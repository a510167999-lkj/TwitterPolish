package com.polish.twitter.core

object Constants {
    const val TAG = "TwitterPolish"
    const val TARGET_PACKAGE = "com.twitter.android"

    // SharedPreferences 缓存配置
    const val PREFS_NAME = "twitter_polish_prefs"
    const val KEY_CACHED_VERSION_CODE = "cached_version_code"
    const val KEY_DEXKIT_CACHE = "dexkit_cache_json"

    // 功能开关配置
    const val PREF_ENABLE_AD_BLOCK = "pref_enable_ad_block"
    const val PREF_ENABLE_CHRONO_SORT = "pref_enable_chrono_sort"
    const val PREF_ENABLE_MEDIA_DOWNLOAD = "pref_enable_media_download"
    const val PREF_ENABLE_DEFAULT_FOLLOWING = "pref_enable_default_following"

    // GraphQL 端点标识
    val TIMELINE_ENDPOINTS = listOf(
        "HomeTimeline",
        "HomeLatestTimeline",
        "ListLatestTweetsTimeline",
        "UserTweets",
        "TweetDetail",
        "Bookmarks"
    )

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
