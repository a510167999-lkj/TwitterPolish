package com.polish.twitter.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import com.polish.twitter.utils.DownloadResolver
import com.polish.twitter.utils.Downloader
import dev.polish.ui.PolishUi

object FloatingMenuManager {

    /**
     * 按 Activity 弱引用跟踪已挂载的悬浮球：
     * 解决原先单引用下 Activity 旋转/重建后悬浮球脱离管理、
     * 新 Activity 上无法重新挂载的问题。
     */
    private val attachedByActivity = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, View>()
    )

    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

        val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.PREF_SHOW_FLOATING_BALL, true)
        if (!isEnabled) {
            remove()
            return
        }

        val decorView = activity.window.decorView as? ViewGroup ?: return

        // 清理已销毁/已卸载 Activity 上遗留的悬浮球，避免脏视图滞留
        synchronized(attachedByActivity) {
            val it = attachedByActivity.entries.iterator()
            while (it.hasNext()) {
                val (host, view) = it.next()
                if (host !== activity &&
                    (host.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed) || view.parent == null)
                ) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    it.remove()
                }
            }
        }

        val existing = attachedByActivity[activity]
        if (existing != null && existing.parent === decorView && decorView.indexOfChild(existing) >= 0) {
            return
        }
        // 同 Activity 再次挂载：先摘掉旧的（例如 decorView 重建后）
        (existing?.parent as? ViewGroup)?.removeView(existing)

        val dp = activity.resources.displayMetrics.density
        val sizePx = (48 * dp).toInt()

        val floatingBtn = TextView(activity).apply {
            text = "P"
            contentDescription = "TwitterPolish 快捷菜单"
            textSize = 20f
            setTextColor(PolishUi.ACCENT)
            gravity = Gravity.CENTER
            background = PolishUi.shape(activity, PolishUi.SURFACE, 18f, PolishUi.BORDER)
            elevation = 25 * dp

            var initialX = 0f
            var initialY = 0f
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false

            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = view.x
                        initialY = view.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 * dp || Math.abs(dy) > 10 * dp) {
                            isMoving = true
                            view.x = initialX + dx
                            view.y = initialY + dy
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            showQuickActionDialog(activity)
                        } else {
                            // 吸附到屏幕左或右边缘
                            val screenWidth = activity.resources.displayMetrics.widthPixels
                            val targetX = if (view.x + view.width / 2f > screenWidth / 2f) {
                                (screenWidth - view.width - 8 * dp)
                            } else {
                                (8 * dp)
                            }
                            view.animate().x(targetX).setDuration(200).start()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val screenHeight = activity.resources.displayMetrics.heightPixels

        val layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = screenWidth - sizePx - (12 * dp).toInt()
            topMargin = (screenHeight * 0.45f).toInt()
        }

        try {
            decorView.addView(floatingBtn, layoutParams)
            attachedByActivity[activity] = floatingBtn
            Logger.d("Attached draggable floating ball to decorView.")
        } catch (e: Throwable) {
            Logger.w("Failed to attach floating ball", e)
        }
    }

    /** 移除所有 Activity 上的悬浮球（关闭开关 / 隐藏入口调用）。 */
    fun remove() {
        synchronized(attachedByActivity) {
            val it = attachedByActivity.entries.iterator()
            while (it.hasNext()) {
                val (_, view) = it.next()
                try {
                    (view.parent as? ViewGroup)?.removeView(view)
                } catch (_: Throwable) {
                }
                it.remove()
            }
        }
    }

    private fun showQuickActionDialog(activity: Activity) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val mediaItems = DownloadResolver.resolve(activity)
        val videos = mediaItems.filter { it.isVideo }
        val photos = mediaItems.filter { !it.isVideo }

        // 混合帖子：视频与图片逐项列出 + 全部下载
        if (mediaItems.size > 1) {
            options.add("📥 全部下载（${mediaItems.size} 项）")
            actions.add {
                mediaItems.forEach { Downloader.download(activity, it) }
            }
        }

        videos.forEachIndexed { index, video ->
            options.add("📥 下载当前视频${if (videos.size > 1) " ${index + 1}" else ""}")
            actions.add {
                Downloader.download(activity, video)
            }
        }

        photos.forEachIndexed { index, photo ->
            options.add("🖼️ 下载原图 ${index + 1}")
            actions.add {
                Downloader.download(activity, photo)
            }
        }

        options.add("⚙️ 打开 TwitterPolish 设置")
        actions.add {
            SettingsDialog.show(activity)
        }

        options.add("❌ 隐藏此悬浮球")
        actions.add {
            val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(Constants.PREF_SHOW_FLOATING_BALL, false).apply()
            remove()
            Toast.makeText(activity, "悬浮球已隐藏，如需再次开启可长按顶部 X 进入设置", Toast.LENGTH_LONG).show()
        }

        val dialog = AlertDialog.Builder(PolishUi.themed(activity))
            .setTitle("TwitterPolish 快捷菜单")
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
        PolishUi.styleDialog(dialog)
    }
}
