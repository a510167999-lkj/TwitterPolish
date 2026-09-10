package com.polish.twitter.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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
import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.utils.DownloadResolver
import com.polish.twitter.utils.Downloader
import com.polish.twitter.utils.HostOkHttp
import com.polish.twitter.utils.PlayerPlaybackTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

class MediaDownloadHook : BaseHook() {

    override val name: String = "MediaDownloadHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    // 用 WeakReference 避免持有 Activity 导致泄漏（Xposed Hook 实例进程常驻）
    private var currentActivityRef: WeakReference<Activity>? = null
    private val currentActivity get() = currentActivityRef?.get()

    private var pressDownX = 0f
    private var pressDownY = 0f
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null
    private var trackingSurface = false

    // 下载开关缓存：触摸事件高频回调里不再每次读 SharedPreferences，
    // 由监听器即时刷新。SharedPreferences 只持 WeakReference，必须留强引用。
    @Volatile
    private var downloadEnabled = true
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        this.appContext = context
        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            downloadEnabled = prefs.getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Constants.PREF_ENABLE_MEDIA_DOWNLOAD) {
                    downloadEnabled = prefs.getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true)
                }
            }
            prefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        } catch (_: Throwable) {
        }

        try {
            hookActivityLifecycle()
            PlayerPlaybackTracker.hook(classLoader)
            hookOkHttpVideoRequests(classLoader)
            hookExoPlayer(classLoader)
            hookMedia3DataSource(classLoader)
            hookPlayerLongPress()
            hookClipboard()
        } catch (e: Throwable) {
            Logger.e("Failed to initialize MediaDownloadHook", e)
        }
    }

    private fun hookActivityLifecycle() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        currentActivityRef = WeakReference(param.thisObject as? Activity ?: return)
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
            MediaCache.notePlaybackUrl(urlStr, fromPlayer = false)
            // 只把 master 当下载源。正在播的 480p 变体 playlist 不能覆盖 GraphQL 最高清。
            if (HlsPlaylistParser.isMasterPlaylistUrl(urlStr)) {
                val mediaId = MediaExtractor.extractMediaId(urlStr) ?: return
                MediaCache.ingestObject(
                    org.json.JSONObject().put(
                        "video_info",
                        org.json.JSONObject().put(
                            "variants",
                            org.json.JSONArray().put(
                                org.json.JSONObject()
                                    .put("content_type", "application/x-mpegURL")
                                    .put("url", urlStr)
                            )
                        )
                    ).put("id_str", mediaId).put("rest_id", mediaId).put("__typename", "Tweet")
                )
            }
        }
    }

    private fun hookMedia3DataSource(classLoader: ClassLoader) {
        val names = listOf(
            "androidx.media3.datasource.DefaultHttpDataSource",
            "androidx.media3.datasource.cronet.CronetDataSource",
            "com.google.android.exoplayer2.upstream.DefaultHttpDataSource"
        )
        for (name in names) {
            try {
                val clazz = classLoader.loadClass(name)
                XposedBridge.hookAllMethods(clazz, "open", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val spec = param.args.getOrNull(0) ?: return
                        val uri = try {
                            XposedHelpers.getObjectField(spec, "uri")
                        } catch (_: Throwable) {
                            try { XposedHelpers.callMethod(spec, "getUri") } catch (_: Throwable) { null }
                        } ?: return
                        notePlaybackIfVideo(uri.toString())
                    }
                })
                Logger.i("Hooked $name.open for HLS URL capture")
            } catch (_: Throwable) {
            }
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
                override fun afterHookedMethod(param: MethodHookParam) {
                    PlayerPlaybackTracker.attach(param.thisObject)
                    // 预取也会 setMediaItem，不能把 playlist 里下一条当成当前在播。
                }
            })
            try {
                XposedBridge.hookAllMethods(exoClass, "setMediaItems", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        PlayerPlaybackTracker.attach(param.thisObject)
                    }
                })
            } catch (_: Throwable) {
            }
            Logger.i("ExoPlayer setMediaItem(s) hooked as playback-id capture.")
        } catch (e: Throwable) {
            Logger.w("ExoPlayer hook failed (non-fatal): ${e.message}")
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
                    if (!downloadEnabled) return
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

                val surface = findTouchedVideoSurface(activity, event) ?: return

                trackingSurface = true
                pressDownX = event.x
                pressDownY = event.y
                val pressed = surface
                val runnable = Runnable {
                    longPressFired = true
                    mainHandler.post {
                        if (!activity.isFinishing) {
                            showDownloadChooser(activity, videoView = pressed)
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
     * 手指下面的视频画面，不是全局最大的 Surface。
     * ViewPager 里上一条/下一条往往同样大，按全局最大会绑到错的播放器。
     */
    private fun findTouchedVideoSurface(activity: Activity, event: MotionEvent): View? {
        val decor = activity.window?.decorView ?: return null
        val screenArea = activity.resources.displayMetrics.widthPixels.toLong() *
            activity.resources.displayMetrics.heightPixels.toLong()
        if (screenArea <= 0) return null
        val minArea = (screenArea * 0.18).toLong()
        val hits = mutableListOf<Pair<View, Long>>()

        fun isVideoLike(v: View): Boolean {
            return v is SurfaceView || v is TextureView ||
                v.javaClass.name.contains("PlayerView", ignoreCase = true)
        }

        fun scan(v: View) {
            if (v.isShown && isVideoLike(v) && v.width > 0 && v.height > 0) {
                val area = v.width.toLong() * v.height.toLong()
                if (area >= minArea && touchInside(v, event)) {
                    hits.add(v to area)
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) scan(v.getChildAt(i))
            }
        }
        scan(decor)
        if (hits.isEmpty()) return null
        // 同一触点上 PlayerView 包着 SurfaceView：取更小的那块，才能对上 setVideoSurfaceView。
        return hits.minBy { it.second }.first
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
                        if (!downloadEnabled) return
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

    private fun showDownloadChooser(
        activity: Activity,
        tweetId: String? = null,
        videoView: View? = null
    ) {
        if (activity.isFinishing) return
        val items = DownloadResolver.resolve(activity, tweetId, videoView)
        if (items.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("📥 下载推文媒体")
                .setMessage("还没拿到这条视频的播放列表。\n请先让视频播放几秒再长按，模块会从 HLS 流合成完整 MP4。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (items.size == 1) {
            confirmDownload(activity, items[0])
            return
        }

        // 混合帖子（视频+多图同推文）：第一项“全部下载”，之后逐项列出
        val labels = mutableListOf<String>()
        labels.add("📥 全部下载（${items.size} 项）")
        items.forEach { media ->
            if (media.isVideo) {
                if (MediaExtractor.isHlsPlaylistUrl(media.url)) labels.add("📥 合成下载最高清视频")
                else {
                    val kbps = if (media.bitrate > 0) " ${media.bitrate / 1000}kbps" else ""
                    labels.add("📥 视频$kbps")
                }
            } else {
                labels.add("🖼️ 原图")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("📥 下载媒体")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    // 全部下载：每项独立通知、互不阻塞
                    items.forEach { Downloader.download(activity, it) }
                } else {
                    items.getOrNull(which - 1)?.let { confirmDownload(activity, it) }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDownload(activity: Activity, media: ExtractedMedia) {
        val kind = if (media.isVideo) "视频" else "原图"
        val extra = if (media.isVideo && MediaExtractor.isHlsPlaylistUrl(media.url)) {
            "\n将从播放列表选取最高分辨率并合成完整 MP4（含音轨）。"
        } else ""
        AlertDialog.Builder(activity)
            .setTitle("📥 下载$kind")
            .setMessage("将保存到 ${if (media.isVideo) "Movies" else "Pictures"}/Twitter/\n${media.fileName}$extra")
            .setPositiveButton("立即下载") { _, _ ->
                Downloader.download(activity, media)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
