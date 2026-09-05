package com.polish.twitter

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
            "Long-press after playback must download the GraphQL progressive file, not the 900B DASH init",
            "https://video.twimg.com/amplify_video/2094335606436622337/vid/1280x720/full.mp4?tag=21",
            resolved[0].url
        )
        assertEquals(2176000L, resolved[0].bitrate)
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
}
