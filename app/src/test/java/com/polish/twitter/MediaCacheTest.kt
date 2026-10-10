package com.polish.twitter

import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaCacheTest {

    @Before
    fun reset() {
        MediaCache.clear()
    }

    @Test
    fun dashPlaybackUrlMustNotBeTreatedAsDownloadableFile() {
        val dash = "https://video.twimg.com/amplify_video/2094335606436622337/vid/avc1/0/0/1920x1078/FIqZc-yi7AX39gkW.mp4"
        val audio = "https://video.twimg.com/amplify_video/2094335606436622337/aud/mp4a/0/0/32000/4bhahiDjjwKYxtoo.mp4"
        val progressive = "https://video.twimg.com/amplify_video/2094335606436622337/vid/avc1/1920x1078/FIqZc-yi7AX39gkW.mp4?tag=21"

        assertTrue(
            "Player-captured /0/0/ path is a DASH init (~900B ftyp-dash), not a complete video",
            MediaExtractor.isDashSegmentUrl(dash)
        )
        assertTrue("Audio-only /aud/ tracks must not be saved as the tweet video", MediaExtractor.isDashSegmentUrl(audio))
        assertFalse("GraphQL variant without /0/0/ is the file we actually want", MediaExtractor.isDashSegmentUrl(progressive))
        assertTrue(MediaExtractor.isProgressiveMp4Url(progressive))
    }

    @Test
    fun dashFtypHeaderIsRejectedSoTinyInitSegmentsAreNotSaved() {
        val dashHeader = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x69, 0x73, 0x6f, 0x35, // iso5
            0x00, 0x00, 0x00, 0x01,
            0x69, 0x73, 0x6f, 0x36, // iso6
            0x63, 0x6d, 0x66, 0x32, // cmf2
            0x64, 0x61, 0x73, 0x68  // dash
        )
        val progressiveHeader = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x69, 0x73, 0x6f, 0x6d, // isom
            0x00, 0x00, 0x00, 0x00,
            0x69, 0x73, 0x6f, 0x6d,
            0x6d, 0x70, 0x34, 0x32  // mp42
        )
        assertFalse(
            "Saving a cmf2/dash init produces an unplayable 900-byte 'mp4'",
            MediaExtractor.isPlayableMp4Header(dashHeader)
        )
        assertTrue(MediaExtractor.isPlayableMp4Header(progressiveHeader))
    }

    @Test
    fun playbackDashUrlResolvesToSameTweetProgressiveVariant() {
        val json = """
        {
          "data": {
            "tweetResult": {
              "result": {
                "__typename": "Tweet",
                "rest_id": "2056623384482783650",
                "legacy": {
                  "full_text": "clip",
                  "extended_entities": {
                    "media": [
                      {
                        "id_str": "2094335606436622337",
                        "type": "video",
                        "video_info": {
                          "variants": [
                            {
                              "bitrate": 256000,
                              "content_type": "video/mp4",
                              "url": "https://video.twimg.com/amplify_video/2094335606436622337/vid/avc1/0/0/480x270/seg.mp4"
                            },
                            {
                              "bitrate": 2176000,
                              "content_type": "video/mp4",
                              "url": "https://video.twimg.com/amplify_video/2094335606436622337/vid/1280x720/full.mp4?tag=21"
                            },
                            {
                              "content_type": "application/x-mpegURL",
                              "url": "https://video.twimg.com/amplify_video/2094335606436622337/pl/playlist.m3u8"
                            }
                          ]
                        }
                      }
                    ]
                  }
                }
              }
            }
          }
        }
        """.trimIndent()

        MediaCache.ingestJson(json)
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/amplify_video/2094335606436622337/vid/avc1/0/0/1920x1078/FIqZc-yi7AX39gkW.mp4"
        )

        val resolved = MediaCache.resolve()
        assertEquals(1, resolved.size)
        assertTrue(resolved[0].isVideo)
        assertEquals(
            "Must download HLS master (1080p ladder), not the 720p progressive variant",
            "https://video.twimg.com/amplify_video/2094335606436622337/pl/playlist.m3u8",
            resolved[0].url
        )
        assertTrue(MediaExtractor.isHlsPlaylistUrl(resolved[0].url))
    }

    @Test
    fun playerLockIgnoresPrefetchAndPreviousSegments() {
        val json = """
        {
          "tweets": [
            {
              "__typename": "Tweet",
              "rest_id": "1111111111111111111",
              "legacy": {
                "full_text": "a",
                "extended_entities": {
                  "media": [{
                    "id_str": "1000000000000000001",
                    "type": "video",
                    "video_info": {
                      "variants": [
                        {
                          "bitrate": 2176000,
                          "content_type": "video/mp4",
                          "url": "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/vid/720x1280/a.mp4?tag=12"
                        },
                        {
                          "content_type": "application/x-mpegURL",
                          "url": "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/pl/masterA.m3u8"
                        }
                      ]
                    }
                  }]
                }
              }
            },
            {
              "__typename": "Tweet",
              "rest_id": "2222222222222222222",
              "legacy": {
                "full_text": "b",
                "extended_entities": {
                  "media": [{
                    "id_str": "1000000000000000002",
                    "type": "video",
                    "video_info": {
                      "variants": [
                        {
                          "bitrate": 256000,
                          "content_type": "video/mp4",
                          "url": "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/vid/480x270/b.mp4?tag=12"
                        },
                        {
                          "content_type": "application/x-mpegURL",
                          "url": "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/masterB.m3u8"
                        }
                      ]
                    }
                  }]
                }
              }
            }
          ]
        }
        """.trimIndent()
        MediaCache.ingestJson(json)

        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/pl/avc1/720x1280/a.m3u8",
            fromPlayer = true
        )
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/masterB.m3u8",
            fromPlayer = false
        )
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/vid/avc1/0/0/720x1280/seg.m4s",
            fromPlayer = false
        )
        assertTrue(
            "Prefetch of the next video must not steal the playing id",
            MediaCache.bestVideoUrl()!!.contains("masterA.m3u8")
        )

        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/avc1/1920x1080/b.m3u8",
            fromPlayer = true
        )
        assertTrue(
            "After the player actually switches, save must follow the new video",
            MediaCache.bestVideoUrl()!!.contains("masterB.m3u8")
        )
    }

    @Test
    fun resolvePutsThePlayingVideoFirstWhenATweetHasSeveral() {
        val json = """
        {
          "__typename": "Tweet",
          "rest_id": "3333333333333333333",
          "legacy": {
            "full_text": "two clips",
            "extended_entities": {
              "media": [
                {
                  "id_str": "1000000000000000101",
                  "type": "video",
                  "video_info": {
                    "variants": [{
                      "content_type": "application/x-mpegURL",
                      "url": "https://video.twimg.com/ext_tw_video/1000000000000000101/pu/pl/first.m3u8"
                    }]
                  }
                },
                {
                  "id_str": "1000000000000000102",
                  "type": "video",
                  "video_info": {
                    "variants": [{
                      "content_type": "application/x-mpegURL",
                      "url": "https://video.twimg.com/ext_tw_video/1000000000000000102/pu/pl/second.m3u8"
                    }]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        MediaCache.ingestJson(json)
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000102/pu/pl/second.m3u8",
            fromPlayer = true
        )
        val resolved = MediaCache.resolve()
        assertEquals(2, resolved.size)
        assertTrue(
            "Saving from a multi-video tweet must put the playing clip first",
            resolved[0].url.contains("second.m3u8")
        )
    }

    @Test
    fun longPressMediaIdMustNotFallBackToTheLockedPreviousVideo() {
        val json = """
        {
          "tweets": [
            {
              "__typename": "Tweet",
              "rest_id": "1111111111111111111",
              "legacy": {
                "full_text": "a",
                "extended_entities": {
                  "media": [{
                    "id_str": "1000000000000000001",
                    "type": "video",
                    "video_info": {
                      "variants": [{
                        "content_type": "application/x-mpegURL",
                        "url": "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/pl/masterA.m3u8"
                      }]
                    }
                  }]
                }
              }
            },
            {
              "__typename": "Tweet",
              "rest_id": "2222222222222222222",
              "legacy": {
                "full_text": "b",
                "extended_entities": {
                  "media": [{
                    "id_str": "1000000000000000002",
                    "type": "video",
                    "video_info": {
                      "variants": [{
                        "content_type": "application/x-mpegURL",
                        "url": "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/masterB.m3u8"
                      }]
                    }
                  }]
                }
              }
            }
          ]
        }
        """.trimIndent()
        MediaCache.ingestJson(json)
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000001/pu/pl/masterA.m3u8",
            fromPlayer = true
        )
        val fromLongPress = MediaCache.resolve(mediaId = "1000000000000000002")
        assertEquals(1, fromLongPress.size)
        assertTrue(
            "Long-press on the visible clip must ignore the still-locked previous player",
            fromLongPress.single().url.contains("masterB.m3u8")
        )
        assertTrue(
            MediaCache.resolve(mediaId = "9999999999999999999").isEmpty()
        )
    }

    @Test
    fun copiedLinkMustDownloadThatTweetNotTheLastTimelineVideo() {
        val json = """
        {
          "tweets": [
            {
              "__typename": "Tweet",
              "rest_id": "1111111111111111111",
              "legacy": {
                "full_text": "old",
                "extended_entities": {
                  "media": [{
                    "id_str": "1001",
                    "type": "video",
                    "video_info": {
                      "variants": [{
                        "bitrate": 1000,
                        "content_type": "video/mp4",
                        "url": "https://video.twimg.com/ext_tw_video/1001/pu/vid/720x1280/a.mp4?tag=12"
                      }]
                    }
                  }]
                }
              }
            },
            {
              "__typename": "Tweet",
              "rest_id": "2222222222222222222",
              "legacy": {
                "full_text": "new",
                "extended_entities": {
                  "media": [{
                    "id_str": "1002",
                    "type": "video",
                    "video_info": {
                      "variants": [{
                        "bitrate": 2000,
                        "content_type": "video/mp4",
                        "url": "https://video.twimg.com/ext_tw_video/1002/pu/vid/1080x1920/b.mp4?tag=12"
                      }]
                    }
                  }]
                }
              }
            }
          ]
        }
        """.trimIndent()
        MediaCache.ingestJson(json)

        val copied = MediaExtractor.parseTweetIdFromShareUrl(
            "https://x.com/caochangqlng11/status/1111111111111111111"
        )
        assertEquals("1111111111111111111", copied)

        val items = MediaCache.resolve(copied)
        assertEquals(
            "Copying tweet A must not save tweet B just because B was ingested later",
            "https://video.twimg.com/ext_tw_video/1001/pu/vid/720x1280/a.mp4?tag=12",
            items.single().url
        )
        assertNull(
            "Without a tweet id or playback id, do not guess the last timeline video",
            MediaCache.bestVideoUrl()
        )
    }

    @Test
    fun originalImageNameIsUpgradedAndUserRestIdIsNotTreatedAsTweet() {
        val json = JSONObject(
            """
            {
              "__typename": "Tweet",
              "rest_id": "1800123456789012345",
              "core": {
                "user_results": {
                  "result": {
                    "__typename": "User",
                    "rest_id": "123456789012345",
                    "legacy": { "screen_name": "foo" }
                  }
                }
              },
              "legacy": {
                "full_text": "pic",
                "extended_entities": {
                  "media": [{
                    "id_str": "99",
                    "type": "photo",
                    "media_url_https": "https://pbs.twimg.com/media/GMcDe6qXoAA-7gO?format=jpg&name=small"
                  }]
                }
              }
            }
            """.trimIndent()
        )
        MediaCache.ingestObject(json)
        val items = MediaCache.resolve("1800123456789012345")
        assertEquals(1, items.size)
        assertFalse(items[0].isVideo)
        assertTrue(items[0].url.contains("name=orig"))
        assertEquals("1800123456789012345", items[0].tweetId)
    }

    @Test
    fun byMediaIdCacheIsBounded() {
        // 回归 P1-4：byMediaId 原先无容量上限，注入大量不同视频后必须被 LRU 限制
        val ids = (1..260L).map { 1_000_000_000_000_000_000L + it }
        for (tweetId in ids) {
            val mediaId = tweetId + 1_000_000L
            val json = JSONObject()
                .put("__typename", "Tweet")
                .put("rest_id", tweetId.toString())
                .put(
                    "extended_entities",
                    JSONObject().put(
                        "media",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("id_str", mediaId.toString())
                                .put("type", "video")
                                .put(
                                    "video_info",
                                    JSONObject().put(
                                        "variants",
                                        org.json.JSONArray().put(
                                            JSONObject()
                                                .put("content_type", "video/mp4")
                                                .put("bitrate", 800_000)
                                                .put("url", "https://video.twimg.com/ext_tw_video/$mediaId/pu/vid/720x1280/v.mp4")
                                        )
                                    )
                                )
                        )
                    )
                )
            MediaCache.ingestObject(json)
        }
        assertTrue(
            "byMediaId 必须被 LRU 上限约束，而不是无限增长",
            MediaCache.debugMediaIdCacheSize() <= 250
        )
        assertTrue(MediaCache.debugMediaIdCacheSize() > 0)
    }

    @Test
    fun freshPlayerLockBlocksOtherUrlsButExpiredLockDoesNot() {
        // 回归：锁必须有时效。永久锁会拒绝所有新视频 URL，
        // 导致长按任何视频都解析到同一个陈旧 mediaId。
        val idA = "2097650019889287168"
        val idB = "2098000000000000000"

        // 1. 真实播放事件写入锁（fromPlayer）
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/$idA/pu/vid/avc1/720x1280/a.mp4?tag=12",
            fromPlayer = true
        )
        assertEquals(idA, MediaCache.playerLockedMediaId)
        assertEquals(idA, MediaCache.freshPlayerLockMediaId())

        // 2. 锁新鲜时：其它视频的 URL 被拒绝（防预取串片）
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/$idB/pu/vid/avc1/720x1280/b.mp4?tag=12",
            fromPlayer = false
        )
        assertEquals("新鲜锁必须阻止其它视频的 URL 更新", idA, MediaCache.currentMediaId)

        // 3. 锁过期后：允许新视频更新 currentMediaId（修复"总是同一个视频"的根因）
        MediaCache.debugExpirePlayerLock()
        assertEquals("过期锁必须不再被采信", null, MediaCache.freshPlayerLockMediaId())

        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/$idB/pu/vid/avc1/720x1280/b.mp4?tag=12",
            fromPlayer = false
        )
        assertEquals("锁过期后新视频必须能更新 currentMediaId", idB, MediaCache.currentMediaId)
    }

    @Test
    fun copiedLinkOfTextOnlyTweetNeverResolvesToThePlayingVideo() {
        // WHY: 复制链接会弹下载选择。若查不到这条推文就回落到“当前在播”，
        // 复制一条纯文字推文会弹出另一个视频的下载，用户会存错文件。
        MediaCache.ingestJson(
            """{"__typename":"Tweet","rest_id":"2222222222222222222","legacy":{"extended_entities":{"media":[""" +
                """{"id_str":"1000000000000000002","type":"video","video_info":{"variants":[""" +
                """{"content_type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/masterB.m3u8"}""" +
                """]}}]}}}"""
        )
        MediaCache.notePlaybackUrl(
            "https://video.twimg.com/ext_tw_video/1000000000000000002/pu/pl/masterB.m3u8",
            fromPlayer = true
        )

        assertTrue("纯文字推文没有缓存媒体，必须是空", MediaCache.forTweet("3333333333333333333").isEmpty())
        assertEquals(1, MediaCache.forTweet("2222222222222222222").size)
    }

    /** 12.33 真机响应的结构（字段与层级照抄 HomeTimeline 抓包，内容裁剪）。 */
    private fun x1233Tweet(): JSONObject = JSONObject(
        """
        {"data":{"timeline_response":{"timeline":{"instructions":[{"__typename":"TimelineAddEntries","entries":[
          {"entry_id":"tweet-2108808672331915459","sort_index":"1","content":{"__typename":"TimelineTimelineItem",
           "content":{"__typename":"TimelineTweet","tweet_display_type":"Tweet","tweet_results":{"result":{
             "__typename":"Tweet","rest_id":"2108808672331915459",
             "core":{"user_results":{"result":{"__typename":"User","rest_id":"1500000000000000001"}}},
             "details":{"full_text":"x"},
             "media_entities":[
               {"id_str":"2108808547836493824","media_results":{"result":{"__typename":"ApiMedia",
                 "media_id":"2108808547836493824",
                 "media_info":{"__typename":"ApiVideo","duration_millis":44776,"variants":[
                   {"bit_rate":632000,"content_type":"video/mp4","url":"https://video.twimg.com/amplify_video/2108808547836493824/vid/avc1/320x568/a.mp4"},
                   {"bit_rate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/amplify_video/2108808547836493824/vid/avc1/720x1278/b.mp4"},
                   {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/amplify_video/2108808547836493824/pl/6HOv3JLP3PAn2mWu.m3u8"}]}}}},
               {"id_str":"2108808547836493825","media_results":{"result":{"__typename":"ApiMedia",
                 "media_id":"2108808547836493825",
                 "media_info":{"__typename":"ApiImage","original_img_height":1892,"original_img_width":2088,
                   "original_img_url":"https://pbs.twimg.com/media/HUP_brKacAA42N2.jpg"}}}}
             ]}}}}}
        ]}]}}}}
        """.trimIndent()
    )

    @Test
    fun x1233PhotosAreCollectedAsOriginals() {
        // 12.33 的图片只有 ApiImage.original_img_url，没有 type=photo / media_url_https；
        // 不认这个结构，复制链接和悬浮球就永远拿不到原图。
        MediaCache.ingestObject(x1233Tweet())
        val photo = MediaCache.forTweet("2108808672331915459").single { !it.isVideo }
        assertEquals("https://pbs.twimg.com/media/HUP_brKacAA42N2.jpg?name=orig", photo.url)
        assertEquals("2108808547836493825", photo.mediaId)
    }

    @Test
    fun x1233VideoIsKeyedByItsOwnMediaIdAndUsesTheMaster() {
        // 长按从播放器拿到的是 media_id；GraphQL 这条必须能按它查到，且给 master 以便选 1080p。
        MediaCache.ingestObject(x1233Tweet())
        val video = MediaCache.resolve(mediaId = "2108808547836493824").first()
        assertTrue(video.isVideo)
        assertEquals("2108808672331915459", video.tweetId)
        assertTrue(HlsPlaylistParser.isMasterPlaylistUrl(video.url))
        assertEquals("bit_rate 也要读到，否则多档 progressive 之间比不出高低", 2176000L, video.bitrate)
    }
}
