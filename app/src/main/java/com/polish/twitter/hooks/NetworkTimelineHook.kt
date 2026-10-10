package com.polish.twitter.hooks

import android.content.Context
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.core.Settings
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.TimelineProcessor
import com.polish.twitter.utils.HostOkHttp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 网络层唯一入口：给所有 OkHttpClient 注入时间线拦截器，并在 RealCall 上统一观察请求。
 * [onRequestUrl] 供媒体模块从播放请求里认出当前视频，避免它再单独挂一遍 RealCall。
 */
class NetworkTimelineHook(private val onRequestUrl: (String) -> Unit) : BaseHook() {

    override val name: String = "NetworkTimelineHook"

    // 拦截器与 RealCall.afterHook 会看到同一条响应；重建后的实例记在这里，避免二次 bytes()。
    private val processedResponses = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            hookOkHttp(classLoader)
            hookRealCall(classLoader)
        } catch (e: Throwable) {
            Logger.e("Failed to hook OkHttp network layer", e)
        }
    }

    private fun hookRealCall(classLoader: ClassLoader) {
        try {
            val realCallClass = classLoader.loadClass("okhttp3.internal.connection.RealCall")
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val req = XposedHelpers.getObjectField(param.thisObject, "originalRequest")
                        val url = if (req != null) {
                            XposedHelpers.callMethod(req, "url")?.toString()
                        } else {
                            null
                        }
                        if (url != null) {
                            HostOkHttp.captureClient(param.thisObject, url)
                            onRequestUrl(url)
                        }
                        if (req != null) HostOkHttp.captureCookieFromRequest(req)
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val resp = param.result ?: return
                    if (processedResponses.containsKey(resp)) return
                    try {
                        val modified = processResponseDirectly(resp, classLoader)
                        if (modified != null) {
                            param.result = modified
                        }
                    } catch (t: Throwable) {
                        Logger.w("Error in RealCall hook: ${t.message}")
                        if (t is IOException) throw t
                    }
                }
            }

            XposedBridge.hookAllMethods(realCallClass, "getResponseWithInterceptorChain", hook)
            XposedBridge.hookAllMethods(realCallClass, "getResponseWithInterceptorChain\$okhttp", hook)
            Logger.i("Successfully hooked RealCall.getResponseWithInterceptorChain")
        } catch (e: Throwable) {
            Logger.d("RealCall hook skipped or failed: ${e.message}")
        }
    }

    private var builderInstalled = false

    fun installDiscovered(classLoader: ClassLoader) = hookOkHttp(classLoader)

    @Synchronized
    private fun hookOkHttp(classLoader: ClassLoader) {
        if (builderInstalled) return
        val buildMethod = runCatching {
            classLoader.loadClass("okhttp3.OkHttpClient\$Builder").declaredMethods.singleOrNull {
                it.name == "build" && it.parameterCount == 0 &&
                    it.returnType.name == "okhttp3.OkHttpClient" &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isBridge
            }
        }.getOrNull() ?: DexKitManager.getMethod(DexKitManager.KEY_OKHTTP_CLIENT_BUILD, classLoader)
        if (buildMethod == null) return

        val interceptorClass = try {
            classLoader.loadClass("okhttp3.Interceptor")
        } catch (e: Throwable) {
            Logger.w("Cannot load okhttp3.Interceptor class")
            null
        }

        if (interceptorClass != null) {
            val timelineInterceptor = Proxy.newProxyInstance(
                classLoader,
                arrayOf(interceptorClass),
                object : InvocationHandler {
                    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
                        if (method == null) return null
                        when (method.name) {
                            "intercept" -> {
                                if (args != null && args.isNotEmpty()) {
                                    return handleIntercept(args[0], classLoader)
                                }
                                return null
                            }
                            "equals" -> return proxy === args?.getOrNull(0)
                            "hashCode" -> return System.identityHashCode(proxy)
                            "toString" -> return "TwitterPolishTimelineInterceptor"
                        }
                        return defaultValue(method.returnType)
                    }
                }
            )

            run {
                @Suppress("UNCHECKED_CAST")
                XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val builder = param.thisObject
                            val interceptors = XposedHelpers.getObjectField(builder, "interceptors") as? MutableList<Any>
                            if (interceptors != null) {
                                val alreadyHas = interceptors.any { it === timelineInterceptor }
                                if (!alreadyHas) {
                                    interceptors.add(0, timelineInterceptor)
                                    Logger.d("Added timelineInterceptor to OkHttpClient.Builder at index 0.")
                                }
                            } else {
                                XposedHelpers.callMethod(builder, "addInterceptor", timelineInterceptor)
                            }
                        } catch (t: Throwable) {
                            Logger.w("Failed to inject interceptor into OkHttpClient", t)
                        }
                    }
                })
                builderInstalled = true
            }

            try {
                val okHttpClientClass = classLoader.loadClass("okhttp3.OkHttpClient")
                @Suppress("UNCHECKED_CAST")
                XposedBridge.hookAllConstructors(okHttpClientClass, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val builder = param.args.getOrNull(0) ?: return
                        try {
                            val interceptors = XposedHelpers.getObjectField(builder, "interceptors") as? MutableList<Any>
                            if (interceptors != null && !interceptors.any { it === timelineInterceptor }) {
                                interceptors.add(0, timelineInterceptor)
                                Logger.d("Injected timelineInterceptor in OkHttpClient constructor.")
                            }
                        } catch (_: Throwable) {}
                    }
                })
            } catch (_: Throwable) {}
        }

        Logger.i("NetworkTimelineHook initialized successfully.")
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            Void.TYPE, Void::class.java -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    }

    private fun handleIntercept(chain: Any, classLoader: ClassLoader): Any {
        val request = XposedHelpers.callMethod(chain, "request")
        val response = try {
            XposedHelpers.callMethod(chain, "proceed", request)
        } catch (t: Throwable) {
            var cause: Throwable? = t
            while (cause is InvocationTargetException ||
                cause is de.robv.android.xposed.XposedHelpers.InvocationTargetError) {
                cause = cause.cause
            }
            if (cause is IOException) {
                throw cause
            }
            throw t
        }

        if (processedResponses.containsKey(response)) {
            return response
        }
        val modified = processResponseDirectly(response, classLoader)
        return modified ?: response
    }

    /**
     * 读过 [okhttp3.ResponseBody.bytes] 之后原 body 不可再读。
     * 唯一安全出口是重建 ResponseBody；禁止把已消耗的原响应交回调用方。
     */
    private fun processResponseDirectly(response: Any, classLoader: ClassLoader): Any? {
        if (processedResponses.containsKey(response)) return null

        val request = try {
            XposedHelpers.callMethod(response, "request")
        } catch (_: Throwable) { null } ?: return null

        val urlString = try {
            val urlObj = XposedHelpers.callMethod(request, "url")
            urlObj.toString()
        } catch (_: Throwable) { "" }

        if (!Constants.isTimelineApiUrl(urlString)) {
            return null
        }

        val enableAdBlock = Settings.adBlock
        val enableBypassSensitive = Settings.bypassSensitive
        if (!enableAdBlock && !enableBypassSensitive) {
            return null
        }

        Logger.d("Inspecting potential timeline API: $urlString")

        val body = try {
            XposedHelpers.callMethod(response, "body")
        } catch (_: Throwable) { null } ?: return null

        val contentType = try {
            XposedHelpers.callMethod(body, "contentType")
        } catch (_: Throwable) { null }

        if (shouldSkipContentType(contentType?.toString())) {
            return null
        }

        val contentEncoding = try {
            XposedHelpers.callMethod(response, "header", "Content-Encoding") as? String
        } catch (_: Throwable) { null }
        val isGzip = contentEncoding?.equals("gzip", ignoreCase = true) == true

        val bytes = try {
            XposedHelpers.callMethod(body, "bytes") as? ByteArray
        } catch (e: Throwable) {
            throw IOException("timeline body read failed", e)
        } ?: throw IOException("timeline body consumed but empty")

        try {
            val rawJson = if (isGzip) {
                try {
                    decompressGzip(bytes)
                } catch (e: Throwable) {
                    Logger.w("Failed to decompress GZIP response, restoring original bytes", e)
                    return rebuildOrThrow(response, classLoader, contentType, bytes, contentEncoding)
                }
            } else {
                String(bytes, Charsets.UTF_8)
            }

            val trimmed = rawJson.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return rebuildOrThrow(response, classLoader, contentType, bytes, contentEncoding)
            }

            try {
                MediaCache.ingestJson(rawJson)
            } catch (t: Throwable) {
                Logger.w("MediaCache ingest failed: ${t.message}")
            }

            val processedJson = TimelineProcessor.processTimelineResponse(
                rawJson = rawJson,
                enableAdBlock = enableAdBlock,
                enableBypassSensitive = enableBypassSensitive
            )

            if (processedJson == rawJson) {
                return rebuildOrThrow(response, classLoader, contentType, bytes, contentEncoding)
            }

            Logger.d("Timeline response rewritten")
            val outBytes = if (isGzip) {
                compressGzip(processedJson)
            } else {
                processedJson.toByteArray(Charsets.UTF_8)
            }
            val outEncoding = if (isGzip) "gzip" else null
            return rebuildOrThrow(response, classLoader, contentType, outBytes, outEncoding)
        } catch (e: Throwable) {
            Logger.e("Error processing timeline response: ${e.message}", e)
            return rebuildOrThrow(response, classLoader, contentType, bytes, contentEncoding)
        }
    }

    private fun rebuildOrThrow(
        response: Any,
        classLoader: ClassLoader,
        contentType: Any?,
        bytes: ByteArray,
        contentEncoding: String?
    ): Any {
        return rebuildResponse(response, classLoader, contentType, bytes, contentEncoding)
            ?: throw IOException("timeline body already consumed and rebuild failed")
    }

    private fun shouldSkipContentType(typeStr: String?): Boolean {
        if (typeStr.isNullOrBlank()) return false
        val t = typeStr.lowercase()
        if (t.contains("json") || t.contains("text") || t.contains("javascript")) return false
        return t.contains("image") || t.contains("video") || t.contains("audio") ||
            t.contains("octet-stream") || t.contains("mpegurl") || t.contains("protobuf")
    }

    private fun rebuildResponse(
        response: Any,
        classLoader: ClassLoader,
        contentType: Any?,
        bytes: ByteArray,
        contentEncoding: String?
    ): Any? {
        return try {
            val responseBodyClass = classLoader.loadClass("okhttp3.ResponseBody")
            val newBody = createResponseBody(responseBodyClass, contentType, bytes)
            if (newBody == null) {
                Logger.e("Failed to recreate ResponseBody after consuming stream")
                return null
            }
            val newBuilder = XposedHelpers.callMethod(response, "newBuilder")
            XposedHelpers.callMethod(newBuilder, "body", newBody)
            XposedHelpers.callMethod(newBuilder, "removeHeader", "Content-Length")
            if (!contentEncoding.isNullOrBlank()) {
                XposedHelpers.callMethod(newBuilder, "header", "Content-Encoding", contentEncoding)
            } else {
                XposedHelpers.callMethod(newBuilder, "removeHeader", "Content-Encoding")
            }
            val built = XposedHelpers.callMethod(newBuilder, "build")
            if (built != null) {
                processedResponses[built] = true
            }
            built
        } catch (e: Throwable) {
            Logger.e("Failed to rebuild response after consuming body", e)
            null
        }
    }

    private fun decompressGzip(compressed: ByteArray): String {
        ByteArrayInputStream(compressed).use { byteIn ->
            GZIPInputStream(byteIn).use { gzipIn ->
                return gzipIn.bufferedReader(Charsets.UTF_8).readText()
            }
        }
    }

    private fun compressGzip(content: String): ByteArray {
        val byteOut = ByteArrayOutputStream()
        GZIPOutputStream(byteOut).use { gzipOut ->
            gzipOut.write(content.toByteArray(Charsets.UTF_8))
            gzipOut.finish()
        }
        return byteOut.toByteArray()
    }

    private fun createResponseBody(responseBodyClass: Class<*>, contentType: Any?, bytes: ByteArray): Any? {
        val create2 = responseBodyClass.methods.firstOrNull { method ->
            method.name == "create" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == ByteArray::class.java
        }
        if (create2 != null) {
            try {
                return create2.invoke(null, contentType, bytes)
            } catch (e: Throwable) {
                Logger.d("ResponseBody.create(MediaType, byte[]) failed: ${e.message}")
            }
        }

        try {
            val loader = responseBodyClass.classLoader ?: return null
            val bufferClass = loader.loadClass("okio.Buffer")
            val buffer = bufferClass.getDeclaredConstructor().newInstance()
            val write = bufferClass.methods.firstOrNull { method ->
                method.name == "write" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == ByteArray::class.java
            } ?: return null
            write.invoke(buffer, bytes)

            val create3 = responseBodyClass.methods.firstOrNull { method ->
                method.name == "create" &&
                    method.parameterTypes.size == 3 &&
                    (method.parameterTypes[1] == Long::class.javaPrimitiveType ||
                        method.parameterTypes[1] == java.lang.Long::class.java) &&
                    method.parameterTypes[2].isInstance(buffer)
            } ?: responseBodyClass.methods.firstOrNull { method ->
                method.name == "create" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[2].name == "okio.BufferedSource"
            }
            if (create3 != null) {
                return create3.invoke(null, contentType, bytes.size.toLong(), buffer)
            }
        } catch (e: Throwable) {
            Logger.d("ResponseBody.create(MediaType, long, BufferedSource) failed: ${e.message}")
        }

        return try {
            XposedHelpers.callStaticMethod(responseBodyClass, "create", contentType, bytes)
        } catch (e: Throwable) {
            Logger.e("Failed to create ResponseBody via reflection", e)
            null
        }
    }
}
