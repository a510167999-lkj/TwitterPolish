package com.polish.twitter

import com.polish.twitter.utils.DownloadSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSecurityTest {

    @Test
    fun trustedMediaHostsAreAllowed() {
        assertTrue(DownloadSecurity.isAllowedMediaUrl("https://video.twimg.com/ext_tw_video/123/v.mp4"))
        assertTrue(DownloadSecurity.isAllowedMediaUrl("https://pbs.twimg.com/media/abc.jpg"))
        assertTrue(DownloadSecurity.isAllowedMediaUrl("https://abs-0.twimg.com/emoji/v2/72x72/1f600.png"))
        assertTrue(DownloadSecurity.isAllowedMediaUrl("https://x.com/foo/status/1"))
        assertTrue(DownloadSecurity.isAllowedMediaUrl("https://twitter.com/foo/status/1"))
    }

    @Test
    fun foreignHostsAreRejected() {
        assertFalse(DownloadSecurity.isAllowedMediaUrl("https://evil.example.com/steal"))
        assertFalse(DownloadSecurity.isAllowedMediaUrl("https://twimg.com.evil.com/steal"))
        assertFalse(DownloadSecurity.isAllowedMediaUrl("https://example.com/twimg.com/steal"))
        assertFalse(DownloadSecurity.isAllowedMediaUrl("not-a-url"))
        assertFalse(DownloadSecurity.isAllowedMediaUrl(""))
    }

    @Test
    fun credentialsOnlyTravelToTrustedHosts() {
        assertTrue(DownloadSecurity.mayAttachCredentials("https://video.twimg.com/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("https://evil.example.com/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("http://video.twimg.com/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("http://192.168.1.10/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("http://127.0.0.1/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("http://localhost/a.mp4"))
        assertFalse(DownloadSecurity.mayAttachCredentials("http://10.0.0.5/a.mp4"))
    }

    @Test
    fun blockedHostsAreDetected() {
        assertTrue(DownloadSecurity.isBlockedHost("http://127.0.0.1:8080/x"))
        assertTrue(DownloadSecurity.isBlockedHost("http://localhost/x"))
        assertTrue(DownloadSecurity.isBlockedHost("http://169.254.169.254/latest/meta-data"))
        assertTrue(DownloadSecurity.isBlockedHost("http://192.168.0.1/x"))
        assertFalse(DownloadSecurity.isBlockedHost("https://video.twimg.com/a.mp4"))
        assertFalse(DownloadSecurity.isBlockedHost("https://evil.example.com/a.mp4"))
        assertFalse(
            "公网主机不能再因为前缀 10. 被误伤",
            DownloadSecurity.isBlockedHost("https://10.evil.com/a")
        )
    }

    @Test
    fun literalPrivateAndIpv6HostsAreBlocked() {
        // WHY: Java URI.getHost() 给 IPv6 加方括号，旧的 "::1" 字符串规则匹配不到。
        assertTrue(DownloadSecurity.isBlockedHost("https://[::1]/a"))
        assertTrue(DownloadSecurity.isBlockedHost("https://[::ffff:127.0.0.1]/a"))
        assertTrue(DownloadSecurity.isBlockedHost("https://[fd00::1]/a"))
        assertTrue(DownloadSecurity.isBlockedHost("https://[fe80::1]/a"))
        assertTrue(DownloadSecurity.isBlockedHost("http://172.16.0.1/a"))
        assertTrue(DownloadSecurity.isBlockedHost("http://0.0.0.0/a"))
        assertFalse(DownloadSecurity.isBlockedHost("https://8.8.8.8/a"))
    }

    @Test
    fun redirectMustStayHttps() {
        assertEquals(
            "https://video.twimg.com/dir/b.mp4",
            DownloadSecurity.resolveRedirect("https://video.twimg.com/dir/a.m3u8", "b.mp4")
        )
        assertNull(DownloadSecurity.resolveRedirect("https://video.twimg.com/a", "http://video.twimg.com/b"))
        assertFalse(
            DownloadSecurity.mayAttachCredentials(
                DownloadSecurity.resolveRedirect("https://video.twimg.com/a", "https://evil.example/b")!!
            )
        )
    }

    @Test
    fun fileNameAndPlaybackHostRejectPathTricks() {
        assertEquals("passwd", DownloadSecurity.safeDownloadFileName("../../etc/passwd"))
        assertEquals("twitter_1_video.mp4", DownloadSecurity.safeDownloadFileName("twitter_1_video.mp4"))
        assertEquals("twitter_media", DownloadSecurity.safeDownloadFileName(".."))
        assertTrue(DownloadSecurity.isVideoTwimgUrl("https://video.twimg.com/amplify_video/1/pl/a.m3u8"))
        assertFalse(DownloadSecurity.isVideoTwimgUrl("https://video.twimg.com.evil.com/amplify_video/a.m3u8"))
        assertFalse(DownloadSecurity.isVideoTwimgUrl("https://evil.example/amplify_video/a.m3u8"))
        assertTrue(DownloadSecurity.isSafeSqlIdentifier("ExoPlayerCacheIndex123"))
        assertFalse(DownloadSecurity.isSafeSqlIdentifier("ExoPlayerCacheIndex`;drop"))
    }

    @Test
    fun pngOriginalIsNotLabelledJpeg() {
        // WHY: name=orig 可能返回 PNG。MediaStore 里登记成 image/jpeg，部分相册会按 JPEG 解码失败或显示损坏。
        assertEquals("image/png", com.polish.twitter.utils.Downloader.mimeType("twitter_1_photo_1.png", false))
        assertEquals("image/jpeg", com.polish.twitter.utils.Downloader.mimeType("twitter_1_photo_1.jpg", false))
        assertEquals("video/mp4", com.polish.twitter.utils.Downloader.mimeType("twitter_1_video_1.mp4", true))
    }
}
