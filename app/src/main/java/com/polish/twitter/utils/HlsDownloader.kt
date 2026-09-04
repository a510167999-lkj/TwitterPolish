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
        val masterText = readUtf8(playlistUrl)
        val videoPlaylist: String
        var audioPlaylist = audioPlaylistUrl?.takeIf { it.isNotBlank() }

        if (HlsPlaylistParser.isMasterPlaylist(masterText)) {
            val best = HlsPlaylistParser.pickBest(HlsPlaylistParser.parseMaster(masterText, playlistUrl))
                ?: throw RuntimeException("HLS master 里没有可用视频流")
            videoPlaylist = best.videoUrl
            if (audioPlaylist.isNullOrBlank()) audioPlaylist = best.audioUrl
            Logger.i("HLS master picked ${best.width}x${best.height} bw=${best.bandwidth}")
        } else {
            videoPlaylist = playlistUrl
        }

        onProgress("拉取视频分片…")
        val videoPart = File(workDir, "video.mp4")
        concatPlaylist(videoPlaylist, videoPart)

        var audioPart: File? = null
        if (!audioPlaylist.isNullOrBlank()) {
            onProgress("拉取音轨…")
            val aud = File(workDir, "audio.mp4")
            try {
                concatPlaylist(audioPlaylist, aud)
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

    private fun concatPlaylist(playlistUrl: String, dest: File) {
        val text = readUtf8(playlistUrl)
        val media = HlsPlaylistParser.parseMedia(text, playlistUrl)
        if (media.mapUrl == null && media.segments.isEmpty()) {
            throw RuntimeException("空的 HLS 播放列表")
        }
        FileOutputStream(dest).use { out ->
            media.mapUrl?.let { copyUrl(it, out) }
            for (seg in media.segments) {
                copyUrl(seg, out)
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
            copySamples(videoEx, muxer, vOut)
            if (aOut >= 0) copySamples(audioEx, muxer, aOut)
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

    private fun readUtf8(url: String): String {
        val src = open(url)
        return src.stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
            src.close()
            if (!it.contains("#EXTM3U")) throw RuntimeException("不是 HLS 播放列表")
        }
    }

    private fun copyUrl(url: String, out: FileOutputStream) {
        val src = open(url)
        try {
            src.stream.copyTo(out)
        } finally {
            src.close()
        }
    }

    private data class Opened(val stream: java.io.InputStream, val close: () -> Unit)

    private fun open(url: String): Opened {
        val via = HostOkHttp.open(url)
        if (via != null) return Opened(via.stream, via.close)
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("Referer", "https://x.com/")
        conn.setRequestProperty("Origin", "https://x.com")
        conn.connect()
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            throw RuntimeException("HTTP ${conn.responseCode} $url")
        }
        return Opened(conn.inputStream) { conn.disconnect() }
    }

    fun copyFileTo(src: File, dest: java.io.OutputStream) {
        FileInputStream(src).use { it.copyTo(dest) }
    }
}
