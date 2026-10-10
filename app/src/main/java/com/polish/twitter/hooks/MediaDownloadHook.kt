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
import android.view.ViewConfiguration
import android.view.ViewGroup
import com.polish.twitter.core.Logger
import com.polish.twitter.core.Settings
import com.polish.twitter.core.XHost
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.HlsPlaylistParser
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.utils.DownloadResolver
import com.polish.twitter.utils.DownloadSecurity
import com.polish.twitter.utils.Downloader
import com.polish.twitter.utils.PlayerPlaybackTracker
import dev.polish.ui.PolishUi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class MediaDownloadHook : BaseHook(), ActivityHooks.PlayerLongPress {

    override val name: String = "MediaDownloadHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var pressDownX = 0f
    private var pressDownY = 0f
    private var pressRawX = 0f
    private var pressRawY = 0f
    private var longPressFired = false
    private var longPressRunnable: Runnable? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            PlayerPlaybackTracker.hook(classLoader)
            hookMedia3DataSource(classLoader)
            hookClipboard()
        } catch (e: Throwable) {
            Logger.e("Failed to initialize MediaDownloadHook", e)
        }
    }

    /** 由 [NetworkTimelineHook] 的统一请求 Hook 回调：只从播放请求里取出 mediaId。 */
    fun notePlaybackIfVideo(urlStr: String) {
        if (DownloadSecurity.isVideoTwimgUrl(urlStr)) {
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

    /**
     * X 12.x 播放器不是独立 Activity，全程 MainActivity。
     * 长按画面弹出下载；不消费 DOWN，避免挡住单击进播放器。
     * 找画面要遍历 View 树，只在长按真正触发时做一次，不在每次按下时做。
     */
    override fun onTouch(activity: Activity, event: MotionEvent): Boolean {
        if (!Settings.mediaDownload) return false
        val density = activity.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                longPressFired = false
                // 避开顶栏 X 图标长按（那是设置入口）
                if (event.y < XHost.headerHeightPx(density)) return false
                pressDownX = event.x
                pressDownY = event.y
                pressRawX = event.rawX
                pressRawY = event.rawY
                val runnable = Runnable {
                    longPressRunnable = null
                    if (activity.isFinishing) return@Runnable
                    val surface = findTouchedVideoSurface(activity, pressRawX, pressRawY) ?: return@Runnable
                    longPressFired = true
                    showDownloadChooser(activity, videoView = surface)
                }
                longPressRunnable = runnable
                mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (longPressRunnable != null) {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (Math.abs(event.x - pressDownX) > slop || Math.abs(event.y - pressDownY) > slop) {
                        cancelLongPress()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                return longPressFired.also { longPressFired = false }
            }
        }
        return false
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 手指下面的视频画面，不是全局最大的 Surface。
     * ViewPager 里上一条/下一条往往同样大，按全局最大会绑到错的播放器。
     */
    private fun findTouchedVideoSurface(activity: Activity, rawX: Float, rawY: Float): View? {
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
                if (area >= minArea && touchInside(v, rawX, rawY)) {
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

    private fun touchInside(view: View, x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width &&
            y >= loc[1] && y <= loc[1] + view.height
    }

    /**
     * 复制推文链接时，若这条推文已在缓存里有媒体就弹下载选择。
     * 纯文字推文或还没刷到的推文不弹：复制链接是高频操作，不能每次都打断。
     */
    private fun hookClipboard() {
        try {
            XposedHelpers.findAndHookMethod(
                ClipboardManager::class.java, "setPrimaryClip", ClipData::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!Settings.mediaDownload) return
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount == 0) return
                        val text = clip.getItemAt(0).text?.toString() ?: return
                        val tweetId = MediaExtractor.parseTweetIdFromShareUrl(text) ?: return
                        val items = MediaCache.forTweet(tweetId)
                        Logger.d("Clipboard: tweet link copied, cached media=${items.size}")
                        if (items.isEmpty()) return
                        mainHandler.post {
                            val act = ActivityHooks.current
                            if (act != null && !act.isFinishing) showChooser(act, items)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.w("ClipboardManager hook failed", e)
        }
    }

    private fun showDownloadChooser(activity: Activity, videoView: View) {
        if (activity.isFinishing) return
        val items = DownloadResolver.resolve(activity, videoView = videoView)
        if (items.isEmpty()) {
            AlertDialog.Builder(PolishUi.themed(activity))
                .setTitle("📥 下载推文媒体")
                .setMessage("没认出手指下这条视频。\n请让视频播放起来后再长按；为免下错视频，模块不会猜。")
                .setPositiveButton("知道了", null)
                .show().also { PolishUi.styleDialog(it) }
            return
        }
        showChooser(activity, items)
    }

    private fun showChooser(activity: Activity, items: List<ExtractedMedia>) {
        if (activity.isFinishing) return
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

        AlertDialog.Builder(PolishUi.themed(activity))
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
            .show().also { PolishUi.styleDialog(it) }
    }

    private fun confirmDownload(activity: Activity, media: ExtractedMedia) {
        val kind = if (media.isVideo) "视频" else "原图"
        val extra = if (media.isVideo && MediaExtractor.isHlsPlaylistUrl(media.url)) {
            "\n将从播放列表选取最高分辨率并合成完整 MP4（含音轨）。"
        } else ""
        AlertDialog.Builder(PolishUi.themed(activity))
            .setTitle("📥 下载$kind")
            .setMessage("将保存到 ${if (media.isVideo) "Movies" else "Pictures"}/Twitter/\n${media.fileName}$extra")
            .setPositiveButton("立即下载") { _, _ ->
                Downloader.download(activity, media)
            }
            .setNegativeButton("取消", null)
            .show().also { PolishUi.styleDialog(it) }
    }
}
