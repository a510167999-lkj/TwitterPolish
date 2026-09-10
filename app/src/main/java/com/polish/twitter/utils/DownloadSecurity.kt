package com.polish.twitter.utils

import java.net.URI

/**
 * 下载目标域名白名单。
 *
 * 下载器会把宿主的 Cookie / Authorization / CSRF 附加到请求上，
 * 一旦媒体 URL 被构造指向非 Twitter 域，凭证就会外泄。
 * 因此所有携带凭证的下载请求必须经过本校验；
 * 非白名单 URL 一律走无凭证的匿名连接（见 Downloader.openSource / HlsDownloader.open）。
 */
object DownloadSecurity {

    /** 允许携带用户凭证的域名（含子域）。媒体 CDN 与官方域。 */
    private val CREDENTIAL_HOST_SUFFIXES = listOf(
        "twimg.com",
        "twitter.com",
        "x.com"
    )

    /** 完全不允许请求的域名黑名单关键词（防 SSRF 内网探测）。 */
    private val BLOCKED_HOST_HINTS = listOf(
        "10.", "127.", "169.254.", "192.168.",
        "localhost", ".local", ".internal", ".lan"
    )

    fun isAllowedMediaUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return CREDENTIAL_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    fun isBlockedHost(url: String): Boolean {
        val host = hostOf(url) ?: return true
        return BLOCKED_HOST_HINTS.any { hint ->
            host.startsWith(hint) || host.endsWith(hint)
        }
    }

    /** 是否允许携带用户凭证访问（仅白名单主机 + 非内网/回环地址）。 */
    fun mayAttachCredentials(url: String): Boolean {
        if (!isAllowedMediaUrl(url)) return false
        if (isBlockedHost(url)) return false
        return true
    }

    private fun hostOf(url: String): String? {
        return try {
            URI(url.trim()).host?.lowercase()
        } catch (_: Throwable) {
            null
        }
    }
}
