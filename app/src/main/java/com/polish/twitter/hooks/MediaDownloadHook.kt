package com.polish.twitter.hooks

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.MediaExtractor
import com.polish.twitter.utils.Downloader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class MediaDownloadHook : BaseHook() {

    override val name: String = "MediaDownloadHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private var currentActivity: Activity? = null

    companion object {
        @Volatile
        var latestMediaUrl: String? = null
            internal set

        @Volatile
        var latestVideoUrl: String? = null
            internal set
    }

    override fun init(classLoader: ClassLoader, context: Context) {
        this.appContext = context
        try {
            hookActivityLifecycle(classLoader)
            hookExoPlayer(classLoader)
            hookClipboard(classLoader)
        } catch (e: Throwable) {
            Logger.e("Failed to initialize MediaDownloadHook", e)
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity
                        if (act != null && (act.javaClass.name.contains("MainActivity") || act.javaClass.name.contains("Twitter"))) {
                            currentActivity = act
                        }
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    /**
     * 监控 ExoPlayer 播放事件，自动提取当前播放的最高清视频直链
     */
    private fun hookExoPlayer(classLoader: ClassLoader) {
        try {
            val exoPlayerClass = try {
                classLoader.loadClass("androidx.media3.exoplayer.ExoPlayer")
            } catch (_: ClassNotFoundException) {
                try {
                    classLoader.loadClass("com.google.android.exoplayer2.ExoPlayer")
                } catch (_: ClassNotFoundException) {
                    null
                }
            }

            if (exoPlayerClass != null) {
                XposedBridge.hookAllMethods(exoPlayerClass, "setMediaItem", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mediaItem = param.args.getOrNull(0) ?: return
                        try {
                            val localConfig = XposedHelpers.getObjectField(mediaItem, "localConfiguration")
                            if (localConfig != null) {
                                val uri = XposedHelpers.getObjectField(localConfig, "uri")
                                val uriStr = uri?.toString()
                                if (!uriStr.isNullOrBlank() && (uriStr.contains(".mp4") || uriStr.contains("video.twimg.com"))) {
                                    latestVideoUrl = uriStr
                                    latestMediaUrl = uriStr
                                    Logger.d("Captured active video URL from ExoPlayer: $uriStr")
                                }
                            }
                        } catch (t: Throwable) {
                            Logger.d("Failed to read MediaItem URI: ${t.message}")
                        }
                    }
                })
                Logger.i("Successfully hooked ExoPlayer for video download extraction.")
            }
        } catch (e: Throwable) {
            Logger.w("Failed to hook ExoPlayer", e)
        }
    }

    /**
     * Hook 剪贴板复制链接（点击推文分享 -> 复制链接触发）
     */
    private fun hookClipboard(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                ClipboardManager::class.java,
                "setPrimaryClip",
                ClipData::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clip = param.args[0] as? ClipData ?: return
                        if (clip.itemCount == 0) return
                        val text = clip.getItemAt(0).text?.toString() ?: return

                        if ((text.contains("x.com/") || text.contains("twitter.com/")) && text.contains("/status/")) {
                            Logger.i("Detected copied tweet link: $text")
                            mainHandler.post {
                                val act = currentActivity
                                if (act != null && !act.isFinishing) {
                                    showCopiedTweetDownloadDialog(act, text)
                                } else {
                                    val ctx = appContext ?: return@post
                                    Toast.makeText(ctx, "📥 已检测到推文链接，可通过屏幕悬浮球一键下载！", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.w("Failed to hook ClipboardManager", e)
        }
    }

    private fun showCopiedTweetDownloadDialog(activity: Activity, tweetUrl: String) {
        val videoUrl = latestVideoUrl
        val mediaUrl = latestMediaUrl

        val isVideo = !videoUrl.isNullOrBlank()
        val downloadTargetUrl = if (isVideo) videoUrl else mediaUrl

        val title = if (isVideo) "📥 下载推文 1080p 视频" else "📥 下载推文高清原图"
        val message = if (!downloadTargetUrl.isNullOrBlank()) {
            "已自动解析推文媒体资源！\n\n地址: ${downloadTargetUrl.take(60)}..."
        } else {
            "已捕获推文链接：\n$tweetUrl\n\n如推文内含视频，点击下方即可立即解析并下载。"
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("立即下载") { _, _ ->
                if (!downloadTargetUrl.isNullOrBlank()) {
                    val fileName = "twitter_${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}"
                    Downloader.download(activity, downloadTargetUrl, fileName, isVideo)
                } else {
                    Toast.makeText(activity, "正在后台获取媒体流，请播放视频或长按屏幕悬浮球...", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
