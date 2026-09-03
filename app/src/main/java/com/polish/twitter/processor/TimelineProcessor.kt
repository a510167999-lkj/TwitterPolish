package com.polish.twitter.processor

import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object TimelineProcessor {

    private val SNOWFLAKE_REGEX = Regex("(\\d{16,20})")
    private val TWITTER_DATE_FORMAT = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * 处理时间线响应 JSON 字符串：
     * 1. 彻底递归过滤推文流中的所有广告、已推广（Promoted）推文、关注推荐与话题推广
     * 2. 依据推文 Snowflake ID 严格按时间倒序重排（纯时间线）
     * 3. 同步重构 URT 的 sortIndex / sort_index 字段，确保客户端 SQLite 数据库中也是严格按时间倒序
     * 4. 保持分页游标完整，不影响上下滑动加载
     */
    fun processTimelineResponse(
        rawJson: String,
        enableAdBlock: Boolean = true,
        enableChronoSort: Boolean = true
    ): String {
        if (!enableAdBlock && !enableChronoSort) return rawJson

        try {
            // 快速前置判断：如果不含任何时间线或推广相关字段，直接返回
            if (!rawJson.contains("instructions") && !rawJson.contains("entries") && !rawJson.contains("promoted", ignoreCase = true)) {
                return rawJson
            }

            val root = JSONObject(rawJson)
            val modified = cleanJsonObject(root, enableAdBlock, enableChronoSort)

            return if (modified) root.toString() else rawJson
        } catch (e: Throwable) {
            Logger.w("Failed to process timeline JSON, fallback to raw", e)
            return rawJson
        }
    }

    /**
     * 深度递归遍历 JSON 节点，处理所有出现的 instructions 或 entries 数组
     */
    private fun cleanJsonObject(
        jsonObj: JSONObject,
        enableAdBlock: Boolean,
        enableChronoSort: Boolean
    ): Boolean {
        var modified = false

        // 1. 处理直接包含的 instructions
        val instructions = jsonObj.optJSONArray("instructions")
        if (instructions != null) {
            for (i in 0 until instructions.length()) {
                val instruction = instructions.optJSONObject(i) ?: continue
                val type = instruction.optString("type", "")

                if (type == "TimelineAddEntries" || instruction.has("entries")) {
                    val entries = instruction.optJSONArray("entries")
                    if (entries != null && entries.length() > 0) {
                        val newEntries = processEntries(entries, enableAdBlock, enableChronoSort)
                        instruction.put("entries", newEntries)
                        modified = true
                    }
                } else if ((type == "TimelinePinEntry" || instruction.has("entry")) && enableAdBlock) {
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
            val newEntries = processEntries(directEntries, enableAdBlock, enableChronoSort)
            jsonObj.put("entries", newEntries)
            modified = true
        }

        // 3. 递归遍历所有子对象和子数组
        val keys = jsonObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "instructions" || key == "entries") continue

            val childObj = jsonObj.optJSONObject(key)
            if (childObj != null) {
                if (cleanJsonObject(childObj, enableAdBlock, enableChronoSort)) {
                    modified = true
                }
            } else {
                val childArr = jsonObj.optJSONArray(key)
                if (childArr != null) {
                    for (j in 0 until childArr.length()) {
                        val item = childArr.optJSONObject(j)
                        if (item != null && cleanJsonObject(item, enableAdBlock, enableChronoSort)) {
                            modified = true
                        }
                    }
                }
            }
        }

        return modified
    }

    /**
     * 清洗和重排 entries 列表：
     * - 剔除广告与推广
     * - 提取每条推文真正的 Snowflake ID 与时间戳
     * - 按 Snowflake ID 降序（最新发布在最上）
     * - 重构 sortIndex，让客户端 SQLite 读出的顺序与时间线一致
     */
    private fun processEntries(
        entries: JSONArray,
        enableAdBlock: Boolean,
        enableChronoSort: Boolean
    ): JSONArray {
        val topCursors = mutableListOf<JSONObject>()
        val bottomCursors = mutableListOf<JSONObject>()
        val headerEntries = mutableListOf<JSONObject>()
        val validTweetEntries = mutableListOf<Pair<Long, JSONObject>>()

        var adCount = 0

        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue

            // 1. 检查是否为广告或干扰推荐模块
            if (enableAdBlock && isAdOrRecommendation(entry)) {
                adCount++
                continue
            }

            val entryId = entry.optString("entryId", "")
            val content = entry.optJSONObject("content")
            val entryType = content?.optString("entryType", "")

            // 2. 分离分页游标
            if (entryType == "TimelineTimelineCursor" || entryId.startsWith("cursor-")) {
                val cursorType = content?.optString("cursorType", "")
                if (cursorType.equals("Top", ignoreCase = true) || entryId.startsWith("cursor-top")) {
                    topCursors.add(entry)
                } else {
                    bottomCursors.add(entry)
                }
                continue
            }

            // 3. 提取推文 Snowflake ID
            val tweetId = extractTweetId(entry)
            cacheMediaFromEntry(entry)

            if (tweetId > 0L) {
                validTweetEntries.add(Pair(tweetId, entry))
            } else {
                headerEntries.add(entry)
            }
        }

        if (adCount > 0) {
            Logger.d("Filtered $adCount ads/recommendations from timeline batch.")
        }

        // 4. 瀑布流按 Snowflake ID 降序（最新发布在最上方）
        if (enableChronoSort && validTweetEntries.size > 1) {
            // 提取批次中所有的旧 sortIndex 序列，按降序排列
            val existingSortIndexes = validTweetEntries
                .mapNotNull {
                    val s = it.second.optString("sortIndex").ifBlank { it.second.optString("sort_index") }
                    s.toLongOrNull()
                }
                .sortedDescending()

            // 严格按推文真实发布时间倒序排列（Snowflake ID 越大，发布时间越新）
            validTweetEntries.sortByDescending { it.first }

            // 关键修复：将降序的 sortIndex 重新赋给排序后的推文
            // 解决 Twitter 本地 SQLite 使用 `ORDER BY sort_index DESC` 导致的算法顺序回弹
            for (idx in validTweetEntries.indices) {
                val targetSortIndex = if (idx < existingSortIndexes.size) {
                    existingSortIndexes[idx]
                } else {
                    validTweetEntries[idx].first
                }
                val entryObj = validTweetEntries[idx].second
                entryObj.put("sortIndex", targetSortIndex.toString())
                entryObj.put("sort_index", targetSortIndex.toString())
            }

            // 同步调整 topCursor 的 sortIndex 保持在所有推文上方
            if (existingSortIndexes.isNotEmpty()) {
                val maxSort = existingSortIndexes.first()
                topCursors.forEach {
                    it.put("sortIndex", (maxSort + 1000L).toString())
                    it.put("sort_index", (maxSort + 1000L).toString())
                }
                val minSort = existingSortIndexes.last()
                bottomCursors.forEach {
                    if (minSort > 1000L) {
                        it.put("sortIndex", (minSort - 1000L).toString())
                        it.put("sort_index", (minSort - 1000L).toString())
                    }
                }
            }
        }

        // 5. 重新组装 entries 数组：Top游标 -> 头部组件 -> 排序后的纯时间线推文 -> Bottom游标
        val result = JSONArray()
        topCursors.forEach { result.put(it) }
        headerEntries.forEach { result.put(it) }
        validTweetEntries.forEach { result.put(it.second) }
        bottomCursors.forEach { result.put(it) }

        return result
    }

    /**
     * 自动从推文条目中解析并缓存视频与图片资源
     */
    private fun cacheMediaFromEntry(entry: JSONObject) {
        try {
            val content = entry.optJSONObject("content") ?: return
            val itemContent = content.optJSONObject("itemContent") ?: return
            val tweetResult = itemContent.optJSONObject("tweet_results")?.optJSONObject("result") ?: return
            val legacy = tweetResult.optJSONObject("legacy") ?: tweetResult.optJSONObject("tweet")?.optJSONObject("legacy") ?: return
            val tweetId = extractTweetId(entry)

            val mediaList = MediaExtractor.extractMediaFromTweetJson(legacy, tweetId.toString())
            for (media in mediaList) {
                if (media.isVideo) {
                    com.polish.twitter.hooks.MediaDownloadHook.latestVideoUrl = media.url
                    com.polish.twitter.hooks.MediaDownloadHook.latestMediaUrl = media.url
                    break
                } else if (com.polish.twitter.hooks.MediaDownloadHook.latestMediaUrl == null) {
                    com.polish.twitter.hooks.MediaDownloadHook.latestMediaUrl = media.url
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 判断某个条目是否为广告、推广、推荐关注或话题推荐
     * 涵盖 X 所有版本的所有变体（包括普通推文、TweetWithVisibilityResults、推荐横栏、推广趋势等）
     */
    fun isAdOrRecommendation(entry: JSONObject): Boolean {
        val entryId = entry.optString("entryId", "")
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

        // 2. 深度 JSON 字符串特征匹配（无死角捕获任何嵌套深度的推广标识）
        val entryStr = entry.toString()
        if (entryStr.contains("\"promotedMetadata\"", ignoreCase = true) ||
            entryStr.contains("\"promoted_metadata\"", ignoreCase = true) ||
            entryStr.contains("\"advertiserResults\"", ignoreCase = true) ||
            entryStr.contains("\"advertiser_name\"", ignoreCase = true) ||
            entryStr.contains("\"advertiserName\"", ignoreCase = true) ||
            entryStr.contains("\"disclosure_type\"", ignoreCase = true) ||
            entryStr.contains("\"promotedPost\"", ignoreCase = true) ||
            entryStr.contains("\"promotedContent\"", ignoreCase = true) ||
            entryStr.contains("\"promotedTrend\"", ignoreCase = true) ||
            entryStr.contains("\"promotedTweet\"", ignoreCase = true) ||
            entryStr.contains("\"is_promoted\":true", ignoreCase = true) ||
            entryStr.contains("\"isPromoted\":true", ignoreCase = true) ||
            entryStr.contains("\"is_paid_promotion\":true", ignoreCase = true) ||
            entryStr.contains("\"source_type\":\"promoted\"", ignoreCase = true)) {
            return true
        }

        // 3. 结构化模块检查 (如推荐关注横栏、趋势轮播)
        val content = entry.optJSONObject("content") ?: return false
        val entryType = content.optString("entryType", "")
        if (entryType == "TimelineTimelineModule") {
            val clientEventInfo = content.optJSONObject("clientEventInfo")
            val component = clientEventInfo?.optString("component", "") ?: ""
            if (component.contains("suggest", ignoreCase = true) ||
                component.contains("who_to_follow", ignoreCase = true) ||
                component.contains("whoToFollow", ignoreCase = true) ||
                component.contains("trend", ignoreCase = true) ||
                component.contains("topic", ignoreCase = true)) {
                return true
            }
        }

        return false
    }

    /**
     * 全面解析推文的 64 位 Snowflake ID 或时间戳
     * 适配普通 Tweet、TweetWithVisibilityResults、ModuleItem 等各种结构
     */
    fun extractTweetId(entry: JSONObject): Long {
        val entryId = entry.optString("entryId", "")

        // 1. 从 entryId 中正则提取 16~20 位的 Snowflake 数字（覆盖 tweet-xxx, sq-I-t-xxx, conversation-xxx 等所有变体）
        SNOWFLAKE_REGEX.find(entryId)?.let { match ->
            val id = match.value.toLongOrNull()
            if (id != null && id > 1000000000000000L) {
                return id
            }
        }

        val content = entry.optJSONObject("content") ?: return 0L
        val itemContent = content.optJSONObject("itemContent")

        // 2. 从 itemContent.tweet_results 中提取
        val tweetResult = itemContent?.optJSONObject("tweet_results")?.optJSONObject("result")
        if (tweetResult != null) {
            // 直接 rest_id
            tweetResult.optString("rest_id").toLongOrNull()?.let { if (it > 0) return it }

            // TweetWithVisibilityResults 嵌套结构
            val innerTweet = tweetResult.optJSONObject("tweet")
            innerTweet?.optString("rest_id")?.toLongOrNull()?.let { if (it > 0) return it }

            // legacy.id_str
            tweetResult.optJSONObject("legacy")?.optString("id_str")?.toLongOrNull()?.let { if (it > 0) return it }
            innerTweet?.optJSONObject("legacy")?.optString("id_str")?.toLongOrNull()?.let { if (it > 0) return it }

            // created_at 时间字符串降级推算
            val createdAt = tweetResult.optJSONObject("legacy")?.optString("created_at")
                ?: innerTweet?.optJSONObject("legacy")?.optString("created_at")
            if (!createdAt.isNullOrBlank()) {
                try {
                    val date = TWITTER_DATE_FORMAT.parse(createdAt)
                    if (date != null) {
                        val epochMs = date.time
                        // 将 epochMs 反算为等价 Snowflake ID: (timestamp - TWITTER_EPOCH) << 22
                        val pseudoSnowflake = (epochMs - 1288834974657L) shl 22
                        if (pseudoSnowflake > 0) return pseudoSnowflake
                    }
                } catch (_: Throwable) {}
            }
        }

        // 3. 检查是否为 TimelineTimelineModule（会话推文模块）
        val moduleItems = content.optJSONArray("items") ?: content.optJSONArray("moduleItems")
        if (moduleItems != null && moduleItems.length() > 0) {
            for (m in 0 until moduleItems.length()) {
                val itemObj = moduleItems.optJSONObject(m) ?: continue
                val innerEntry = itemObj.optJSONObject("item") ?: itemObj
                val subId = extractTweetId(innerEntry)
                if (subId > 0L) return subId
            }
        }

        return 0L
    }
}
