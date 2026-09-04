package com.polish.twitter.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.polish.twitter.core.Logger
import com.polish.twitter.utils.Downloader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.zip.GZIPInputStream

class MediaDownloadHook : BaseHook() {

    override val name: String = "MediaDownloadHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private var currentActivity: Activity? = null

    companion object {
        /** 最近一次 OkHttp 捕获到的 MP4 直链（最可靠来源）*/
        @Volatile var latestVideoUrl: String? = null

        /** 最近一次从 GraphQL JSON 中解析出的最高码率 MP4 直链 */
        @Volatile var graphqlVideoUrl: String? = null

        /**
         * 供 NetworkTimelineHook 调用，解析 GraphQL 响应 JSON 并更新
         * graphqlVideoUrl / latestVideoUrl（如 JSON 中含 video_info）
         */
        fun inspectJsonForVideoUrls(json: String) {
            try {
                val root = JSONObject(json)
                val best = findBestVideoVariant(root)
                if (!best.isNullOrBlank()) {
                    graphqlVideoUrl = best
                    latestVideoUrl = best      // GraphQL 来源也可以作为直链使用
                    Logger.d("GraphQL video URL captured: $best")
                }
            } catch (_: Throwable) {}
        }

        /** 递归遍历 JSON 树，找到 video_info.variants 中 bitrate 最高的 mp4 直链 */
        private fun findBestVideoVariant(obj: JSONObject): String? {
            var best: String? = null
            var bestBitrate = -1

            // 先声明 walk，再声明 walkArray（避免前向引用编译错误）
            fun walk(o: JSONObject) {
                // 找到 video_info 节点
                if (o.has("video_info")) {
                    val vi = o.optJSONObject("video_info")
                    val variants = vi?.optJSONArray("variants")
                    if (variants != null) {
                        for (i in 0 until variants.length()) {
                            val v = variants.optJSONObject(i) ?: continue
                            val ct = v.optString("content_type", "")
                            val url = v.optString("url", "")
                            val bitrate = v.optInt("bitrate", 0)
                            if (ct == "video/mp4" && url.isNotBlank() && bitrate >= bestBitrate) {
                                bestBitrate = bitrate
                                best = url
                            }
                        }
                    }
                }
                // 递归键值（通过显式递归代替 walkArray 引用）
                for (key in o.keys()) {
                    when (val child = o.opt(key)) {
                        is JSONObject -> walk(child)
                        is JSONArray  -> {
                            for (i in 0 until child.length()) {
                                val item = child.opt(i)
                                if (item is JSONObject) walk(item)
                            }
                        }
                    }
                }
            }

            walk(obj)
            return best
        }
    }

    override fun init(classLoader: ClassLoader, context: Context) {
        this.appContext = context
        try {
            hookActivityLifecycle()
            hookOkHttpVideoRequests(classLoader)
            hookExoPlayer(classLoader)
            hookClipboard()
        } catch (e: Throwable) {
            Logger.e("Failed to initialize MediaDownloadHook", e)
        }
    }

    // ----------- Activity 生命周期，保持对当前 Activity 的引用 ----------- //

    private fun hookActivityLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        currentActivity = act
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ----------- 策略1：OkHttp 请求层捕获 video.twimg.com MP4 直链 ----------- //

