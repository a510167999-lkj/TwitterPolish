package com.polish.twitter.utils

import com.polish.twitter.core.Logger
import java.io.InputStream
import java.lang.reflect.Modifier

/**
 * 复用 X 进程里的 OkHttpClient，下载走与播放相同的 TLS / Cookie。
 * 客户端与 Cookie 由 NetworkTimelineHook 在 RealCall 上捕获。
 */
object HostOkHttp {

    @Volatile
    var client: Any? = null

    @Volatile
    private var capturedFromApi = false

    @Volatile
    var cookie: String? = null

    data class OneHop(
        val code: Int,
        val stream: InputStream?,
        val contentLength: Long,
        val location: String?,
        val close: () -> Unit
    )

    /**
     * 只接受白名单主机上的客户端。CDN 客户端可以被后来的 API 客户端替换，
     * 一旦拿到 API 客户端就不再换成别的。
     */
    @Synchronized
    fun captureClient(realCallOrClient: Any, requestUrl: String?) {
        if (requestUrl.isNullOrBlank() || !DownloadSecurity.isAllowedMediaUrl(requestUrl)) return
        val fromApi = DownloadSecurity.isPreferredApiHost(requestUrl)
        if (client != null && (capturedFromApi || !fromApi)) return
        try {
            val extracted = extractClient(realCallOrClient) ?: return
            client = extracted
            capturedFromApi = fromApi
        } catch (t: Throwable) {
            Logger.d("HostOkHttp.captureClient: ${t.message}")
        }
    }

    private fun extractClient(realCallOrClient: Any): Any? {
        val cls = realCallOrClient.javaClass
        if (cls.name == "okhttp3.OkHttpClient") return realCallOrClient
        val field = cls.declaredFields.firstOrNull {
            it.name == "client" || it.type.name == "okhttp3.OkHttpClient"
        } ?: return null
        field.isAccessible = true
        return field.get(realCallOrClient)
    }

    /** 只留下载 CDN 需要的 Cookie；Authorization / csrf 用不到，不在进程里多留一份凭据。 */
    fun captureCookieFromRequest(request: Any) {
        try {
            cookie = header(request, "Cookie") ?: cookie
        } catch (_: Throwable) {
        }
    }

    /**
     * 单跳请求。调用方负责跟随重定向。
     * 关不掉宿主客户端的自动重定向时返回 null，让调用方改走 HttpURLConnection。
     */
    fun openHop(url: String): OneHop? {
        if (!DownloadSecurity.mayAttachCredentials(url)) {
            Logger.d("HostOkHttp refused untrusted host")
            return null
        }
        val okClient = client ?: return null
        val safeClient = withoutRedirects(okClient) ?: return null
        return try {
            val loader = safeClient.javaClass.classLoader ?: return null
            val builderClass = loader.loadClass("okhttp3.Request\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()

            val urlSet = builderClass.methods.firstOrNull {
                it.name == "url" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java
            }
            if (urlSet != null) {
                urlSet.invoke(builder, url)
            } else {
                val httpUrlClass = loader.loadClass("okhttp3.HttpUrl")
                val parsed = httpUrlClass.methods.firstOrNull {
                    it.name == "parse" && Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1
                }?.invoke(null, url) ?: return null
                builderClass.methods.first {
                    it.name == "url" && it.parameterTypes.size == 1 && it.parameterTypes[0] == httpUrlClass
                }.invoke(builder, parsed)
            }

            val addHeader = builderClass.methods.first {
                it.name == "addHeader" && it.parameterTypes.size == 2
            }
            addHeader.invoke(builder, "Referer", "https://x.com/")
            addHeader.invoke(builder, "Origin", "https://x.com")
            cookie?.let { addHeader.invoke(builder, "Cookie", it) }

            val request = builderClass.getMethod("build").invoke(builder)
            val requestClass = loader.loadClass("okhttp3.Request")
            val newCall = safeClient.javaClass.methods.first {
                it.name == "newCall" && it.parameterTypes.size == 1 && it.parameterTypes[0] == requestClass
            }
            val call = newCall.invoke(safeClient, request)
            val response = call.javaClass.getMethod("execute").invoke(call) ?: return null
            val code = (response.javaClass.methods.first { it.name == "code" && it.parameterTypes.isEmpty() }
                .invoke(response) as Number).toInt()
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = header(response, "Location")
                closeQuietly(response)
                return OneHop(code, null, -1L, location) {}
            }
            if (code !in 200..299) {
                Logger.d("HostOkHttp HTTP $code")
                closeQuietly(response)
                return OneHop(code, null, -1L, null) {}
            }
            val body = response.javaClass.methods.first { it.name == "body" && it.parameterTypes.isEmpty() }
                .invoke(response)
            if (body == null) {
                closeQuietly(response)
                return OneHop(code, null, -1L, null) {}
            }
            val length = try {
                (body.javaClass.methods.first { it.name == "contentLength" && it.parameterTypes.isEmpty() }
                    .invoke(body) as Number).toLong()
            } catch (_: Throwable) {
                -1L
            }
            val stream = body.javaClass.methods.first { it.name == "byteStream" && it.parameterTypes.isEmpty() }
                .invoke(body) as InputStream
            OneHop(code, stream, length, null) {
                closeQuietly(response)
            }
        } catch (t: Throwable) {
            Logger.d("HostOkHttp.openHop failed: ${t.message}")
            null
        }
    }

    private fun withoutRedirects(okClient: Any): Any? {
        return try {
            val newBuilder = okClient.javaClass.methods.firstOrNull {
                it.name == "newBuilder" && it.parameterTypes.isEmpty()
            } ?: return null
            val builder = newBuilder.invoke(okClient) ?: return null
            for (name in listOf("followRedirects", "followSslRedirects")) {
                val method = builder.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == java.lang.Boolean.TYPE
                } ?: return null
                method.invoke(builder, false)
            }
            builder.javaClass.methods.firstOrNull {
                it.name == "build" && it.parameterTypes.isEmpty()
            }?.invoke(builder)
        } catch (t: Throwable) {
            Logger.d("HostOkHttp.withoutRedirects: ${t.message}")
            null
        }
    }

    private fun header(request: Any, name: String): String? {
        return try {
            request.javaClass.methods.firstOrNull {
                it.name == "header" && it.parameterTypes.size == 1
            }?.invoke(request, name) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun closeQuietly(response: Any?) {
        try {
            response?.javaClass?.methods?.firstOrNull { it.name == "close" && it.parameterTypes.isEmpty() }
                ?.invoke(response)
        } catch (_: Throwable) {
        }
    }
}
