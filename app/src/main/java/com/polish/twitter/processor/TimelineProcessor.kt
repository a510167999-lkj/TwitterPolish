package com.polish.twitter.processor

import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import org.json.JSONArray
import org.json.JSONObject

object TimelineProcessor {

    // 只认“有值”的推广字段：普通推文里也可能带 "promotedMetadata":null 之类的空占位，
    // 只看 key 名会把整条正常推文删掉。JSONObject.toString() 输出无空格，可直接匹配 ":{" / ":true"。
    // Web 版是驼峰命名；X Android 12.33 的 Apollo 响应是下划线命名（promoted_metadata、rtb_* 等），
    // 推广推文的 tweet_display_type 为 CompactPromotedTweet / EmphasizedPromotedTweet / MapCardPromotedTweet。
    // 不认 is_paid_promotion：那是作者自己声明的「付费推广」（content_disclosure），是正常推文，不是 X 广告。
    private val AD_STRING_PATTERN = Regex(
        "\"(promotedMetadata|promoted_metadata|rtb_promoted_metadata|rtb_ad_metadata|advertiserResults|" +
            "advertiser_results|promotedPost|promotedContent|promotedTrend|promoted_trend|promotedTweet)\":\\{\"|" +
            "\"(is_promoted|isPromoted)\":true|" +
            "\"source_type\":\"promoted\"|" +
            "\"tweet_display_type\":\"[A-Za-z]*PromotedTweet\"",
        RegexOption.IGNORE_CASE
    )

    /** 只清媒体警告。不可用、年龄、withheld 等插屏保留。 */
    private val SENSITIVE_KEYS_REMOVE = setOf(
        "sensitive_media_warning",
        "ext_sensitive_media_warning"
    )

    private val SENSITIVE_BOOL_KEYS = setOf(
        "possibly_sensitive",
        "possibly_sensitive_editable"
    )