    /**
     * X 请求 video.twimg.com/ext_tw_video/.../1280x720/video.mp4 时，
     * 我们直接从 Request URL 中获取最终 MP4 直链，无需等待播放器。
     * 这是最可靠的方式——URL 100% 是有效可下载链接。
     */
    private fun hookOkHttpVideoRequests(classLoader: ClassLoader) {
        try {
            val realCallClass = classLoader.loadClass("okhttp3.internal.connection.RealCall")
            XposedBridge.hookAllMethods(realCallClass, "getResponseWithInterceptorChain", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // RealCall 内有 originalRequest 字段
                        val req = XposedHelpers.getObjectField(param.thisObject, "originalRequest")
                        val urlObj = XposedHelpers.callMethod(req, "url")
                        val urlStr = urlObj?.toString() ?: return
                        captureVideoUrlFromRequest(urlStr)
                    } catch (_: Throwable) {}
                }
            })
            Logger.i("Hooked OkHttp RealCall to capture video.twimg.com MP4 URLs")
        } catch (e: Throwable) {
            Logger.w("RealCall video URL capture hook failed: ${e.message}")
        }

        // 同时 hook OkHttpClient.newCall 以覆盖不同入口
        try {
            val clientClass = classLoader.loadClass("okhttp3.OkHttpClient")
            XposedBridge.hookAllMethods(clientClass, "newCall", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val req = param.args.getOrNull(0) ?: return
                        val urlObj = XposedHelpers.callMethod(req, "url")
                        val urlStr = urlObj?.toString() ?: return
                        captureVideoUrlFromRequest(urlStr)
                    } catch (_: Throwable) {}
                }
            })
        } catch (_: Throwable) {}
    }

    private fun captureVideoUrlFromRequest(urlStr: String) {
        // 仅捕获 video.twimg.com 上的 MP4 文件（排除 HLS m3u8 manifest）
        if ((urlStr.contains("video.twimg.com") || urlStr.contains("ext_tw_video")) &&
            urlStr.contains(".mp4") && !urlStr.contains(".m3u8")) {
            // 取分辨率最高的：X 通常 URL 里含有 1280x720 或 2048x1152
            val current = latestVideoUrl
            if (current == null || isBetterVideoUrl(urlStr, current)) {
                latestVideoUrl = urlStr
                Logger.d("OkHttp captured video.twimg.com MP4: $urlStr")
            }
        }
    }

    /** 比较两个 video.twimg.com URL，返回分辨率更高的那个是否为 candidate */
    private fun isBetterVideoUrl(candidate: String, current: String): Boolean {
        // 从 URL 路径中提取 WxH
        val resRegex = Regex("""(\d+)x(\d+)""")
        val cRes = resRegex.find(candidate)
        val curRes = resRegex.find(current)
        if (cRes != null && curRes != null) {
            val cPx = cRes.groupValues[1].toIntOrNull()?.times(cRes.groupValues[2].toIntOrNull() ?: 0) ?: 0
            val curPx = curRes.groupValues[1].toIntOrNull()?.times(curRes.groupValues[2].toIntOrNull() ?: 0) ?: 0
            return cPx >= curPx
        }
        return true
    }

    // ----------- 策略2：ExoPlayer setMediaItem/setMediaSource 兜底 ----------- //

    private fun hookExoPlayer(classLoader: ClassLoader) {
        try {
            val exoClass = try {
                classLoader.loadClass("androidx.media3.exoplayer.ExoPlayer")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("com.google.android.exoplayer2.ExoPlayer")
            }
            // hook setMediaItem
            XposedBridge.hookAllMethods(exoClass, "setMediaItem", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    extractUriFromMediaItem(param.args.getOrNull(0))
                }
            })
            // hook setMediaItems (plural)
            try {
                XposedBridge.hookAllMethods(exoClass, "setMediaItems", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val list = param.args.getOrNull(0) ?: return
                        if (list is List<*>) {
                            list.forEach { extractUriFromMediaItem(it) }
                        }
                    }
                })
            } catch (_: Throwable) {}
            Logger.i("ExoPlayer setMediaItem(s) hooked as fallback video URL capture.")
        } catch (e: Throwable) {
            Logger.w("ExoPlayer hook failed (non-fatal): ${e.message}")
        }
    }

    private fun extractUriFromMediaItem(mediaItem: Any?) {
        if (mediaItem == null) return
        try {
            val localConfig = XposedHelpers.getObjectField(mediaItem, "localConfiguration")
            if (localConfig != null) {
                val uri = XposedHelpers.getObjectField(localConfig, "uri")
                val uriStr = uri?.toString()
                if (!uriStr.isNullOrBlank() &&
                    (uriStr.contains(".mp4") || uriStr.contains("video.twimg.com"))) {
                    if (isBetterVideoUrl(uriStr, latestVideoUrl ?: "")) {
                        latestVideoUrl = uriStr
                        Logger.d("ExoPlayer captured video URL: $uriStr")
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // ----------- 剪贴板：用户复制推文链接时弹出下载对话框 ----------- //

    private fun hookClipboard() {
        try {
            XposedHelpers.findAndHookMethod(
                ClipboardManager::class.java, "setPrimaryClip", ClipData::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount == 0) return
                        val text = clip.getItemAt(0).text?.toString() ?: return
                        if ((text.contains("x.com/") || text.contains("twitter.com/")) && text.contains("/status/")) {
                            Logger.i("Clipboard: copied tweet link detected: $text")
                            mainHandler.post {
                                val act = currentActivity
                                if (act != null && !act.isFinishing) {
                                    showDownloadDialog(act)
                                } else {
                                    appContext?.let {
                                        Toast.makeText(it, "📥 推文已复制，通过悬浮球⚙️可一键下载视频", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.w("ClipboardManager hook failed", e)
        }
    }

    // ----------- 下载对话框 ----------- //

    /**
     * 按优先级选择最可靠的视频直链：
     *   1. OkHttp 直接捕获的 video.twimg.com MP4 URL（最可靠）
     *   2. GraphQL JSON 里解析出的最高码率 MP4 URL
     */
    private fun showDownloadDialog(activity: Activity) {
        val videoUrl = latestVideoUrl
            ?: graphqlVideoUrl

        if (videoUrl.isNullOrBlank()) {
            AlertDialog.Builder(activity)
                .setTitle("📥 下载推文媒体")
                .setMessage("暂未检测到该推文的视频流。\n\n请先在 X 内播放一下视频（等待几秒），再点击分享→复制链接，下载对话框将自动弹出。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val isVideo = videoUrl.contains(".mp4") || videoUrl.contains("video.twimg.com")
        val ext = if (isVideo) "mp4" else "jpg"
        val fileName = "TwitterPolish_${System.currentTimeMillis()}.$ext"

        AlertDialog.Builder(activity)
            .setTitle(if (isVideo) "📥 下载推文 1080p 视频" else "📥 下载推文高清原图")
            .setMessage("已自动解析媒体直链：\n${videoUrl.take(80)}...\n\n点击【立即下载】开始后台下载，进度显示在通知栏。")
            .setPositiveButton("立即下载") { _, _ ->
                Downloader.download(activity, videoUrl, fileName, isVideo)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
