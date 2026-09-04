package com.polish.twitter.processor

import com.polish.twitter.core.Logger
import org.json.JSONObject

/**
 * 按推文 / 媒体 ID 缓存「可下载的完整媒体」。
 *
 * 播放器 OkHttp 抓到的 DASH 分片只用来更新 [currentMediaId]，
 * 真正下载始终走 GraphQL variants 里的 progressive MP4 / orig 图。
 */
object MediaCache {

    private const val MAX_TWEETS = 250

    private val byTweetId = object : LinkedHashMap<String, MutableList<ExtractedMedia>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ExtractedMedia>>?): Boolean {
            return size > MAX_TWEETS
        }
    }

    private val byMediaId = LinkedHashMap<String, ExtractedMedia>(64, 0.75f, true)

    @Volatile
    var currentTweetId: String? = null
        private set

    @Volatile
    var currentMediaId: String? = null
        private set

    @Synchronized
    fun clear() {
        byTweetId.clear()
        byMediaId.clear()
        currentTweetId = null
        currentMediaId = null
    }

    @Synchronized
    fun ingestJson(rawJson: String) {
        if (rawJson.isBlank()) return
        val collected = MediaExtractor.collectAllMediaFromString(rawJson)
        if (collected.isEmpty()) return
        for (media in collected) {
            put(media)
        }
        Logger.d("MediaCache ingested ${collected.size} media item(s)")
    }

    @Synchronized
    fun ingestObject(obj: JSONObject) {
        val collected = MediaExtractor.collectAllMedia(obj)
        for (media in collected) {
            put(media)
        }
    }

    @Synchronized
    fun notePlaybackUrl(url: String) {
        val mediaId = MediaExtractor.extractMediaId(url) ?: return
        currentMediaId = mediaId
        val mapped = byMediaId[mediaId]
        if (mapped != null && mapped.tweetId.isNotBlank()) {
            currentTweetId = mapped.tweetId
        } else if (byTweetId.containsKey(mediaId)) {
            currentTweetId = mediaId
        }
    }

    @Synchronized
    fun noteTweetId(tweetId: String?) {
        if (tweetId.isNullOrBlank()) return
        currentTweetId = tweetId
    }

    /**
     * 解析当前应下载的媒体列表。优先：指定推文 > 正在播放的 mediaId > 当前推文。
     * 绝不回退到「时间线里最后一条视频」，那会下错推。
     */
    @Synchronized
    fun resolve(tweetId: String? = null): List<ExtractedMedia> {
        val explicit = tweetId?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            byTweetId[explicit]?.let { return sorted(it) }
        }

        currentMediaId?.let { mid ->
            byMediaId[mid]?.let { hit ->
                val siblings = byTweetId[hit.tweetId]
                if (!siblings.isNullOrEmpty()) return sorted(siblings)
                return listOf(hit)
            }
            byTweetId[mid]?.let { return sorted(it) }
        }

        val current = currentTweetId
        if (!current.isNullOrBlank()) {
            byTweetId[current]?.let { return sorted(it) }
        }

        if (explicit != null) {
            return emptyList()
        }
        return emptyList()
    }

    fun bestVideo(): ExtractedMedia? = resolve().firstOrNull { it.isVideo }

    fun bestVideoUrl(): String? = bestVideo()?.url

    private fun put(media: ExtractedMedia) {
        if (media.url.isBlank()) return
        if (media.isVideo && MediaExtractor.isDashSegmentUrl(media.url)) return

        if (media.mediaId.isNotBlank()) {
            val existing = byMediaId[media.mediaId]
            if (existing == null || prefer(media, existing)) {
                byMediaId[media.mediaId] = media
            }
        }

        val tweetId = media.tweetId
        if (tweetId.isNotBlank()) {
            val list = byTweetId.getOrPut(tweetId) { mutableListOf() }
            val idx = list.indexOfFirst { it.url == media.url || (it.mediaId.isNotBlank() && it.mediaId == media.mediaId && it.isVideo == media.isVideo) }
            if (idx >= 0) {
                if (prefer(media, list[idx])) {
                    list[idx] = media
                }
            } else {
                list.add(media)
            }
        }
    }

    private fun prefer(candidate: ExtractedMedia, current: ExtractedMedia): Boolean {
        if (candidate.isVideo && current.isVideo) {
            return candidate.bitrate >= current.bitrate
        }
        return false
    }

    private fun sorted(items: List<ExtractedMedia>): List<ExtractedMedia> {
        return items.sortedWith(compareByDescending<ExtractedMedia> { it.isVideo }.thenByDescending { it.bitrate })
    }
}
