package com.polish.twitter.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 下载请求的主机边界。
 *
 * 带登录凭证的请求只允许 https，且主机必须是 Twitter/X 媒体域。
 * 内网、回环、链路本地、未指定地址和 IPv6 ULA 一律拒绝。
 * 字面量在这里判断；域名的解析结果由 [resolvesToNonPublic] 在连接前再判一次。
 */
object DownloadSecurity {

    private val CREDENTIAL_HOST_SUFFIXES = listOf(
        "twimg.com",
        "twitter.com",
        "x.com"
    )

    private val IPV4_LITERAL = Regex(
        """^(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}$"""
    )
    private val IPV6_LITERAL = Regex("""^[0-9a-f:.]{2,}$""")

    fun isHttps(url: String): Boolean {
        return try {
            URI(url.trim()).scheme.equals("https", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    fun isAllowedMediaUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return CREDENTIAL_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    fun isBlockedHost(url: String): Boolean {
        val host = hostOf(url) ?: return true
        if (isLocalName(host)) return true
        val literal = parseLiteral(host) ?: return false
        return isNonPublic(literal)
    }

    /** 是否允许携带用户凭证。仅 https + 白名单主机 + 不是内网字面量。 */
    fun mayAttachCredentials(url: String): Boolean {
        if (!isHttps(url)) return false
        if (!isAllowedMediaUrl(url)) return false
        if (isBlockedHost(url)) return false
        return true
    }

    /**
     * 连接前再看解析结果。字面量不走 DNS。
     * 任一地址不是公网，或解析失败，都视为拒绝。
     */
    fun resolvesToNonPublic(host: String): Boolean {
        val name = canonicalHost(host)
        val literal = parseLiteral(name)
        if (literal != null) return isNonPublic(literal)
        return try {
            val addresses = InetAddress.getAllByName(name)
            addresses.isEmpty() || addresses.any { isNonPublic(it) }
        } catch (_: Throwable) {
            true
        }
    }

    /** 把重定向 Location 解析成绝对 https URL。明文或无法解析时返回 null。 */
    fun resolveRedirect(base: String, location: String): String? {
        val target = location.trim()
        if (target.isEmpty()) return null
        return try {
            val resolved = URI(base.trim()).resolve(target).toString()
            if (isHttps(resolved)) resolved else null
        } catch (_: Throwable) {
            null
        }
    }

    fun isVideoTwimgUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (host != "twimg.com" && !host.endsWith(".twimg.com")) return false
        return host == "video.twimg.com" ||
            "/ext_tw_video/" in url ||
            "/amplify_video/" in url ||
            "/tweet_video/" in url
    }

    /** 用来替换过早捕获的 CDN OkHttpClient。 */
    fun isPreferredApiHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "api.x.com" || host == "api.twitter.com" ||
            host == "x.com" || host == "twitter.com"
    }

    fun safeDownloadFileName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = buildString(leaf.length) {
            for (ch in leaf) {
                if (ch == '.' || ch == '_' || ch == '-' || ch.isLetterOrDigit()) append(ch)
            }
        }.trim { it == '.' || it == '_' || it == '-' }
        if (cleaned.isEmpty()) return "twitter_media"
        return cleaned.take(120)
    }

    fun isSafeSqlIdentifier(name: String): Boolean {
        return name.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))
    }

    fun hostOf(url: String): String? {
        return try {
            URI(url.trim()).host?.let { canonicalHost(it) }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun canonicalHost(host: String): String {
        var value = host.trim().lowercase()
        if (value.startsWith("[") && value.endsWith("]") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        return value
    }

    internal fun isNonPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        if (address is Inet4Address) {
            val bytes = address.address
            if (bytes[0] == 0.toByte()) return true
            if (bytes.all { it == 0xFF.toByte() }) return true
        }
        if (address is Inet6Address) {
            val first = address.address[0].toInt() and 0xff
            if (first and 0xfe == 0xfc) return true
        }
        return false
    }

    private fun isLocalName(host: String): Boolean {
        return host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".lan")
    }

    private fun parseLiteral(host: String): InetAddress? {
        val value = canonicalHost(host)
        val candidate = when {
            IPV4_LITERAL.matches(value) -> value
            ':' in value && IPV6_LITERAL.matches(value) -> value
            else -> return null
        }
        return try {
            InetAddress.getByName(candidate)
        } catch (_: Throwable) {
            null
        }
    }
}
