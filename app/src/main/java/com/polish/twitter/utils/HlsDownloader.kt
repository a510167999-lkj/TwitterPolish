package com.polish.twitter.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.HlsPlaylistParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

object HlsDownloader {

    fun downloadToFile(
        playlistUrl: String,
        audioPlaylistUrl: String?,
        output: File,
        workDir: File,
        onProgress: (String) -> Unit = {}
    ) {
        workDir.mkdirs()
        val masterText = readUtf8(playlistUrl, playlistUrl)
        val videoPlaylist: String
        var audioPlaylist = audioPlaylistUrl?.takeIf { it.isNotBlank() }

        if (HlsPlaylistParser.isMasterPlaylist(masterText)) {
            val best = HlsPlaylistParser.pickBest(HlsPlaylistParser.parseMaster(masterText, playlistUrl))
                ?: throw RuntimeException("HLS master 里没有可用视频流")
            videoPlaylist = best.videoUrl
            audioPlaylist = best.audioUrl ?: audioPlaylist
            onProgress("最高清 ${best.width}x${best.height}")
            Logger.d("HLS master picked ${best.width}x${best.height} bw=${best.bandwidth}")
        } else {
            videoPlaylist = playlistUrl
            Logger.d("HLS media playlist (no master)")
        }

        onProgress("拉取视频分片…")
        val videoPart = File(workDir, "video.mp4")
        concatPlaylist(videoPlaylist, videoPart, playlistUrl)

        var audioPart: File? = null
        if (!audioPlaylist.isNullOrBlank()) {
            onProgress("拉取音轨…")
            val aud = File(workDir, "audio.mp4")
            try {
                concatPlaylist(audioPlaylist, aud, playlistUrl)
                audioPart = aud
            } catch (t: Throwable) {
                Logger.w("Audio track download failed, continuing video-only: ${t.message}")
            }
        }

        onProgress("合并音视频…")
        if (audioPart != null && audioPart.length() > 0) {
            try {
                mux(videoPart, audioPart, output)
            } catch (t: Throwable) {
                Logger.w("MediaMuxer failed (${t.message}), saving video track only")
                videoPart.copyTo(output, overwrite = true)
            }
        } else {
            videoPart.copyTo(output, overwrite = true)
        }

        if (output.length() < 50_000L) {
            throw RuntimeException("合成结果过小（${output.length()}B），不是完整视频")
        }
        Logger.i("HLS mux complete: ${output.length()} bytes")
    }

    private fun concatPlaylist(playlistUrl: String, dest: File, anchor: String) {
        val text = readUtf8(playlistUrl, anchor)
        val media = HlsPlaylistParser.parseMedia(text, playlistUrl)
        if (media.mapUrl == null && media.segments.isEmpty()) {
            throw RuntimeException("空的 HLS 播放列表")
        }
        FileOutputStream(dest).use { out ->
            media.mapUrl?.let { copyUrl(it, out, anchor) }
            for (seg in media.segments) {
                copyUrl(seg, out, anchor)
            }
        }
    }

    private fun mux(videoFile: File, audioFile: File, output: File) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoEx.setDataSource(videoFile.absolutePath)
            audioEx.setDataSource(audioFile.absolutePath)
            val vTrack = findTrack(videoEx, "video/")
            val aTrack = findTrack(audioEx, "audio/")
            if (vTrack < 0) throw RuntimeException("视频轨无法解析")
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoEx.selectTrack(vTrack)
            val vOut = muxer.addTrack(videoEx.getTrackFormat(vTrack))
            var aOut = -1
            if (aTrack >= 0) {
                audioEx.selectTrack(aTrack)
                aOut = muxer.addTrack(audioEx.getTrackFormat(aTrack))
            }
            muxer.start()
            // 按 PTS 交错写入两路样本：先写满一个轨道再写另一个会使部分设备
            // MediaMuxer 失败或生成音画不同步文件
            if (aOut >= 0) {
                copySamplesInterleaved(videoEx, vOut, audioEx, aOut, muxer)
            } else {
                copySamples(videoEx, muxer, vOut)
            }
            muxer.stop()
        } finally {
            try { videoEx.release() } catch (_: Throwable) {}
            try { audioEx.release() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString("mime") ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return if (extractor.trackCount > 0 && mimePrefix.startsWith("video")) 0 else -1
    }

    /** 单轨直写（无音频时使用）。 */
    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }

    /** 两个抽取器各持独立 buffer/info，按 PTS 取小者写入，直到双轨耗尽。 */
    private fun copySamplesInterleaved(
        videoEx: MediaExtractor,
        vOut: Int,
        audioEx: MediaExtractor,
        aOut: Int,
        muxer: MediaMuxer
    ) {
        val v = TrackState(videoEx, muxer, vOut)
        val a = TrackState(audioEx, muxer, aOut)
        while (true) {
            val vReady = v.ensure()
            val aReady = a.ensure()
            if (!vReady && !aReady) break
            when {
                !vReady -> a.write()
                !aReady -> v.write()
                v.info.presentationTimeUs <= a.info.presentationTimeUs -> v.write()
                else -> a.write()
            }
        }
    }

    /** 单个抽取器的读取状态（保留下一待写样本）。 */
    private class TrackState(
        private val extractor: MediaExtractor,
        private val muxer: MediaMuxer,
        private val trackIndex: Int
    ) {
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        private var hasPending = false
        private var done = false

        /** 确保有一份未写入的样本；返回 false 表示该轨已耗尽。 */
        fun ensure(): Boolean {
            if (done) return false
            if (!hasPending) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    done = true
                    return false
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                hasPending = true
            }
            return true
        }

        fun write() {
            muxer.writeSampleData(trackIndex, buffer, info)
            hasPending = false
            extractor.advance()
        }
    }

    private fun readUtf8(url: String, anchor: String): String {
        val src = open(url, anchor)
        return src.stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
            src.close()
            if (!it.contains("#EXTM3U")) throw RuntimeException("不是 HLS 播放列表")
        }
    }

    private fun copyUrl(url: String, out: FileOutputStream, anchor: String) {
        val src = open(url, anchor)
        try {
            src.stream.copyTo(out)
        } finally {
            src.close()
        }
    }

    private data class Opened(val stream: java.io.InputStream, val close: () -> Unit)

    private fun open(url: String, anchor: String): Opened {
        val pin = DownloadSecurity.hostOf(anchor) ?: throw RuntimeException("无效播放列表地址")
        val credentials = DownloadSecurity.mayAttachCredentials(anchor)
        val opened = DownloadClient.open(url, credentials, pin)
        return Opened(opened.stream, opened.close)
    }

    fun copyFileTo(src: File, dest: java.io.OutputStream) {
        FileInputStream(src).use { it.copyTo(dest) }
    }
}
