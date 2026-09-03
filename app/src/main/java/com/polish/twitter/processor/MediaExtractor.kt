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
    val height: Int = 0
)

object MediaExtractor {

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

                            if (contentType == "video/mp4" && videoUrl.isNotBlank()) {
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
}
