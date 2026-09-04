package com.polish.twitter.utils

import com.polish.twitter.core.Logger
import java.io.InputStream
import java.lang.reflect.Modifier

/**
 * 复用 X 进程里的 OkHttpClient，下载走与播放相同的 TLS / Cookie。
 * 字段由 NetworkTimelineHook 在 RealCall 上捕获。
 */
object HostOkHttp {

    @Volatile
    var client: Any? = null

    @Volatile
    var cookie: String? = null

    @Volatile
    var authorization: String? = null

    @Volatile
    var csrfToken: String? = null

    data class OpenedStream(
        val stream: InputStream,
        val contentLength: Long,
        val close: () -> Unit
    )

    fun captureClient(realCallOrClient: Any) {
        if (client != null) return
        try {
            val cls = realCallOrClient.javaClass
            if (cls.name == "okhttp3.OkHttpClient") {
                client = realCallOrClient
                return
            }
            val field = cls.declaredFields.firstOrNull {
                it.name == "client" || it.type.name == "okhttp3.OkHttpClient"
            }
            if (field != null) {
                field.isAccessible = true
                client = field.get(realCallOrClient)
            }
        } catch (t: Throwable) {
            Logger.d("HostOkHttp.captureClient: ${t.message}")
        }
    }

    fun captureAuthFromRequest(request: Any) {
        try {
            cookie = header(request, "Cookie") ?: cookie
            authorization = header(request, "Authorization") ?: authorization
            csrfToken = header(request, "x-csrf-token") ?: header(request, "X-Csrf-Token") ?: csrfToken
        } catch (_: Throwable) {
        }
    }

    fun open(url: String): OpenedStream? {
        val okClient = client ?: return null
        return try {
            val loader = okClient.javaClass.classLoader ?: return null
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
            val newCall = okClient.javaClass.methods.first {
                it.name == "newCall" && it.parameterTypes.size == 1 && it.parameterTypes[0] == requestClass
            }
            val call = newCall.invoke(okClient, request)
            val response = call.javaClass.getMethod("execute").invoke(call)
            val code = (response.javaClass.methods.first { it.name == "code" && it.parameterTypes.isEmpty() }
                .invoke(response) as Number).toInt()
            if (code !in 200..299) {
                Logger.w("HostOkHttp HTTP $code for $url")
                closeQuietly(response)
                return null
            }
            val body = response.javaClass.methods.first { it.name == "body" && it.parameterTypes.isEmpty() }
                .invoke(response) ?: return null
            val length = try {
                (body.javaClass.methods.first { it.name == "contentLength" && it.parameterTypes.isEmpty() }
                    .invoke(body) as Number).toLong()
            } catch (_: Throwable) {
                -1L
            }
            val stream = body.javaClass.methods.first { it.name == "byteStream" && it.parameterTypes.isEmpty() }
                .invoke(body) as InputStream
            OpenedStream(stream, length) {
                closeQuietly(response)
            }
        } catch (t: Throwable) {
            Logger.w("HostOkHttp.open failed: ${t.message}")
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
