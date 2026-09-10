package com.polish.twitter

import com.polish.twitter.core.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineApiUrlTest {

    @Test
    fun matcherDoesNotSwallowUnrelatedGraphql() {
        // WHY: 第一轮审计留下 contains("graphql")，NetworkTimelineHook 会对 Viewer /
        // CreateTweet 等全部 GraphQL 执行 body.bytes()。OkHttp 的 bytes() 关闭流且不缓存，
        // 未改写时若 return null，客户端按 Content-Length 空等，下拉刷新就会卡住。
        assertFalse(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/AbCdEf/Viewer")
        )
        assertFalse(
            Constants.isTimelineApiUrl("https://api.x.com/graphql/xyz/CreateTweet")
        )
        assertFalse(
            Constants.isTimelineApiUrl("https://api.twitter.com/1.1/statuses/update.json")
        )
        assertFalse(
            Constants.isTimelineApiUrl("https://video.twimg.com/ext_tw_video/1/pu/pl/master.m3u8")
        )
        assertFalse(Constants.isTimelineApiUrl(""))
        assertFalse(
            "操作名必须是路径最后一段，不能用 contains 误伤其它 mutation",
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/DeleteUserTweets")
        )
    }

    @Test
    fun matcherHitsTimelineAndTweetDetailOperations() {
        assertTrue(
            Constants.isTimelineApiUrl(
                "https://x.com/i/api/graphql/hash/HomeLatestTimeline?variables=%7B%7D"
            )
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/HomeTimeline")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/UserTweets")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/UserTweetsAndReplies")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/TweetDetail")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/TweetResultByRestId")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/SearchTimeline")
        )
        assertTrue(
            Constants.isTimelineApiUrl("https://x.com/i/api/graphql/hash/Bookmarks")
        )
    }
}
