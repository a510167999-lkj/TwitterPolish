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

    @Test
    fun x1233OperationNamesAreRecognized() {
        // WHY: 12.33 用自家 Apollo 操作名。名单只有 Web 旧名时，关注流、详情页、书签、搜索的响应
        // 全部不经处理——这是 1.0.7 在 12.33 上去广告失效的另一个原因。
        for (op in listOf("HomeTimelineLatest", "ConversationTimeline", "BookmarksTimelineQuery", "SearchTimelineQuery")) {
            assertTrue(op, Constants.isTimelineApiUrl("https://api.x.com/graphql/abc/$op?variables=%7B%7D"))
        }
        assertFalse(
            "推荐关注页本身不能过滤，否则页面被清空",
            Constants.isTimelineApiUrl("https://api.x.com/graphql/abc/ConnectTabTimelineQuery")
        )
    }
}
