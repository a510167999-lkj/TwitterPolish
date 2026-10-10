package com.polish.twitter

import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.processor.TimelineProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineProcessorTest {

    @Test
    fun adsAreRemovedAndServerOrderIsKept() {
        val rawJson = """
        {
          "data": {
            "home": {
              "home_timeline_urt": {
                "instructions": [
                  {
                    "type": "TimelineAddEntries",
                    "entries": [
                      {
                        "entryId": "cursor-top-1",
                        "content": {
                          "entryType": "TimelineTimelineCursor",
                          "cursorType": "Top"
                        }
                      },
                      {
                        "entryId": "tweet-1700000000000000000",
                        "content": {
                          "entryType": "TimelineTimelineItem",
                          "itemContent": {
                            "itemType": "TimelineTweet",
                            "tweet_results": {
                              "result": {
                                "rest_id": "1700000000000000000"
                              }
                            }
                          }
                        }
                      },
                      {
                        "entryId": "promoted-tweet-99999",
                        "content": {
                          "entryType": "TimelineTimelineItem",
                          "itemContent": {
                            "promotedMetadata": {
                              "impressionId": "abc"
                            }
                          }
                        }
                      },
                      {
                        "entryId": "who-to-follow-123",
                        "content": {
                          "entryType": "TimelineTimelineModule"
                        }
                      },
                      {
                        "entryId": "tweet-1800000000000000000",
                        "content": {
                          "entryType": "TimelineTimelineItem",
                          "itemContent": {
                            "itemType": "TimelineTweet",
                            "tweet_results": {
                              "result": {
                                "rest_id": "1800000000000000000"
                              }
                            }
                          }
                        }
                      },
                      {
                        "entryId": "cursor-bottom-2",
                        "content": {
                          "entryType": "TimelineTimelineCursor",
                          "cursorType": "Bottom"
                        }
                      }
                    ]
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()

        val processedJson = TimelineProcessor.processTimelineResponse(rawJson)
        val root = JSONObject(processedJson)
        val instructions = root.getJSONObject("data")
            .getJSONObject("home")
            .getJSONObject("home_timeline_urt")
            .getJSONArray("instructions")

        val addEntries = instructions.getJSONObject(0)
        val entries = addEntries.getJSONArray("entries")

        // WHY: 只删广告与推荐，不动顺序。旧版按 ID 重排会把详情页回复顶到主推文上面。
        assertEquals(
            listOf("cursor-top-1", "tweet-1700000000000000000", "tweet-1800000000000000000", "cursor-bottom-2"),
            entryIds(entries)
        )
    }

    @Test
    fun testMediaExtractorOriginalImageUrl() {
        val sampleUrl1 = "https://pbs.twimg.com/media/GMcDe6qXoAA-7gO?format=jpg&name=large"
        val origUrl1 = MediaExtractor.toOriginalImageUrl(sampleUrl1)
        assertEquals("https://pbs.twimg.com/media/GMcDe6qXoAA-7gO?format=jpg&name=orig", origUrl1)

        val sampleUrl2 = "https://pbs.twimg.com/media/GMcDe6qXoAA-7gO.jpg"
        val origUrl2 = MediaExtractor.toOriginalImageUrl(sampleUrl2)
        assertEquals("https://pbs.twimg.com/media/GMcDe6qXoAA-7gO.jpg?name=orig", origUrl2)
    }

    @Test
    fun testMediaExtractorHighestBitrateVideo() {
        val tweetJson = JSONObject("""
        {
          "rest_id": "1800123456789",
          "extended_entities": {
            "media": [
              {
                "type": "video",
                "video_info": {
                  "variants": [
                    {
                      "bitrate": 832000,
                      "content_type": "video/mp4",
                      "url": "https://video.twimg.com/vid/720x1280/low.mp4"
                    },
                    {
                      "bitrate": 2176000,
                      "content_type": "video/mp4",
                      "url": "https://video.twimg.com/vid/1080x1920/high.mp4"
                    },
                    {
                      "content_type": "application/x-mpegURL",
                      "url": "https://video.twimg.com/vid/playlist.m3u8"
                    }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent())

        val mediaList = MediaExtractor.extractMediaFromTweetJson(tweetJson)
        assertEquals(1, mediaList.size)

        val video = mediaList[0]
        assertTrue(video.isVideo)
        assertEquals(2176000L, video.bitrate)
        assertEquals("https://video.twimg.com/vid/1080x1920/high.mp4", video.url)
        assertEquals("twitter_1800123456789_video_1.mp4", video.fileName)
    }

    @Test
    fun tweetDetailConversationOrderIsUntouched() {
        // WHY: 详情页顺序是“上文 → 主推文 → 回复”，回复 ID 一定比主推文大。
        // 任何按 ID 的重排都会把回复顶到主推文上方、上文沉到底部，会话结构就坏了。
        val raw = """{"data":{"threaded_conversation_with_injections_v2":{"instructions":[{"type":"TimelineAddEntries","entries":[""" +
            """{"entryId":"tweet-1700000000000000000","sortIndex":"9","content":{"entryType":"TimelineTimelineItem"}},""" +
            """{"entryId":"tweet-1750000000000000000","sortIndex":"8","content":{"entryType":"TimelineTimelineItem"}},""" +
            """{"entryId":"promoted-tweet-1","content":{"entryType":"TimelineTimelineItem"}},""" +
            """{"entryId":"conversationthread-1790000000000000000","sortIndex":"7","content":{"entryType":"TimelineTimelineModule"}},""" +
            """{"entryId":"conversationthread-1780000000000000000","sortIndex":"6","content":{"entryType":"TimelineTimelineModule"}}""" +
            """]}]}}}"""
        val entries = JSONObject(TimelineProcessor.processTimelineResponse(raw))
            .getJSONObject("data").getJSONObject("threaded_conversation_with_injections_v2")
            .getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")

        assertEquals(
            listOf(
                "tweet-1700000000000000000",
                "tweet-1750000000000000000",
                "conversationthread-1790000000000000000",
                "conversationthread-1780000000000000000"
            ),
            entryIds(entries)
        )
        assertEquals("sortIndex 也不能被改写", "9", entries.getJSONObject(0).getString("sortIndex"))
    }

    @Test
    fun nullPromotedPlaceholderDoesNotDropOrganicTweet() {
        // WHY: 普通推文也可能带推广字段的空占位。只看 key 名会把正常推文整条删掉。
        val raw = """{"instructions":[{"type":"TimelineAddEntries","entries":[""" +
            """{"entryId":"tweet-1700000000000000000","content":{"entryType":"TimelineTimelineItem","itemContent":""" +
            """{"promotedMetadata":null,"tweet_results":{"result":{"rest_id":"1700000000000000000","legacy":{"is_promoted":false}}}}}},""" +
            """{"entryId":"tweet-1800000000000000000","content":{"entryType":"TimelineTimelineItem","itemContent":""" +
            """{"promotedMetadata":{"impressionId":"x"},"tweet_results":{"result":{"rest_id":"1800000000000000000"}}}}}""" +
            """]}]}"""
        val entries = JSONObject(TimelineProcessor.processTimelineResponse(raw))
            .getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")

        assertEquals("只删带真实推广元数据的那条", listOf("tweet-1700000000000000000"), entryIds(entries))
    }

    @Test
    fun consecutivePinnedAdsAreAllRemoved() {
        // 回归 P1-3：删除元素时索引后移导致“跳过下一个”，连续广告必须全部被移除
        val rawJson = """
        {
          "instructions": [
            { "type": "TimelinePinEntry", "entry": { "entryId": "promoted-tweet-1", "content": {} } },
            { "type": "TimelinePinEntry", "entry": { "entryId": "promoted-tweet-2", "content": {} } },
            {
              "type": "TimelineAddEntries",
              "entries": [
                {
                  "entryId": "tweet-1700000000000000000",
                  "content": {
                    "entryType": "TimelineTimelineItem",
                    "itemContent": {
                      "itemType": "TimelineTweet",
                      "tweet_results": { "result": { "rest_id": "1700000000000000000" } }
                    }
                  }
                }
              ]
            },
            { "type": "TimelinePinEntry", "entry": { "entryId": "promoted-tweet-3", "content": {} } }
          ]
        }
        """.trimIndent()

        val processed = TimelineProcessor.processTimelineResponse(rawJson)
        val instructions = JSONObject(processed).getJSONArray("instructions")
        assertEquals(
            "三条连续的 promoted PinEntry 应全部删除，只保留真实推文 instruction",
            1,
            instructions.length()
        )
        assertEquals("TimelineAddEntries", instructions.getJSONObject(0).getString("type"))
    }

    @Test
    fun noRewriteWhenNothingToStrip() {
        // WHY: 网络层会 bytes() 耗尽响应体。无广告时必须返回同一字符串实例，
        // 这样拦截器走 restoreOriginal（原字节重建），而不是 JSONObject.toString() 改 key 序。
        // 若这里仍改写，改写失败就会把已消耗的 body 交回客户端，下拉刷新一直转。
        val raw = """{"instructions":[{"type":"TimelineAddEntries","entries":[""" +
            """{"entryId":"tweet-1700000000000000000","content":{"entryType":"TimelineTimelineItem",""" +
            """"itemContent":{"tweet_results":{"result":{"rest_id":"1700000000000000000"}}}}},""" +
            """{"entryId":"cursor-bottom-1","content":{"entryType":"TimelineTimelineCursor","cursorType":"Bottom"}}]}]}"""
        val out = TimelineProcessor.processTimelineResponse(raw, enableAdBlock = true)
        assertTrue(
            "无广告时应返回原字符串实例，避免无意义改写",
            out === raw
        )
    }

    @Test
    fun testBypassSensitiveContentWarning() {
        val raw = """
        {
          "instructions": [
            {
              "type": "TimelineAddEntries",
              "entries": [
                {
                  "entryId": "tweet-1800000000000000000",
                  "content": {
                    "entryType": "TimelineTimelineItem",
                    "itemContent": {
                      "itemType": "TimelineTweet",
                      "tweet_results": {
                        "result": {
                          "rest_id": "1800000000000000000",
                          "tweetInterstitial": {
                            "interstitial_type": "SensitiveMediaWarning"
                          },
                          "sensitive_media_warning": {
                            "adult_content": true
                          },
                          "ext_sensitive_media_warning": {
                            "adult_content": true
                          },
                          "legacy": {
                            "possibly_sensitive": true,
                            "possibly_sensitive_editable": true
                          }
                        }
                      }
                    }
                  }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val processed = TimelineProcessor.processTimelineResponse(
            raw,
            enableAdBlock = false,
            enableBypassSensitive = true
        )

        val root = JSONObject(processed)
        val tweetResult = root.getJSONArray("instructions")
            .getJSONObject(0)
            .getJSONArray("entries")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONObject("itemContent")
            .getJSONObject("tweet_results")
            .getJSONObject("result")

        // WHY: 媒体警告要去掉，但 tweetInterstitial 还承担不可用/年龄等插屏，不能整棵删掉。
        assertTrue("tweetInterstitial 应保留", tweetResult.has("tweetInterstitial"))
        assertFalse("sensitive_media_warning 应被移除", tweetResult.has("sensitive_media_warning"))
        assertFalse("ext_sensitive_media_warning 应被移除", tweetResult.has("ext_sensitive_media_warning"))

        val legacy = tweetResult.getJSONObject("legacy")
        assertFalse("possibly_sensitive 应被置为 false", legacy.getBoolean("possibly_sensitive"))
        assertFalse("possibly_sensitive_editable 应被置为 false", legacy.getBoolean("possibly_sensitive_editable"))
    }

    @Test
    fun bypassSensitiveDoesNotRewriteWhenNoFlagsPresent() {
        // WHY: 开关打开时若仍 JSONObject.toString()，无标志的时间线也会被改 key 序。
        val raw = """{"instructions":[{"type":"TimelineAddEntries","entries":[{"entryId":"tweet-1700000000000000000","content":{"entryType":"TimelineTimelineItem","itemContent":{"tweet_results":{"result":{"rest_id":"1700000000000000000","legacy":{"full_text":"hello"}}}}}}]}]}"""
        val out = TimelineProcessor.processTimelineResponse(
            raw,
            enableAdBlock = false,
            enableBypassSensitive = true
        )
        assertTrue(out === raw)
    }

    @Test
    fun bypassSensitiveKeepsNonMediaInterstitial() {
        val raw = """{"tweetInterstitial":{"interstitial_type":"Unavailable"},"legacy":{"possibly_sensitive":false}}"""
        val out = TimelineProcessor.processTimelineResponse(
            raw,
            enableAdBlock = false,
            enableBypassSensitive = true
        )
        assertTrue(out === raw)
    }

    private fun entryIds(entries: JSONArray): List<String> =
        (0 until entries.length()).map { entries.getJSONObject(it).getString("entryId") }

    // ---- X Android 12.33 的 Apollo 响应：下划线命名，结构取自真机抓包（内容为虚构） ----

    private fun x1233Tweet(id: String, extraContent: String = "", resultExtra: String = "") =
        """{"entry_id":"tweet-$id","sort_index":"$id","content":{"__typename":"TimelineTimelineItem",""" +
            """"client_event_info":{"component":"for_you_in_network"},"content":{"__typename":"TimelineTweet",""" +
            """"tweet_display_type":"Tweet",$extraContent"tweet_results":{"rest_id":"$id","result":{"__typename":"Tweet"$resultExtra}}}}}"""

    private fun x1233Home(vararg entries: String) =
        """{"data":{"timeline_response":{"id":"x","timeline":{"instructions":[{"__typename":"TimelineClearCache"},""" +
            """{"__typename":"TimelineAddEntries","entries":[${entries.joinToString(",")}]}]}}}}"""

    private fun x1233Entries(json: String): JSONArray =
        JSONObject(json).getJSONObject("data").getJSONObject("timeline_response").getJSONObject("timeline")
            .getJSONArray("instructions").getJSONObject(1).getJSONArray("entries")

    private fun x1233Ids(entries: JSONArray) =
        (0 until entries.length()).map { entries.getJSONObject(it).getString("entry_id") }

    @Test
    fun x1233PromotedTweetIsRemoved() {
        // WHY: 12.33 的推广推文 entry_id 与普通推文一样是 tweet-<id>，前缀规则认不出；
        // 只能靠 content.content 下有值的 promoted_metadata。前后两条普通推文必须原序保留。
        val raw = x1233Home(
            x1233Tweet("1800000000000000001"),
            x1233Tweet(
                "1800000000000000002",
                extraContent = """"promoted_metadata":{"advertiser_results":{"rest_id":"9"},"impression_id":"abc"},"""
            ),
            x1233Tweet("1800000000000000003")
        )
        assertEquals(
            listOf("tweet-1800000000000000001", "tweet-1800000000000000003"),
            x1233Ids(x1233Entries(TimelineProcessor.processTimelineResponse(raw)))
        )
    }

    @Test
    fun x1233PromotedDisplayTypeIsRemoved() {
        // WHY: 推广推文的展示类型是 *PromotedTweet；即使 promoted_metadata 被裁剪，也要能认出来。
        val promoted = x1233Tweet("1800000000000000002")
            .replace(""""tweet_display_type":"Tweet"""", """"tweet_display_type":"CompactPromotedTweet"""")
        val raw = x1233Home(x1233Tweet("1800000000000000001"), promoted)
        assertEquals(
            listOf("tweet-1800000000000000001"),
            x1233Ids(x1233Entries(TimelineProcessor.processTimelineResponse(raw)))
        )
    }

    @Test
    fun authorPaidPromotionDisclosureIsNotAnAd() {
        // WHY: content_disclosure.advertising_disclosure.is_paid_promotion 是作者自己声明的付费推广，
        // 是关注对象的正常推文，不是 X 投放的广告。真机抓包里就有一条，旧规则把它误删了。
        val raw = x1233Home(
            x1233Tweet(
                "1800000000000000001",
                resultExtra = ""","content_disclosure":{"advertising_disclosure":{"is_paid_promotion":true}}"""
            )
        )
        val out = TimelineProcessor.processTimelineResponse(raw)
        assertTrue("没有广告时应原样返回", out === raw)
    }

    @Test
    fun x1233WhoToFollowModuleIsRemoved() {
        // WHY: 12.33 的模块类型在 __typename、事件信息在 client_event_info；只认驼峰字段会漏掉推荐关注横栏。
        val module = """{"entry_id":"module-1","sort_index":"1","content":{"__typename":"TimelineTimelineModule",""" +
            """"client_event_info":{"component":"suggest_who_to_follow"},"items":[]}}"""
        val raw = x1233Home(x1233Tweet("1800000000000000001"), module)
        assertEquals(
            listOf("tweet-1800000000000000001"),
            x1233Ids(x1233Entries(TimelineProcessor.processTimelineResponse(raw)))
        )
    }
}
