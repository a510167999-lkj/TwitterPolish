package com.polish.twitter.hooks

import android.content.Context
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
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
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class NetworkTimelineHook : BaseHook() {

    override val name: String = "NetworkTimelineHook"
    private var appContext: Context? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        this.appContext = context
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
                    HostOkHttp.captureClient(param.thisObject)
                    try {
                        val req = XposedHelpers.getObjectField(param.thisObject, "originalRequest")
                        if (req != null) {
                            HostOkHttp.captureAuthFromRequest(req)
                        }
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val resp = param.result ?: return
                    try {
                        val modified = processResponseDirectly(resp, classLoader)
                        if (modified != null) {
                            param.result = modified
                        }
                    } catch (t: Throwable) {
                        Logger.w("Error in RealCall hook: ${t.message}")
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

    private fun hookOkHttp(classLoader: ClassLoader) {
        val okHttpBuilderClass = try {
            classLoader.loadClass("okhttp3.OkHttpClient\$Builder")
        } catch (e: ClassNotFoundException) {
            val descriptor = DexKitManager.getMethodDescriptor(DexKitManager.KEY_OKHTTP_CLIENT_BUILD)
            if (descriptor != null) {
                classLoader.loadClass(descriptor.first)
            } else {
                null
            }
        }

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
                        if (method?.name == "intercept" && args != null && args.isNotEmpty()) {
                            val chain = args[0]
                            return handleIntercept(chain, classLoader)
                        }
                        return method?.invoke(proxy, *(args ?: emptyArray()))
                    }
                }
            )

            if (okHttpBuilderClass != null) {
                XposedBridge.hookAllMethods(okHttpBuilderClass, "build", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val builder = param.thisObject
                            val interceptors = XposedHelpers.getObjectField(builder, "interceptors") as? MutableList<Any>
                            if (interceptors != null) {
                                val alreadyHas = interceptors.any {
                                    it === timelineInterceptor || Proxy.isProxyClass(it.javaClass)
                                }
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
            }

            try {
                val okHttpClientClass = classLoader.loadClass("okhttp3.OkHttpClient")
                XposedBridge.hookAllConstructors(okHttpClientClass, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val builder = param.args.getOrNull(0) ?: return
                        try {
                            val interceptors = XposedHelpers.getObjectField(builder, "interceptors") as? MutableList<Any>
                            if (interceptors != null && !interceptors.any { it === timelineInterceptor || Proxy.isProxyClass(it.javaClass) }) {
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

        val modified = processResponseDirectly(response, classLoader)
        return modified ?: response
    }

    private fun processResponseDirectly(response: Any, classLoader: ClassLoader): Any? {
        val request = try {
            XposedHelpers.callMethod(response, "request")
        } catch (_: Throwable) { null } ?: return null

        val urlString = try {
            val urlObj = XposedHelpers.callMethod(request, "url")
            urlObj.toString()
        } catch (_: Throwable) { "" }

        val isTimelineRequest = urlString.contains("graphql", ignoreCase = true) ||
                urlString.contains("timeline", ignoreCase = true) ||
                urlString.contains("/2/", ignoreCase = true) ||
                urlString.contains("/1.1/", ignoreCase = true)

        if (!isTimelineRequest) {
            return null
        }

        Logger.d("Inspecting potential timeline API: $urlString")

        try {
            val ctx = appContext
            var enableAdBlock = true
            var enableChronoSort = true

            if (ctx != null) {
                val prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                enableAdBlock = prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true)
                enableChronoSort = prefs.getBoolean(Constants.PREF_ENABLE_CHRONO_SORT, true)
            }

            if (!enableAdBlock && !enableChronoSort) {
                return null
            }

            val body = XposedHelpers.callMethod(response, "body") ?: return null
            val contentType = XposedHelpers.callMethod(body, "contentType")

            val contentEncoding = try {
                XposedHelpers.callMethod(response, "header", "Content-Encoding") as? String
            } catch (_: Throwable) { null }

            val isGzip = contentEncoding?.equals("gzip", ignoreCase = true) == true

            val rawBytes = XposedHelpers.callMethod(body, "bytes") as? ByteArray ?: return null

            val rawJson = if (isGzip) {
                try {
                    decompressGzip(rawBytes)
                } catch (e: Throwable) {
                    Logger.w("Failed to decompress GZIP response, trying raw string", e)
                    String(rawBytes, Charsets.UTF_8)
                }
            } else {
                String(rawBytes, Charsets.UTF_8)
            }

            try {
                MediaCache.ingestJson(rawJson)
            } catch (t: Throwable) {
                Logger.w("MediaCache ingest failed: ${t.message}")
            }

            val processedJson = TimelineProcessor.processTimelineResponse(
                rawJson = rawJson,
                enableAdBlock = enableAdBlock,
                enableChronoSort = enableChronoSort
            )

            if (processedJson == rawJson) {
                val responseBodyClass = classLoader.loadClass("okhttp3.ResponseBody")
                val untouchedBody = createResponseBody(responseBodyClass, contentType, rawBytes) ?: return null
                val newBuilder = XposedHelpers.callMethod(response, "newBuilder")
                XposedHelpers.callMethod(newBuilder, "body", untouchedBody)
                return XposedHelpers.callMethod(newBuilder, "build")
            }

            Logger.i("Purified and chronologically ordered timeline: $urlString")

            val responseBodyClass = classLoader.loadClass("okhttp3.ResponseBody")
            val newBuilder = XposedHelpers.callMethod(response, "newBuilder")

            val newBody = if (isGzip) {
                val compressedBytes = compressGzip(processedJson)
                createResponseBody(responseBodyClass, contentType, compressedBytes)
            } else {
                val utf8Bytes = processedJson.toByteArray(Charsets.UTF_8)
                createResponseBody(responseBodyClass, contentType, utf8Bytes)
            }

            XposedHelpers.callMethod(newBuilder, "body", newBody)
            return XposedHelpers.callMethod(newBuilder, "build")

        } catch (e: Throwable) {
            Logger.e("Error processing timeline response: ${e.message}", e)
            return null
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
        return try {
            val createMethod = responseBodyClass.methods.firstOrNull { method ->
                method.name == "create" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[1] == ByteArray::class.java
            }

            if (createMethod != null) {
                createMethod.invoke(null, contentType, bytes)
            } else {
                XposedHelpers.callStaticMethod(
                    responseBodyClass,
                    "create",
                    contentType,
                    bytes
                )
            }
        } catch (e: Throwable) {
            Logger.e("Failed to create ResponseBody via reflection", e)
            null
        }
    }
}
