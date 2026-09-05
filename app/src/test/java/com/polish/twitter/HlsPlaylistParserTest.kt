package com.polish.twitter

import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.utils.ExoCacheProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-32000",AUTOSELECT=YES,URI="/amplify_video/1974992463661289472/pl/mp4a/32000/VlM_ZFvXJa4vnJES.m3u8"
        #EXT-X-MEDIA:NAME="Audio",TYPE=AUDIO,GROUP-ID="audio-128000",AUTOSELECT=YES,URI="/amplify_video/1974992463661289472/pl/mp4a/128000/4fSIpl7HlQOQKAd1.m3u8"
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=93181,BANDWIDTH=149034,RESOLUTION=480x270,CODECS="mp4a.40.2,avc1.4D4015",AUDIO="audio-32000"
        /amplify_video/1974992463661289472/pl/avc1/480x270/tfcIaGtghaMRe6P5.m3u8
        #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=1505617,BANDWIDTH=2372256,RESOLUTION=1920x1080,CODECS="mp4a.40.2,avc1.640032",AUDIO="audio-128000"
        /amplify_video/1974992463661289472/pl/avc1/1920x1080/INhPr2hrWQu6anIL.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-MAP:URI="/amplify_video/1974992463661289472/vid/avc1/0/0/1920x1080/MvQcUji9OKbPZhG-.mp4"
        #EXTINF:3.000,
        /amplify_video/1974992463661289472/vid/avc1/0/3000/1920x1080/IOHkdJpNm61XWnFk.m4s
        #EXTINF:3.000,
        /amplify_video/1974992463661289472/vid/avc1/3000/6000/1920x1080/4n1QaOrrGQtIV2tC.m4s
    """.trimIndent()

    @Test
    fun masterUrlWithQueryStillCountsAsMaster() {
        assertTrue(
            HlsPlaylistParser.isMasterPlaylistUrl(
                "https://video.twimg.com/amplify_video/1/pl/Q1z5fx0TWVid4Wit.m3u8?tag=21"
            )
        )
        assertFalse(
            HlsPlaylistParser.isMasterPlaylistUrl(
                "https://video.twimg.com/amplify_video/1/pl/avc1/720x1280/hi.m3u8?tag=21"
            )
        )
    }

    @Test
    fun masterPicksHighestResolutionAndMatchingAudioGroup() {
        val variants = HlsPlaylistParser.parseMaster(
            master,
            "https://video.twimg.com/amplify_video/1974992463661289472/pl/Q1z5fx0TWVid4Wit.m3u8"
        )
        val best = HlsPlaylistParser.pickBest(variants)!!
        assertEquals(1920, best.width)
        assertEquals(1080, best.height)
        assertTrue(
            "Need the 1080p CMAF playlist, not a 900-byte DASH init",
            best.videoUrl.contains("/pl/avc1/1920x1080/")
        )
        assertTrue(
            "Audio is a separate HLS group; muxing 1080p without it would be silent",
            best.audioUrl!!.contains("/pl/mp4a/128000/")
        )
    }

    @Test
    fun mediaPlaylistListsInitMapAndSegments() {
        val parsed = HlsPlaylistParser.parseMedia(
            media,
            "https://video.twimg.com/amplify_video/1974992463661289472/pl/avc1/1920x1080/INhPr2hrWQu6anIL.m3u8"
        )
        assertTrue(parsed.mapUrl!!.endsWith("/vid/avc1/0/0/1920x1080/MvQcUji9OKbPZhG-.mp4"))
        assertEquals(2, parsed.segments.size)
        assertTrue(parsed.segments[0].startsWith("https://video.twimg.com/"))
    }

    @Test
    fun exoCacheKeysPreferMasterOverThePlaying720pVariant() {
        val keys = listOf(
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/Q1z5fx0TWVid4Wit.m3u8?tag=21",
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/avc1/720x1280/hi.m3u8",
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/mp4a/32000/aud.m3u8",
            "https://video.twimg.com/amplify_video/2093578924810960896/vid/avc1/57000/60000/720x1280/seg.m4s"
        )
        val picked = ExoCacheProbe.pickFromKeys(keys)!!
        assertEquals("2093578924810960896", picked.mediaId)
        assertTrue(
            "Must use master so the downloader can pick 1080p instead of the playing 720p ladder",
            HlsPlaylistParser.isMasterPlaylistUrl(picked.url)
        )
        assertFalse(picked.url.contains("/avc1/720x1280/"))
        assertFalse(picked.url.endsWith(".m4s"))
    }

    @Test
    fun exoCacheFallsBackToHighestCachedVariantWhenMasterMissing() {
        val keys = listOf(
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/avc1/480x270/low.m3u8",
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/avc1/720x1280/hi.m3u8",
            "https://video.twimg.com/amplify_video/2093578924810960896/pl/mp4a/128000/aud.m3u8",
            "https://video.twimg.com/amplify_video/2093578924810960896/vid/avc1/57000/60000/720x1280/seg.m4s"
        )
        val picked = ExoCacheProbe.pickFromKeys(keys)!!
        assertTrue(picked.url.contains("/pl/avc1/720x1280/"))
        assertTrue(picked.hlsAudioUrl.contains("/pl/mp4a/128000/"))
    }
}
