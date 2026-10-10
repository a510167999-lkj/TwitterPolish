package com.polish.twitter.utils

import android.app.Activity
import android.content.Context
import android.view.View
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor

/**
 * 长按 / 悬浮球 / 设置共用的保存解析。
 *
 * 关键语义（真机回归教训）：
 * - 长按（有 videoView）：必须只认手指下那块画面。认不出就返回空列表让用户重试，
 *   **绝不回落**全局播放器、播放器锁或「Exo 缓存里最近一条」——那些都可能是预取的上一条/下一条，
 *   甚至是早已删除的视频（真机：不同视频都解析成同一个已 404 的 mediaId）。
 * - 悬浮球 / 设置按钮（无 videoView）：没有手指信息，允许按可见、在播、面积最大选播放器。
 * - Exo 缓存只按已确定的 mediaId 查，不再猜。
 */
object DownloadResolver {

    fun resolve(
        context: Context,
        tweetId: String? = null,
        videoView: View? = null
    ): List<ExtractedMedia> {
        val playingId = if (videoView != null) {
            PlayerPlaybackTracker.syncViewIntoCache(videoView) ?: return emptyList()
        } else {
            PlayerPlaybackTracker.syncPlayingIntoCache(context as? Activity)
                ?: MediaCache.freshPlayerLockMediaId()
        }

        val items = MediaCache.resolve(tweetId, playingId)
        val hls = playingId?.let {
            try {
                ExoCacheProbe.findCurrentHls(context, it)
            } catch (_: Throwable) {
                null
            }
        }
        return merge(items, hls, playingId)
    }

    internal fun merge(
        items: List<ExtractedMedia>,
        hls: ExtractedMedia?,
        playingId: String?
    ): List<ExtractedMedia> {
        var result = items
        if (playingId != null) {
            result = prioritize(result, playingId)
        }
        if (hls == null) return result

        val idx = result.indexOfFirst { it.isVideo && sameVideo(it, hls) }
        if (idx >= 0) {
            val existing = result[idx]
            if (MediaExtractor.downloadQuality(hls) > MediaExtractor.downloadQuality(existing)) {
                val copy = result.toMutableList()
                copy[idx] = hls.copy(
                    tweetId = existing.tweetId.ifBlank { hls.tweetId },
                    fileName = existing.fileName.ifBlank { hls.fileName },
                    mediaId = existing.mediaId.ifBlank { hls.mediaId }
                )
                return copy
            }
            return result
        }

        if (result.none { it.isVideo }) {
            return listOf(hls) + result
        }

        if (playingId != null && hls.mediaId == playingId) {
            val wrong = result.indexOfFirst { it.isVideo && it.mediaId != playingId }
            if (wrong >= 0) {
                val copy = result.toMutableList()
                copy[wrong] = hls
                return copy
            }
        }
        return result
    }

    private fun sameVideo(item: ExtractedMedia, hls: ExtractedMedia): Boolean {
        if (item.mediaId.isBlank() || hls.mediaId.isBlank()) return false
        return item.mediaId == hls.mediaId
    }

    private fun prioritize(items: List<ExtractedMedia>, playingId: String): List<ExtractedMedia> {
        val idx = items.indexOfFirst { it.mediaId == playingId }
        if (idx <= 0) return items
        return listOf(items[idx]) + items.filterIndexed { i, _ -> i != idx }
    }
}
