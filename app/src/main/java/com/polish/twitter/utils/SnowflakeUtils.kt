package com.polish.twitter.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SnowflakeUtils {
    // Twitter Snowflake 的基准纪元（2010-11-04 01:42:54.657 UTC）
    const val TWITTER_EPOCH = 1288834974657L

    /**
     * 从 64 位推文 Snowflake ID 中解析发布时间戳（毫秒）
     * 算法：高 41 位表示距基准纪元的毫秒偏移
     */
    fun getTimestamp(tweetId: Long): Long {
        if (tweetId <= 0) return 0L
        return (tweetId ushr 22) + TWITTER_EPOCH
    }

    /**
     * 从字符串形式的推文 ID 中解析时间戳
     */
    fun getTimestamp(tweetIdStr: String?): Long {
        if (tweetIdStr.isNullOrBlank()) return 0L
        val id = tweetIdStr.toLongOrNull() ?: return 0L
        return getTimestamp(id)
    }

    /**
     * 将推文 ID 转为可读的时间格式，用于日志或调试
     */
    fun formatTweetTime(tweetId: Long): String {
        val timestamp = getTimestamp(tweetId)
        if (timestamp <= TWITTER_EPOCH) return "Unknown"
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
