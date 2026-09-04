package com.polish.twitter.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.utils.Downloader
import com.polish.twitter.utils.HostOkHttp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class MediaDownloadHook : BaseHook() {

    override val name: String = "MediaDownloadHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private var currentActivity: Activity? = null

    private var pressDownX = 0f
    private var pressDownY = 0f
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private var trackingSurface = false

    companion object {
        /** 兼容旧调用点：返回当前可下载的 progressive MP4，而不是播放器 DASH 分片。 */
        var latestVideoUrl: String?
            get() = MediaCache.bestVideoUrl()
            set(_) {}

        var graphqlVideoUrl: String?
            get() = MediaCache.bestVideoUrl()
            set(_) {}

        fun inspectJsonForVideoUrls(json: String) {
            MediaCache.ingestJson(json)
        }
    }

    override fun init(classLoader: ClassLoader, context: Context) {
        this.appContext = context
        try {
            hookActivityLifecycle()
            hookOkHttpVideoRequests(classLoader)
            hookExoPlayer(classLoader)
            hookPlayerLongPress()
            hookClipboard()
        } catch (e: Throwable) {
            Logger.e("Failed to initialize MediaDownloadHook", e)
        }
    }

    private fun isDownloadEnabled(): Boolean {
        val ctx = appContext ?: return true
        return ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true)
    }

    private fun hookActivityLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        currentActivity = param.thisObject as? Activity
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 只从播放请求里取出 mediaId，用来对回 GraphQL 完整 MP4。
     * 不把 DASH 分片 URL 存成下载地址。
     */
    private fun hookOkHttpVideoRequests(classLoader: ClassLoader) {
        try {
            val realCallClass = classLoader.loadClass("okhttp3.internal.connection.RealCall")
            XposedBridge.hookAllMethods(realCallClass, "getResponseWithInterceptorChain", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val req = XposedHelpers.getObjectField(param.thisObject, "originalRequest")
                            ?: XposedHelpers.getObjectField(param.thisObject, "request")
                        val urlObj = XposedHelpers.callMethod(req, "url")
                        val urlStr = urlObj?.toString() ?: return
                        notePlaybackIfVideo(urlStr)
                    } catch (_: Throwable) {
                    }
                }
            })
            Logger.i("Hooked OkHttp RealCall to identify playing media id")
        } catch (e: Throwable) {
            Logger.w("RealCall video URL capture hook failed: ${e.message}")
        }

        try {
            val clientClass = classLoader.loadClass("okhttp3.OkHttpClient")
            XposedBridge.hookAllMethods(clientClass, "newCall", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        HostOkHttp.captureClient(param.thisObject)
                        val req = param.args.getOrNull(0) ?: return
                        val urlObj = XposedHelpers.callMethod(req, "url")
                        val urlStr = urlObj?.toString() ?: return
                        notePlaybackIfVideo(urlStr)
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun notePlaybackIfVideo(urlStr: String) {
        if (urlStr.contains("video.twimg.com") ||
            urlStr.contains("ext_tw_video") ||
            urlStr.contains("amplify_video")
        ) {
            MediaCache.notePlaybackUrl(urlStr)
        }
    }

    private fun hookExoPlayer(classLoader: ClassLoader) {
        try {
            val exoClass = try {
                classLoader.loadClass("androidx.media3.exoplayer.ExoPlayer")
            } catch (_: ClassNotFoundException) {
                classLoader.loadClass("com.google.android.exoplayer2.ExoPlayer")
            }
            XposedBridge.hookAllMethods(exoClass, "setMediaItem", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    extractUriFromMediaItem(param.args.getOrNull(0))
                }
            })
            try {
                XposedBridge.hookAllMethods(exoClass, "setMediaItems", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val list = param.args.getOrNull(0) ?: return
                        if (list is List<*>) {
                            list.forEach { extractUriFromMediaItem(it) }
                        }
                    }
                })
            } catch (_: Throwable) {
            }
            Logger.i("ExoPlayer setMediaItem(s) hooked as playback-id capture.")
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
                val uriStr = uri?.toString() ?: return
                notePlaybackIfVideo(uriStr)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * X 12.x 播放器不是独立 Activity，全程 MainActivity。
     * 在 dispatchTouchEvent 里认大画面 Surface/Texture，长按弹出下载；不消费 DOWN，避免挡住单击进播放器。
     */
    private fun hookPlayerLongPress() {
        try {
            val dispatchTouchMethod = Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            XposedBridge.hookMethod(dispatchTouchMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isDownloadEnabled()) return
                    val event = param.args[0] as? MotionEvent ?: return
                    handlePlayerTouch(activity, event, param)
                }
            })
            Logger.i("Hooked Activity.dispatchTouchEvent for player long-press download")
        } catch (e: Throwable) {
            Logger.w("hookPlayerLongPress failed: ${e.message}")
        }
    }

    private fun handlePlayerTouch(activity: Activity, event: MotionEvent, param: XC_MethodHook.MethodHookParam) {
        val density = activity.resources.displayMetrics.density
        val slop = 24 * density
        // 避开顶栏 X 图标长按（那是设置入口）
        val headerGuard = 160 * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                longPressFired = false
                trackingSurface = false
                if (event.y < headerGuard) return

                val surface = findLargeVideoSurface(activity) ?: return
                if (!touchInside(surface, event)) return

                trackingSurface = true
                pressDownX = event.x
                pressDownY = event.y
                val runnable = Runnable {
                    longPressFired = true
                    mainHandler.post {
                        if (!activity.isFinishing) {
                            showDownloadChooser(activity)
                        }
                    }
                }
                longPressRunnable = runnable
                mainHandler.postDelayed(runnable, 480)
            }

            MotionEvent.ACTION_MOVE -> {
                if (trackingSurface && longPressRunnable != null && !longPressFired) {
                    val dx = Math.abs(event.x - pressDownX)
                    val dy = Math.abs(event.y - pressDownY)
                    if (dx > slop || dy > slop) {
                        cancelLongPress()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (longPressFired) {
                    param.result = true
                }
                trackingSurface = false
            }
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 全屏/详情播放器的画面通常占屏幕 32% 以上；瀑布流 inline 视频更小，避免误触。
     */
    private fun findLargeVideoSurface(activity: Activity): View? {
        val decor = activity.window?.decorView ?: return null
        val screenArea = activity.resources.displayMetrics.widthPixels.toLong() *
            activity.resources.displayMetrics.heightPixels.toLong()
        if (screenArea <= 0) return null
        val minArea = (screenArea * 0.32).toLong()
        var best: View? = null
        var bestArea = 0L

        fun scan(v: View) {
            if (v is SurfaceView || v is TextureView ||
                v.javaClass.name.contains("PlayerView", ignoreCase = true)
            ) {
                val area = v.width.toLong() * v.height.toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
        }
        scan(decor)
        return if (bestArea >= minArea) best else null
    }

    private fun touchInside(view: View, event: MotionEvent): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = event.rawX
        val y = event.rawY
        return x >= loc[0] && x <= loc[0] + view.width &&
            y >= loc[1] && y <= loc[1] + view.height
    }

    private fun hookClipboard() {
        try {
            XposedHelpers.findAndHookMethod(
                ClipboardManager::class.java, "setPrimaryClip", ClipData::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isDownloadEnabled()) return
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount == 0) return
                        val text = clip.getItemAt(0).text?.toString() ?: return
                        val tweetId = MediaExtractor.parseTweetIdFromShareUrl(text) ?: return
                        Logger.i("Clipboard: copied tweet link id=$tweetId")
                        MediaCache.noteTweetId(tweetId)
                        mainHandler.post {
                            val act = currentActivity
                            if (act != null && !act.isFinishing) {
                                showDownloadChooser(act, tweetId)
                            } else {
                                appContext?.let {
                                    Toast.makeText(it, "📥 已复制推文，点悬浮球⚙️可下载", Toast.LENGTH_LONG).show()
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

    private fun showDownloadChooser(activity: Activity, tweetId: String? = null) {
        if (activity.isFinishing) return
        val items = MediaCache.resolve(tweetId)
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("📥 下载推文媒体")
                .setMessage("还没有这条推文的完整视频直链。\n\n请先打开该推文等画面开始播放，或复制这条推文的链接后再试。\n（播放器内部的是 DASH 分片，不能直接保存。）")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (items.size == 1) {
            confirmDownload(activity, items[0])
            return
        }

        val labels = items.map { media ->
            if (media.isVideo) {
                val kbps = if (media.bitrate > 0) " ${media.bitrate / 1000}kbps" else ""
                "📥 视频$kbps"
            } else {
                "🖼️ 原图"
            }
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("📥 下载媒体")
            .setItems(labels) { _, which ->
                items.getOrNull(which)?.let { confirmDownload(activity, it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDownload(activity: Activity, media: ExtractedMedia) {
        val kind = if (media.isVideo) "视频" else "原图"
        AlertDialog.Builder(activity)
            .setTitle("📥 下载$kind")
            .setMessage("将保存到 ${if (media.isVideo) "Movies" else "Pictures"}/Twitter/\n${media.fileName}")
            .setPositiveButton("立即下载") { _, _ ->
                Downloader.download(activity, media)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
