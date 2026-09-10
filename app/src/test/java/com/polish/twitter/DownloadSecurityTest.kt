package com.polish.twitter

import com.polish.twitter.utils.DownloadSecurity
import org.junit.Assert.assertFalse
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
        // 内网 / 回环地址永远不允许携带凭证（防 SSRF + 防凭证外泄）
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
    }
}
