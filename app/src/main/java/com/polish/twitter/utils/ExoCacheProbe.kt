package com.polish.twitter.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.processor.MediaExtractor
import java.io.File

/**
 * ExoPlayer 把正在拉的 HLS URL 写进 exoplayer_internal.db。
 * 视频不走 OkHttp，GraphQL 又经常没有 progressive MP4，长按时从这里取当前最高清音视频播放列表。
 */
object ExoCacheProbe {

    private val AUDIO_PLAYLIST = Regex("""/pl/mp4a/(\d+)/[^?\s]+\.m3u8""")

    fun findCurrentHls(context: Context, mediaId: String): ExtractedMedia? {
        val picked = pickFromKeys(readCacheKeys(context), mediaId) ?: return null
        Logger.d("ExoCache HLS media=${picked.mediaId}")
        return picked
    }

    /**
     * 只取 [mediaId] 这条视频的缓存。不知道 id 时返回 null：缓存索引按写入顺序排，
     * 「最后一条」可能是预取的别的视频，也可能是早已删除、播放列表已 404 的旧视频。
     */
    internal fun pickFromKeys(keys: List<String>, mediaId: String?): ExtractedMedia? {
        if (mediaId.isNullOrBlank()) return null
        val scoped = keys.filter { MediaExtractor.extractMediaId(it) == mediaId }
        if (scoped.isEmpty()) return null

        var master: String? = null
        var bestVideo: String? = null
        var bestPixels = -1L
        var bestAudio: String? = null
        var bestAudioRate = -1

        for (key in scoped) {
            if (!key.contains(".m3u8", ignoreCase = true)) continue
            if (HlsPlaylistParser.isMasterPlaylistUrl(key)) {
                master = key
                continue
            }
            val px = HlsPlaylistParser.variantPixels(key)
            if (px > 0 && px >= bestPixels) {
                bestPixels = px
                bestVideo = key
                continue
            }
            val aud = AUDIO_PLAYLIST.find(key)
            if (aud != null) {
                val rate = aud.groupValues[1].toIntOrNull() ?: 0
                if (rate >= bestAudioRate) {
                    bestAudioRate = rate
                    bestAudio = key
                }
            }
        }

        // master 列出全部档位；变体只是播放器当前在拉的那一档（常见 720p）
        val url = master ?: bestVideo ?: return null
        val id = MediaExtractor.extractMediaId(url) ?: mediaId
        return ExtractedMedia(
            url = url,
            isVideo = true,
            fileName = "twitter_${id}_video.mp4",
            bitrate = bestPixels,
            mediaId = id,
            hlsAudioUrl = if (master != null) "" else (bestAudio ?: "")
        )
    }

    private fun readCacheKeys(context: Context): List<String> {
        val dbFile = File(context.applicationInfo.dataDir, "databases/exoplayer_internal.db")
        if (!dbFile.exists()) {
            Logger.d("exoplayer_internal.db not found")
            return emptyList()
        }
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(
                dbFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            val tables = mutableListOf<String>()
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'ExoPlayerCacheIndex%'",
                null
            ).use { c ->
                while (c.moveToNext()) tables.add(c.getString(0))
            }
            val keys = mutableListOf<String>()
            for (table in tables) {
                if (!DownloadSecurity.isSafeSqlIdentifier(table)) continue
                db.rawQuery("SELECT key FROM `$table` ORDER BY id ASC", null).use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0) ?: continue
                        if (key.contains("video.twimg.com") || key.contains("ext_tw_video") || key.contains("amplify_video")) {
                            keys.add(key)
                        }
                    }
                }
            }
            keys
        } catch (t: Throwable) {
            Logger.w("ExoCacheProbe read failed: ${t.message}")
            emptyList()
        } finally {
            try { db?.close() } catch (_: Throwable) {}
        }
    }
}