    /** 返回是否真的改过节点。没改过时调用方必须交回原字符串。 */
    private fun scrubSensitiveFlags(node: JSONObject): Boolean {
        var changed = false
        for (key in SENSITIVE_KEYS_REMOVE) {
            if (node.has(key)) {
                node.remove(key)
                changed = true
            }
        }
        for (key in SENSITIVE_BOOL_KEYS) {
            if (node.optBoolean(key, false)) {
                node.put(key, false)
                changed = true
            }
        }
        val keys = node.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val child = node.optJSONObject(k)
            if (child != null) {
                if (scrubSensitiveFlags(child)) changed = true
            } else {
                val arr = node.optJSONArray(k)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        if (scrubSensitiveFlags(item)) changed = true
                    }
                }
            }
        }
        return changed
    }

    /**
     * 处理时间线响应 JSON 字符串：
     * 1. 递归删除推广推文、推荐关注与话题推荐条目
     * 2. 可选：去掉媒体警告字段。其他插屏不动
     * 不改条目顺序：详情页、书签、搜索热门的顺序由服务端决定，客户端重排会打乱会话结构。
     * 没改到任何节点时返回原字符串实例，避免无意义的重序列化。
     */
    fun processTimelineResponse(
        rawJson: String,
        enableAdBlock: Boolean = true,
        enableBypassSensitive: Boolean = false
    ): String {
        if (!enableAdBlock && !enableBypassSensitive) return rawJson

        try {
            // 快速前置判断：不含时间线结构也不含推广字段时直接返回。
            // enableBypassSensitive 模式下单条推文接口也要处理，不能以 instructions/entries 作为过滤条件。
            if (!enableBypassSensitive &&
                !rawJson.contains("instructions") && !rawJson.contains("entries") &&
                !rawJson.contains("promoted", ignoreCase = true)) {
                return rawJson
            }

            val root = JSONObject(rawJson)

            val scrubbed = if (enableBypassSensitive) scrubSensitiveFlags(root) else false
            val modified = if (enableAdBlock) removeAds(root) else false

            return if (modified || scrubbed) root.toString() else rawJson
        } catch (e: Throwable) {
            Logger.w("Failed to process timeline JSON, fallback to raw", e)
            return rawJson
        }
    }

    /**
     * 深度递归遍历 JSON 节点，处理所有出现的 instructions 或 entries 数组
     */
    private fun removeAds(jsonObj: JSONObject): Boolean {
        var modified = false

        // 1. 处理直接包含的 instructions
        //    倒序遍历：删除元素后索引不回退，杜绝“跳过下一个元素”导致连续广告漏删
        val instructions = jsonObj.optJSONArray("instructions")
        if (instructions != null) {
            for (i in instructions.length() - 1 downTo 0) {
                val instruction = instructions.optJSONObject(i) ?: continue
                val type = instruction.optString("type", "")

                if (type == "TimelineAddEntries" || instruction.has("entries")) {
                    val entries = instruction.optJSONArray("entries")
                    if (entries != null && removeAdEntries(entries)) modified = true
                } else if (type == "TimelinePinEntry" || instruction.has("entry")) {
                    val entry = instruction.optJSONObject("entry")
                    if (entry != null && isAdOrRecommendation(entry)) {
                        instructions.remove(i)
                        modified = true
                    }
                }
            }
        }

        // 2. 处理直接包含的 entries 数组
        val directEntries = jsonObj.optJSONArray("entries")
        if (directEntries != null && !jsonObj.has("instructions")) {
            if (removeAdEntries(directEntries)) modified = true
        }

        // 3. 递归遍历所有子对象和子数组
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "instructions" || key == "entries") continue

            val childObj = jsonObj.optJSONObject(key)
            if (childObj != null) {
                if (removeAds(childObj)) modified = true
            } else {
                val childArr = jsonObj.optJSONArray(key)
                if (childArr != null) {
                    for (j in 0 until childArr.length()) {
                        val item = childArr.optJSONObject(j)
                        if (item != null && removeAds(item)) modified = true
                    }
                }
            }
        }

        return modified
    }

    /** 原地删除广告条目，保持其余条目原有顺序。返回是否删过。 */
    private fun removeAdEntries(entries: JSONArray): Boolean {
        var adCount = 0
        for (i in entries.length() - 1 downTo 0) {
            val entry = entries.optJSONObject(i) ?: continue
            if (isAdOrRecommendation(entry)) {
                entries.remove(i)
                adCount++
            }
        }
        if (adCount > 0) {
            Logger.d("Filtered $adCount ads/recommendations from timeline batch.")
        }
        return adCount > 0
    }

    /**
     * 判断某个条目是否为广告、推广、推荐关注或话题推荐
     */
    fun isAdOrRecommendation(entry: JSONObject): Boolean {
        // Web 版 entryId / entryType / clientEventInfo；X Android 12.33 为 entry_id / __typename / client_event_info。
        val entryId = entry.optString("entryId", "").ifEmpty { entry.optString("entry_id", "") }
        val lowerEntryId = entryId.lowercase()

        // 1. entryId 匹配广告与推荐前缀
        if (lowerEntryId.contains("promoted") ||
            lowerEntryId.contains("advertisement") ||
            lowerEntryId.startsWith("tweet-promoted") ||
            lowerEntryId.startsWith("promoted-") ||
            lowerEntryId.startsWith("promotedtweet-") ||
            Constants.AD_ENTRY_PREFIXES.any { lowerEntryId.startsWith(it.lowercase()) }) {
            return true
        }

        // 2. 结构化模块检查 (如推荐关注横栏、趋势轮播) —— 零序列化开销，优先执行
        val content = entry.optJSONObject("content")
        val entryType = content?.optString("entryType", "")?.ifEmpty { content.optString("__typename", "") }
        if (entryType == "TimelineTimelineModule") {
            val clientEventInfo = content.optJSONObject("clientEventInfo") ?: content.optJSONObject("client_event_info")
            val component = clientEventInfo?.optString("component", "") ?: ""
            if (component.contains("suggest", ignoreCase = true) ||
                component.contains("who_to_follow", ignoreCase = true) ||
                component.contains("whoToFollow", ignoreCase = true) ||
                component.contains("trend", ignoreCase = true) ||
                component.contains("topic", ignoreCase = true)) {
                return true
            }
        }

        // 3. 深度 JSON 特征匹配：任意嵌套深度上出现有值的推广字段
        if (AD_STRING_PATTERN.containsMatchIn(entry.toString())) {
            return true
        }

        return false
    }
}
