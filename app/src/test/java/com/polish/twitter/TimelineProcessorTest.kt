package com.polish.twitter

import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.processor.TimelineProcessor
import com.polish.twitter.utils.SnowflakeUtils
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineProcessorTest {

    @Test
    fun testSnowflakeTimestampCalculation() {
        val olderId = 1700000000000000000L
        val newerId = 1800000000000000000L

        val olderTime = SnowflakeUtils.getTimestamp(olderId)
        val newerTime = SnowflakeUtils.getTimestamp(newerId)

        assertTrue("Newer Tweet ID must yield a later timestamp", newerTime > olderTime)
        assertTrue("Timestamp should be after Twitter epoch", olderTime > SnowflakeUtils.TWITTER_EPOCH)
    }

    @Test
    fun testAdFilteringAndChronologicalOrdering() {
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

        // 预期保留条目：cursor-top, tweet-1800000000000000000 (新), tweet-1700000000000000000 (旧), cursor-bottom
        assertEquals(4, entries.length())

        assertEquals("cursor-top-1", entries.getJSONObject(0).getString("entryId"))
        assertEquals("tweet-1800000000000000000", entries.getJSONObject(1).getString("entryId")) // 最新推文排在前面！
        assertEquals("tweet-1700000000000000000", entries.getJSONObject(2).getString("entryId")) // 较旧推文排在后面
        assertEquals("cursor-bottom-2", entries.getJSONObject(3).getString("entryId"))
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
    fun testSortIndexSyncingAndEntryIdVariants() {
        val rawJson = """
        {
          "instructions": [
            {
              "type": "TimelineAddEntries",
              "entries": [
                {
                  "entryId": "cursor-top",
                  "sortIndex": "9999999999999999999",
                  "content": { "entryType": "TimelineTimelineCursor", "cursorType": "Top" }
                },
                {
                  "entryId": "home-conversation-1700000000000000000",
                  "sortIndex": "5000",
                  "content": {
                    "itemContent": {
                      "tweet_results": {
                        "result": {
                          "__typename": "TweetWithVisibilityResults",
                          "tweet": { "rest_id": "1700000000000000000" }
                        }
                      }
                    }
                  }
                },
                {
                  "entryId": "sq-I-t-1900000000000000000",
                  "sortIndex": "4000",
                  "content": {
                    "itemContent": {
                      "tweet_results": { "result": { "rest_id": "1900000000000000000" } }
                    }
                  }
                },
                {
                  "entryId": "cursor-bottom",
                  "sortIndex": "1000",
                  "content": { "entryType": "TimelineTimelineCursor", "cursorType": "Bottom" }
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val processed = TimelineProcessor.processTimelineResponse(rawJson)
        val root = JSONObject(processed)
        val entries = root.getJSONArray("instructions").getJSONObject(0).getJSONArray("entries")

        assertEquals(4, entries.length())

        val tweet1 = entries.getJSONObject(1)
        val tweet2 = entries.getJSONObject(2)

        // 验证最新发布的推文（1900...）排在前面
        assertEquals("sq-I-t-1900000000000000000", tweet1.getString("entryId"))
        assertEquals("home-conversation-1700000000000000000", tweet2.getString("entryId"))

        // 验证 sortIndex 也完成了降序重构同步（第一条分配较高的 5000，第二条分配较低的 4000）
        assertEquals("5000", tweet1.getString("sortIndex"))
        assertEquals("4000", tweet2.getString("sortIndex"))
    }
}
