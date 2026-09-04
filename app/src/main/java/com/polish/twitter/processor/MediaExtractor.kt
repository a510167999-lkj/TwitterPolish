package com.polish.twitter.processor

import com.polish.twitter.core.Logger
import org.json.JSONArray
import org.json.JSONObject

data class ExtractedMedia(
    val url: String,
    val isVideo: Boolean,
    val fileName: String,
    val bitrate: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val tweetId: String = "",
    val mediaId: String = "",
    val hlsAudioUrl: String = ""
)

object MediaExtractor {

    private val TWEET_STATUS_ID = Regex("""(?:twitter\.com|x\.com)/[^/\s]+/status/(\d{15,20})""")
    private val MEDIA_PATH_ID = Regex("""/(?:amplify_video|ext_tw_video|tweet_video)/(\d{15,20})""")
    private val SNOWFLAKE_IN_PATH = Regex("""/(\d{15,20})/""")

    /**
     * 播放器向 CDN 拉的是 DASH/CMAF 分片（路径含 /0/0/ 或 /aud/），
     * 不是 GraphQL variants 里那种可直接保存的 progressive MP4。
     */
    fun isDashSegmentUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (isHlsPlaylistUrl(url)) return false
        val u = url.lowercase()
        return u.contains("/0/0/") ||
            u.contains("/aud/") ||
            u.contains("/mp4a/") ||
            u.contains(".m4s")
    }

    fun isHlsPlaylistUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true)
    }

    fun isProgressiveMp4Url(url: String): Boolean {
        return url.contains(".mp4", ignoreCase = true) && !isDashSegmentUrl(url) && !isHlsPlaylistUrl(url)
    }

    /**
     * 从 video.twimg.com 路径取出 amplify_video / ext_tw_video 的媒体 ID，
     * 用来把「正在播放」对回 GraphQL 里那条完整 MP4。
     */
    fun extractMediaId(url: String): String? {
        MEDIA_PATH_ID.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        SNOWFLAKE_IN_PATH.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    fun parseTweetIdFromShareUrl(text: String): String? {
        return TWEET_STATUS_ID.find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * 完整 MP4 带 ftyp；DASH init 是 ftyp + cmf2/dash，约几百字节，不能当成品保存。
     */
    fun isPlayableMp4Header(header: ByteArray): Boolean {
        if (header.size < 12) return false
        if (indexOfSlice(header, "ftyp".toByteArray()) < 0) return false
        val brands = String(header, Charsets.ISO_8859_1)
        return !brands.contains("dash") && !brands.contains("cmf2")
    }

    private fun indexOfSlice(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * 将推特图片地址转换为无损原图地址（name=orig）
     */
    fun toOriginalImageUrl(url: String): String {
        if (url.isBlank()) return url
        val cleanUrl = url.trim()

        // 格式形如: https://pbs.twimg.com/media/xxxx.jpg
        // 或者: https://pbs.twimg.com/media/xxxx?format=jpg&name=large
        return when {
            cleanUrl.contains("format=") && cleanUrl.contains("name=") -> {
                cleanUrl.replace(Regex("name=[a-zA-Z0-9_]+"), "name=orig")
            }
            cleanUrl.contains("?") -> {
                "$cleanUrl&name=orig"
            }
            else -> {
                "$cleanUrl?name=orig"
            }
        }
    }

    /**
     * 从推文实体 JSON 中提取所有可供下载的最高画质媒体（图片和视频）
     * 支持 standard entities, extended_entities, 以及 GraphQL media 结构
     */
    fun extractMediaFromTweetJson(tweetJson: JSONObject, tweetId: String = ""): List<ExtractedMedia> {
        val result = mutableListOf<ExtractedMedia>()
        try {
            // 尝试获取 extended_entities 或 legacy.extended_entities
            val entities = tweetJson.optJSONObject("extended_entities")
                ?: tweetJson.optJSONObject("legacy")?.optJSONObject("extended_entities")
                ?: tweetJson.optJSONObject("entities")
                ?: tweetJson.optJSONObject("legacy")?.optJSONObject("entities")

            val mediaArray = entities?.optJSONArray("media") ?: JSONArray()
            val baseId = tweetId.ifBlank {
                tweetJson.optString("rest_id", tweetJson.optString("id_str", System.currentTimeMillis().toString()))
            }

            for (i in 0 until mediaArray.length()) {
                val mediaObj = mediaArray.optJSONObject(i) ?: continue
                val type = mediaObj.optString("type")

                if (type == "video" || type == "animated_gif") {
                    // 解析视频变体，寻找最高码率的 mp4 直链
                    val videoInfo = mediaObj.optJSONObject("video_info")
                    val variants = videoInfo?.optJSONArray("variants")
                    if (variants != null) {
                        var bestUrl: String? = null
                        var maxBitrate = -1L

                        for (j in 0 until variants.length()) {
                            val variant = variants.optJSONObject(j) ?: continue
                            val contentType = variant.optString("content_type")
                            val bitrate = variant.optLong("bitrate", 0L)
                            val videoUrl = variant.optString("url")

                            if (contentType == "video/mp4" && isProgressiveMp4Url(videoUrl)) {
                                if (bitrate > maxBitrate || bestUrl == null) {
                                    maxBitrate = bitrate
                                    bestUrl = videoUrl
                                }
                            }
                        }

                        if (bestUrl != null) {
                            val fileName = "twitter_${baseId}_video_${i + 1}.mp4"
                            result.add(
                                ExtractedMedia(
                                    url = bestUrl,
                                    isVideo = true,
                                    fileName = fileName,
                                    bitrate = maxBitrate
                                )
                            )
                        }
                    }
                } else if (type == "photo" || type.isEmpty()) {
                    // 解析图片，获取最高分辨率的原图
                    val rawMediaUrl = mediaObj.optString("media_url_https", mediaObj.optString("media_url"))
                    if (rawMediaUrl.isNotBlank()) {
                        val origUrl = toOriginalImageUrl(rawMediaUrl)
                        val ext = if (rawMediaUrl.contains(".png")) "png" else "jpg"
                        val fileName = "twitter_${baseId}_photo_${i + 1}.$ext"
                        result.add(
                            ExtractedMedia(
                                url = origUrl,
                                isVideo = false,
                                fileName = fileName
                            )
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            Logger.e("Error extracting media from tweet JSON", e)
        }
        return result
    }

    /**
     * 递归收集 GraphQL / URT JSON 里所有 progressive MP4 与原图，带上最近的 tweet rest_id。
     */
    fun collectAllMedia(root: JSONObject): List<ExtractedMedia> {
        val result = mutableListOf<ExtractedMedia>()
        try {
            walkCollect(root, "", result)
        } catch (e: Throwable) {
            Logger.w("collectAllMedia failed: ${e.message}")
        }
        return result.distinctBy { it.url }
    }

    fun collectAllMediaFromString(rawJson: String): List<ExtractedMedia> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            collectAllMedia(JSONObject(rawJson))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun walkCollect(obj: JSONObject, inheritedTweetId: String, out: MutableList<ExtractedMedia>) {
        var tweetId = inheritedTweetId
        val restId = obj.optString("rest_id")
        if (looksLikeTweetId(restId) && isTweetLikeNode(obj)) {
            tweetId = restId
        } else {
            val idStr = obj.optString("id_str")
            if (tweetId.isEmpty() && looksLikeTweetId(idStr) && obj.has("extended_entities")) {
                tweetId = idStr
            }
        }

        val videoInfo = obj.optJSONObject("video_info")
            ?: obj.optJSONObject("videoInfo")
            ?: obj.optJSONObject("media_info")
            ?: obj.optJSONObject("mediaInfo")
        if (videoInfo != null) {
            val picked = pickBestProgressiveMp4(videoInfo) ?: pickBestHls(videoInfo)
            picked?.let {
                val mediaId = obj.optString("id_str").ifBlank {
                    extractMediaId(it.url) ?: tweetId
                }
                val baseId = tweetId.ifBlank { mediaId }
                out.add(
                    it.copy(
                        tweetId = tweetId,
                        mediaId = mediaId,
                        fileName = "twitter_${baseId}_video.mp4"
                    )
                )
            }
        } else if (obj.optString("type") == "photo") {
            val raw = obj.optString("media_url_https").ifBlank { obj.optString("media_url") }
            if (raw.isNotBlank() && raw.contains("pbs.twimg.com")) {
                val orig = toOriginalImageUrl(raw)
                val mediaId = obj.optString("id_str").ifBlank { tweetId }
                val baseId = tweetId.ifBlank { mediaId.ifBlank { "img" } }
                val ext = if (raw.contains(".png")) "png" else "jpg"
                out.add(
                    ExtractedMedia(
                        url = orig,
                        isVideo = false,
                        fileName = "twitter_${baseId}_photo.$ext",
                        tweetId = tweetId,
                        mediaId = mediaId
                    )
                )
            }
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val child = obj.opt(key)) {
                is JSONObject -> walkCollect(child, tweetId, out)
                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        val item = child.optJSONObject(i) ?: continue
                        walkCollect(item, tweetId, out)
                    }
                }
            }
        }
    }

    private fun isTweetLikeNode(obj: JSONObject): Boolean {
        val typeName = obj.optString("__typename")
        if (typeName.contains("User")) return false
        if (typeName.contains("Tweet")) return true
        if (obj.has("extended_entities") || obj.has("quoted_status_result")) return true
        val legacy = obj.optJSONObject("legacy") ?: return false
        return legacy.has("full_text") ||
            legacy.has("extended_entities") ||
            legacy.has("conversation_id_str")
    }

    private fun looksLikeTweetId(id: String): Boolean {
        return id.length in 15..20 && id.all { it.isDigit() }
    }

    internal fun pickBestProgressiveMp4(videoInfo: JSONObject): ExtractedMedia? {
        val variants = videoInfo.optJSONArray("variants") ?: return null
        var bestUrl: String? = null
        var maxBitrate = -1L
        for (i in 0 until variants.length()) {
            val variant = variants.optJSONObject(i) ?: continue
            val contentType = variant.optString("content_type").ifBlank {
                variant.optString("contentType")
            }
            val bitrate = variant.optLong("bitrate", 0L)
            val videoUrl = variant.optString("url")
            if (contentType.contains("mp4") && isProgressiveMp4Url(videoUrl)) {
                if (bitrate > maxBitrate || bestUrl == null) {
                    maxBitrate = bitrate
                    bestUrl = videoUrl
                }
            }
        }
        val url = bestUrl ?: return null
        return ExtractedMedia(
            url = url,
            isVideo = true,
            fileName = "twitter_video.mp4",
            bitrate = maxBitrate
        )
    }

    internal fun pickBestHls(videoInfo: JSONObject): ExtractedMedia? {
        val variants = videoInfo.optJSONArray("variants") ?: return null
        var bestUrl: String? = null
        for (i in 0 until variants.length()) {
            val variant = variants.optJSONObject(i) ?: continue
            val contentType = variant.optString("content_type").ifBlank {
                variant.optString("contentType")
            }
            val videoUrl = variant.optString("url")
            if ((contentType.contains("mpegURL", ignoreCase = true) || isHlsPlaylistUrl(videoUrl)) &&
                isHlsPlaylistUrl(videoUrl)
            ) {
                if (bestUrl == null || videoUrl.contains("/pl/")) {
                    bestUrl = videoUrl
                }
            }
        }
        val url = bestUrl ?: return null
        return ExtractedMedia(
            url = url,
            isVideo = true,
            fileName = "twitter_video.mp4"
        )
    }
}
