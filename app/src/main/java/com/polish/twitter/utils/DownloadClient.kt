package com.polish.twitter.utils

import com.polish.twitter.core.Logger
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手动跟随重定向的下载传输。
 * 每一跳都重新检查 https、主机白名单和解析后的地址，避免 Cookie 跟着 302 离开 Twitter。
 */
object DownloadClient {

    private const val MAX_HOPS = 5

    class Opened(
        val stream: InputStream,
        val contentLength: Long,
        val close: () -> Unit
    )

    fun open(initialUrl: String, withCredentials: Boolean, pinHost: String? = null): Opened {
        var current = initialUrl.trim()
        repeat(MAX_HOPS) {
            val host = DownloadSecurity.hostOf(current) ?: throw RuntimeException("无效下载地址")
            if (pinHost != null && !host.equals(pinHost, ignoreCase = true)) {
                throw RuntimeException("分片主机与播放列表不一致")
            }
            if (!DownloadSecurity.isHttps(current) || DownloadSecurity.isBlockedHost(current)) {
                throw RuntimeException("下载地址不安全")
            }
            if (DownloadSecurity.resolvesToNonPublic(host)) {
                throw RuntimeException("下载地址解析到非公网地址")
            }
            val credentials = withCredentials && DownloadSecurity.mayAttachCredentials(current)
            if (withCredentials && !credentials) {
                throw RuntimeException("拒绝把登录凭证带到该地址")
            }
            val hop = request(current, credentials)
            if (hop.location != null) {
                hop.close()
                val next = DownloadSecurity.resolveRedirect(current, hop.location)
                    ?: throw RuntimeException("拒绝不安全的重定向")
                Logger.d("Download redirect hop")
                current = next
                return@repeat
            }
            val stream = hop.stream ?: throw RuntimeException("HTTP ${hop.code}")
            return Opened(stream, hop.contentLength, hop.close)
        }
        throw RuntimeException("重定向次数过多")
    }

    private class Hop(
        val code: Int,
        val stream: InputStream?,
        val contentLength: Long,
        val location: String?,
        val close: () -> Unit
    )

    private fun request(url: String, credentials: Boolean): Hop {
        if (credentials) {
            val via = HostOkHttp.openHop(url)
            if (via != null) {
                if (via.stream == null && via.location == null) {
                    via.close()
                    throw RuntimeException("HTTP ${via.code}")
                }
                return Hop(via.code, via.stream, via.contentLength, via.location, via.close)
            }
        }
        return httpHop(url, credentials)
    }

    private fun httpHop(url: String, credentials: Boolean): Hop {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 90_000
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Accept", "video/mp4,image/jpeg,image/*,*/*;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (credentials) {
            connection.setRequestProperty("Referer", "https://x.com/")
            connection.setRequestProperty("Origin", "https://x.com")
            HostOkHttp.cookie?.let { connection.setRequestProperty("Cookie", it) }
        }
        connection.connect()
        val code = connection.responseCode
        Logger.d("HTTP $code")
        if (isRedirect(code)) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            return Hop(code, null, -1L, location) {}
        }
        if (code !in 200..299) {
            connection.disconnect()
            throw RuntimeException("HTTP 错误 $code")
        }
        return Hop(code, connection.inputStream, connection.contentLengthLong, null) {
            connection.disconnect()
        }
    }

    private fun isRedirect(code: Int): Boolean {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308
    }
}
