package com.polish.twitter.core

object Constants {
    const val TAG = "TwitterPolish"
    const val TARGET_PACKAGE = "com.twitter.android"

    // SharedPreferences 缓存配置
    const val PREFS_NAME = "twitter_polish_prefs"
    const val KEY_DEXKIT_CACHE = "dexkit_cache_json"

    // 功能开关配置
    const val PREF_ENABLE_AD_BLOCK = "pref_enable_ad_block"
    const val PREF_ENABLE_MEDIA_DOWNLOAD = "pref_enable_media_download"
    const val PREF_ENABLE_DEFAULT_FOLLOWING = "pref_enable_default_following"
    const val PREF_SHOW_FLOATING_BALL = "show_floating_ball"
    const val PREF_BYPASS_SENSITIVE = "pref_bypass_sensitive"

    // GraphQL 操作名：只匹配时间线/推文详情，禁止 blanket "graphql"。
    // 命中过宽会让 NetworkTimelineHook 对 Viewer/CreateTweet 等也 bytes()，
    // 未重建 body 时客户端按 Content-Length 空等，下拉刷新会一直转。
    // X Android 12.33 起用自家 Apollo 操作名（与 Web 版不同）；旧名保留给旧版本。
    // 不含 ConnectTabTimelineQuery：那是「推荐关注」页本身，过滤推荐模块会把页面清空。
    val TIMELINE_ENDPOINTS = listOf(
        // X Android 12.33+
        "HomeTimeline",
        "HomeTimelineLatest",
        "HomeTimelineSubscriptions",
        "ConversationTimeline",
        "BookmarksTimelineQuery",
        "BookmarkCollectionTimelineQuery",
        "BookmarksSearchTimelineQuery",
        "SearchTimelineQuery",
        "TimelineByIdQuery",
        "CommunityDetailTimelineQuery",
        "CommunitiesTimelineQuery",
        "ImmersiveViewerExploreMixerTimeline",
        "ImmersiveViewerProfileMixerTimeline",
        "UserProfileOriginalsTimelineQuery",
        "UserProfileRepliesTimelineQuery",
        "UserProfileNewRepliesTimelineQuery",
        "UserProfileRepostsTimelineQuery",
        "UserProfileMediaTimelineQuery",
        "UserProfilePhotosTimelineQuery",
        "UserProfileVideosTimelineQuery",
        "UserProfileHighlightsTimelineQuery",
        "UserProfileArticlesTimelineQuery",
        // 旧版本 / Web 命名
        "HomeLatestTimeline",
        "ListLatestTweetsTimeline",
        "UserTweets",
        "UserTweetsAndReplies",
        "UserMedia",
        "TweetDetail",
        "TweetResultByRestId",
        "TweetResultsByRestIds",
        "Bookmarks",
        "SearchTimeline"
    )

    fun isTimelineApiUrl(url: String): Boolean {
        if (url.isBlank()) return false
        // GraphQL 路径最后一段才是操作名。用 contains 会误伤 DeleteUserTweets 等。
        val operation = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (operation.isBlank()) return false
        return TIMELINE_ENDPOINTS.any { it.equals(operation, ignoreCase = true) }
    }

    // Twitter 广告特征关键词
    val AD_ENTRY_PREFIXES = listOf(
        "promoted-tweet-",
        "promoted-trend-",
        "who-to-follow-",
        "suggest_who_to_follow",
        "topic-",
        "connect-",
        "super-follow-",
        "ad-"
    )
}
