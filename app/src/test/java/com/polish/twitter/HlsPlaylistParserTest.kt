package com.polish.twitter

import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.utils.DownloadResolver
import com.polish.twitter.utils.ExoCacheProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(
            "GraphQL x-mpegURL is master even without /pl/{token}.m3u8",
            HlsPlaylistParser.isMasterPlaylistUrl(
                "https://video.twimg.com/vid/playlist.m3u8"
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
        val picked = ExoCacheProbe.pickFromKeys(keys, "2093578924810960896")!!
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
        val picked = ExoCacheProbe.pickFromKeys(keys, "2093578924810960896")!!
        assertTrue(picked.url.contains("/pl/avc1/720x1280/"))
        assertTrue(picked.hlsAudioUrl.contains("/pl/mp4a/128000/"))
    }

    @Test
    fun exoCacheNeverGuessesTheLatestCachedVideoWhenThePlayerIsUnknown() {
        // 真机 12.33：长按认不出播放器时回落到「缓存里最后一条」，不同视频都下成了同一条
        // 已删除的视频，播放列表 404。不知道是哪条视频，就不能从缓存里挑一条。
        val keys = listOf(
            "https://video.twimg.com/amplify_video/2108796446237687808/pl/hm-G-EyLhrHUUV4X.m3u8",
            "https://video.twimg.com/amplify_video/2108796446237687808/pl/avc1/720x1280/x.m3u8"
        )
        assertNull(ExoCacheProbe.pickFromKeys(keys, null))
        assertNull(ExoCacheProbe.pickFromKeys(keys, ""))
    }

    @Test
    fun exoCacheMustNotFallBackToAnotherVideoWhenPreferredIdHasNoKeys() {
        val keys = listOf(
            "https://video.twimg.com/amplify_video/1111111111111111111/pl/oldMaster.m3u8?tag=21",
            "https://video.twimg.com/amplify_video/1111111111111111111/pl/avc1/720x1280/old.m3u8"
        )
        assertNull(
            "Previous video still in Exo cache must not be saved for a different playing id",
            ExoCacheProbe.pickFromKeys(keys, "2222222222222222222")
        )
    }

    @Test
    fun downloadResolverUpgradesProgressiveToHlsMasterOfTheSameVideo() {
        val progressive = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/2093578924810960896/vid/720x1280/full.mp4",
            isVideo = true,
            fileName = "twitter_a_video.mp4",
            bitrate = 2176000,
            tweetId = "2056623384482783650",
            mediaId = "2093578924810960896"
        )
        val hls = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/2093578924810960896/pl/Q1z5fx0TWVid4Wit.m3u8?tag=21",
            isVideo = true,
            fileName = "twitter_hls.mp4",
            mediaId = "2093578924810960896"
        )
        val merged = DownloadResolver.merge(listOf(progressive), hls, "2093578924810960896")
        assertEquals(1, merged.size)
        assertTrue(
            "Chooser must not keep 720p progressive when master is available",
            HlsPlaylistParser.isMasterPlaylistUrl(merged[0].url)
        )
        assertEquals("2056623384482783650", merged[0].tweetId)
    }

    @Test
    fun downloadResolverDoesNotTreatBlankMediaIdsAsTheSameVideo() {
        val item = ExtractedMedia(
            url = "https://video.twimg.com/a.mp4",
            isVideo = true,
            fileName = "a.mp4"
        )
        val hls = ExtractedMedia(
            url = "https://video.twimg.com/b/pl/master.m3u8",
            isVideo = true,
            fileName = "b.mp4"
        )
        val merged = DownloadResolver.merge(listOf(item), hls, null)
        assertEquals(
            "缺少 mediaId 时不能把两条视频当成同一条",
            item.url,
            merged.single().url
        )
    }

    @Test
    fun downloadResolverDoesNotReplaceWithADifferentVideosHls() {
        val current = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/1000000000000000002/pl/masterB.m3u8",
            isVideo = true,
            fileName = "b.mp4",
            mediaId = "1000000000000000002"
        )
        val staleHls = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/1000000000000000001/pl/masterA.m3u8",
            isVideo = true,
            fileName = "a.mp4",
            mediaId = "1000000000000000001"
        )
        val merged = DownloadResolver.merge(listOf(current), staleHls, "1000000000000000002")
        assertEquals(
            "Stale Exo cache of the previous clip must not replace the playing video",
            current.url,
            merged.single().url
        )
    }

    @Test
    fun downloadQualityRanksMasterAboveProgressive() {
        val master = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/1/pl/token.m3u8",
            isVideo = true,
            fileName = "m.mp4"
        )
        val progressive = ExtractedMedia(
            url = "https://video.twimg.com/amplify_video/1/vid/1280x720/full.mp4",
            isVideo = true,
            fileName = "p.mp4",
            bitrate = 2176000
        )
        assertTrue(MediaExtractor.downloadQuality(master) > MediaExtractor.downloadQuality(progressive))
    }
}
