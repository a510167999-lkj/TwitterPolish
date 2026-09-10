package com.polish.twitter.utils

import android.content.Context
import android.view.View
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor

/**
 * 长按 / 悬浮球 / 设置共用的保存解析。
 *
 * 关键语义（真机回归教训）：
 * - 长按（有 videoView）：必须只认手指下那块画面。view→player 解析失败时
 *   **绝不回落**全局播放器选择——selectPlayer 会抓到预取的下一条/上一条，
 *   导致“长按 A 保存 B”，这是相册出现重复错误视频的直接原因。
 * - 悬浮球 / 设置按钮（无 videoView）：没有手指信息，允许全局播放器选择。
 * - 播放器锁只在新鲜窗口内可信（MediaCache.freshPlayerLockMediaId）。
 */
object DownloadResolver {

    fun resolve(
        context: Context,
        tweetId: String? = null,
        videoView: View? = null
    ): List<ExtractedMedia> {
        val fromView = videoView?.let { PlayerPlaybackTracker.syncViewIntoCache(it) }
        // 长按路径（videoView != null）绝不回落全局播放器选择；
        // 悬浮球 / 设置路径（videoView == null）没有手指信息，才允许全局选择。
        val playingId = fromView
            ?: if (videoView == null) PlayerPlaybackTracker.syncPlayingIntoCache() else null

        // 新鲜锁：真实渲染/在播事件 10 秒内写入的 mediaId，可信；过期锁不采信
        val freshLock = MediaCache.freshPlayerLockMediaId()
        val effectiveMediaId = playingId ?: freshLock

        val items = MediaCache.resolve(tweetId, effectiveMediaId)
        val probeId = effectiveMediaId ?: MediaCache.currentMediaId
        val hls = try {
            ExoCacheProbe.findCurrentHls(context, probeId)
        } catch (_: Throwable) {
            null
        }
        return merge(items, hls, playingId ?: probeId)
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

        val idx = result.indexOfFirst { it.isVideo && sameVideo(it, hls, playingId) }
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

    private fun sameVideo(item: ExtractedMedia, hls: ExtractedMedia, playingId: String?): Boolean {
        if (item.mediaId.isNotBlank() && hls.mediaId.isNotBlank()) {
            return item.mediaId == hls.mediaId
        }
        if (playingId != null && item.mediaId.isNotBlank()) {
            return item.mediaId == playingId
        }
        return true
    }

    private fun prioritize(items: List<ExtractedMedia>, playingId: String): List<ExtractedMedia> {
        val idx = items.indexOfFirst { it.mediaId == playingId }
        if (idx <= 0) return items
        return listOf(items[idx]) + items.filterIndexed { i, _ -> i != idx }
    }
}
