package com.polish.twitter.processor

import com.polish.twitter.core.Logger
import org.json.JSONObject

/**
 * 按推文 / 媒体 ID 缓存「可下载的完整媒体」。
 *
 * CDN 分片只在没有播放器锁时用来认 id。真正下载优先 GraphQL / 缓存里的 HLS master。
 */
object MediaCache {

    private const val MAX_TWEETS = 250

    private val byTweetId = object : LinkedHashMap<String, MutableList<ExtractedMedia>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ExtractedMedia>>?): Boolean {
            return size > MAX_TWEETS
        }
    }

    // byMediaId 同样需要 LRU 上限：X 进程长驻，任意 CDN 请求都会写入，
    // 若无淘汰机制内存将持续膨胀（byTweetId 淘汰后这里仍持有旧条目）。
    private val byMediaId = object : LinkedHashMap<String, ExtractedMedia>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExtractedMedia>?): Boolean {
            return size > MAX_TWEETS
        }
    }

    @Volatile
    var currentTweetId: String? = null
        private set

    @Volatile
    var currentMediaId: String? = null
        private set

    /** 由真正在画的 ExoPlayer 写入；CDN 预取不得覆盖。 */
    @Volatile
    var playerLockedMediaId: String? = null
        private set

    /** 锁的写入时间（nanoTime，供测试与 TTL 判断）。 */
    @Volatile
    private var playerLockedAtNs: Long = 0L

    /**
     * 播放器锁新鲜窗口：锁只在 TTL 内可信。
     * 若永久生效，一旦锁定后所有新视频 URL 都被忽略、currentMediaId 永不更新，
     * 导致长按任何视频都解析到同一个陈旧 mediaId（真机回归：相册出现重复文件）。
     */
    internal const val PLAYER_LOCK_TTL_NS: Long = 10_000L * 1_000_000L // 10 秒

    fun isPlayerLockFresh(): Boolean {
        val id = playerLockedMediaId ?: return false
        if (id.isBlank()) return false
        return System.nanoTime() - playerLockedAtNs < PLAYER_LOCK_TTL_NS
    }

    /** 仅新鲜窗口内的锁；过期即返回 null（调用方不可把它当“当前播放”用）。 */
    fun freshPlayerLockMediaId(): String? =
        if (isPlayerLockFresh()) playerLockedMediaId else null

    @Synchronized
    fun clear() {
        byTweetId.clear()
        byMediaId.clear()
        currentTweetId = null
        currentMediaId = null
        playerLockedMediaId = null
        playerLockedAtNs = 0L
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
    fun notePlaybackUrl(url: String, fromPlayer: Boolean = false) {
        val mediaId = MediaExtractor.extractMediaId(url) ?: return
        if (fromPlayer) {
            // 真实渲染/在播事件：刷新锁与时间戳
            playerLockedMediaId = mediaId
            playerLockedAtNs = System.nanoTime()
            currentMediaId = mediaId
        } else {
            // 锁只在新鲜窗口内阻止其它 URL：过期锁立即失效，允许新视频更新 currentMediaId
            val locked = playerLockedMediaId
            if (isPlayerLockFresh() && locked != null && locked != mediaId) {
                Logger.d("Ignoring prefetch/stale media=$mediaId playing=$locked")
                return
            }
            val isIndexUrl = MediaExtractor.isHlsPlaylistUrl(url) ||
                MediaExtractor.isProgressiveMp4Url(url)
            if (!isIndexUrl && currentMediaId != null && currentMediaId != mediaId) {
                // 非索引分片：仅当 current 也是新鲜锁定的值时才拒绝串片；
                // 否则 currentMediaId 已陈旧，应当允许切换
                if (isPlayerLockFresh()) {
                    Logger.d("Ignoring other-video segment media=$mediaId current=$currentMediaId")
                    return
                }
            }
            currentMediaId = mediaId
        }
        val mapped = byMediaId[mediaId]
        if (mapped != null && mapped.tweetId.isNotBlank()) {
            currentTweetId = mapped.tweetId
        } else if (byTweetId.containsKey(mediaId)) {
            currentTweetId = mediaId
        }
    }

    /** 仅测试用：手动让锁过期，模拟 TTL 窗口外行为。 */
    internal fun debugExpirePlayerLock() {
        playerLockedAtNs = System.nanoTime() - PLAYER_LOCK_TTL_NS - 1_000_000L
    }

    @Synchronized
    fun noteTweetId(tweetId: String?) {
        if (tweetId.isNullOrBlank()) return
        currentTweetId = tweetId
    }

    /**
     * 解析应下载的媒体。
     * [mediaId] 来自手指下的播放器时，只返回那一条视频（外加同推文原图），找不到就空列表，绝不改下别的片。
     */
    @Synchronized
    fun resolve(tweetId: String? = null, mediaId: String? = null): List<ExtractedMedia> {
        val wantedMedia = mediaId?.takeIf { it.isNotBlank() }
        if (wantedMedia != null) {
            byMediaId[wantedMedia]?.let { hit ->
                val photos = if (hit.tweetId.isNotBlank()) {
                    byTweetId[hit.tweetId].orEmpty().filter { !it.isVideo }
                } else {
                    emptyList()
                }
                return listOf(hit) + photos
            }
            byTweetId[wantedMedia]?.let { return sorted(it) }
            return emptyList()
        }

        val explicit = tweetId?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            byTweetId[explicit]?.let { return prioritizeCurrent(sorted(it)) }
        }

        currentMediaId?.let { mid ->
            byMediaId[mid]?.let { hit ->
                val siblings = byTweetId[hit.tweetId]
                if (!siblings.isNullOrEmpty()) return prioritizeCurrent(sorted(siblings))
                return listOf(hit)
            }
            byTweetId[mid]?.let { return prioritizeCurrent(sorted(it)) }
        }

        val current = currentTweetId
        if (!current.isNullOrBlank()) {
            byTweetId[current]?.let { return prioritizeCurrent(sorted(it)) }
        }

        return emptyList()
    }

    fun bestVideo(): ExtractedMedia? = resolve().firstOrNull { it.isVideo }

    fun bestVideoUrl(): String? = bestVideo()?.url

    /** 仅测试用探针：断言 byMediaId 缓存受 LRU 上限约束。 */
    internal fun debugMediaIdCacheSize(): Int = byMediaId.size

    private fun put(media: ExtractedMedia) {
        if (media.url.isBlank()) return
        if (media.isVideo && MediaExtractor.isDashSegmentUrl(media.url) &&
            !MediaExtractor.isHlsPlaylistUrl(media.url)
        ) return

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
            return MediaExtractor.downloadQuality(candidate) > MediaExtractor.downloadQuality(current)
        }
        return false
    }

    private fun sorted(items: List<ExtractedMedia>): List<ExtractedMedia> {
        return items.sortedWith(
            compareByDescending<ExtractedMedia> { it.isVideo }
                .thenByDescending { MediaExtractor.downloadQuality(it) }
        )
    }

    private fun prioritizeCurrent(items: List<ExtractedMedia>): List<ExtractedMedia> {
        val mid = currentMediaId ?: return items
        val idx = items.indexOfFirst { it.mediaId == mid }
        if (idx <= 0) return items
        return listOf(items[idx]) + items.filterIndexed { i, _ -> i != idx }
    }
}
