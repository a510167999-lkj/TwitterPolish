package com.polish.twitter.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.MediaExtractor
import java.io.File

/**
 * ExoPlayer 把正在拉的 HLS URL 写进 exoplayer_internal.db。
 * 视频不走 OkHttp，GraphQL 又经常没有 progressive MP4，长按时从这里取当前最高清音视频播放列表。
 */
object ExoCacheProbe {

    private val AVC_PLAYLIST = Regex("""/pl/avc1/(\d+)x(\d+)/[^?\s]+\.m3u8""")
    private val AUDIO_PLAYLIST = Regex("""/pl/mp4a/(\d+)/[^?\s]+\.m3u8""")
    private val MASTER_PLAYLIST = Regex("""/pl/[^/]+\.m3u8""")

    fun findCurrentHls(context: Context, preferredMediaId: String? = null): ExtractedMedia? {
        val keys = readCacheKeys(context)
        if (keys.isEmpty()) return null

        val recentMediaId = keys.asReversed()
            .asSequence()
            .mapNotNull { MediaExtractor.extractMediaId(it) }
            .firstOrNull()

        val mediaId = preferredMediaId?.takeIf { it.isNotBlank() } ?: recentMediaId
        val scoped = if (mediaId != null) {
            val matched = keys.filter { MediaExtractor.extractMediaId(it) == mediaId }
            matched.ifEmpty { keys }
        } else {
            keys
        }

        var bestVideo: String? = null
        var bestPixels = -1
        var bestAudio: String? = null
        var bestAudioRate = -1
        var master: String? = null

        for (key in scoped) {
            if (!key.contains(".m3u8", ignoreCase = true)) continue
            val avc = AVC_PLAYLIST.find(key)
            if (avc != null) {
                val px = (avc.groupValues[1].toIntOrNull() ?: 0) * (avc.groupValues[2].toIntOrNull() ?: 0)
                if (px >= bestPixels) {
                    bestPixels = px
                    bestVideo = key
                }
                continue
            }
            val aud = AUDIO_PLAYLIST.find(key)
            if (aud != null) {
                val rate = aud.groupValues[1].toIntOrNull() ?: 0
                if (rate >= bestAudioRate) {
                    bestAudioRate = rate
                    bestAudio = key
                }
                continue
            }
            if (MASTER_PLAYLIST.containsMatchIn(key) && !key.contains("/avc1/") && !key.contains("/mp4a/")) {
                master = key
            }
        }

        val url = bestVideo ?: master ?: return null
        val id = MediaExtractor.extractMediaId(url) ?: mediaId ?: "hls"
        Logger.i("ExoCache HLS media=$id video=$url audio=$bestAudio")
        return ExtractedMedia(
            url = url,
            isVideo = true,
            fileName = "twitter_${id}_video.mp4",
            bitrate = if (bestPixels > 0) bestPixels.toLong() else bestAudioRate.toLong(),
            width = 0,
            height = 0,
            tweetId = "",
            mediaId = id,
            hlsAudioUrl = bestAudio ?: ""
        )
    }

    internal fun pickFromKeys(keys: List<String>, preferredMediaId: String? = null): ExtractedMedia? {
        // used by tests — mirrors findCurrentHls without Android
        if (keys.isEmpty()) return null
        val recentMediaId = keys.asReversed().asSequence().mapNotNull { MediaExtractor.extractMediaId(it) }.firstOrNull()
        val mediaId = preferredMediaId ?: recentMediaId
        val scoped = if (mediaId != null) keys.filter { MediaExtractor.extractMediaId(it) == mediaId }.ifEmpty { keys } else keys
        var bestVideo: String? = null
        var bestPixels = -1
        var bestAudio: String? = null
        var bestAudioRate = -1
        var master: String? = null
        for (key in scoped) {
            if (!key.contains(".m3u8", ignoreCase = true)) continue
            val avc = AVC_PLAYLIST.find(key)
            if (avc != null) {
                val px = (avc.groupValues[1].toIntOrNull() ?: 0) * (avc.groupValues[2].toIntOrNull() ?: 0)
                if (px >= bestPixels) {
                    bestPixels = px
                    bestVideo = key
                }
                continue
            }
            val aud = AUDIO_PLAYLIST.find(key)
            if (aud != null) {
                val rate = aud.groupValues[1].toIntOrNull() ?: 0
                if (rate >= bestAudioRate) {
                    bestAudioRate = rate
                    bestAudio = key
                }
                continue
            }
            if (MASTER_PLAYLIST.containsMatchIn(key) && !key.contains("/avc1/") && !key.contains("/mp4a/")) {
                master = key
            }
        }
        val url = bestVideo ?: master ?: return null
        val id = MediaExtractor.extractMediaId(url) ?: mediaId ?: "hls"
        return ExtractedMedia(
            url = url,
            isVideo = true,
            fileName = "twitter_${id}_video.mp4",
            mediaId = id,
            hlsAudioUrl = bestAudio ?: ""
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
