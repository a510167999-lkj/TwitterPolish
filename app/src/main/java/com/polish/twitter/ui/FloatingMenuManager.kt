package com.polish.twitter.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.utils.Downloader
import com.polish.twitter.utils.ExoCacheProbe

object FloatingMenuManager {

    private const val PREF_SHOW_FLOATING_BALL = "show_floating_ball"
    private var currentFloatingView: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

        val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(PREF_SHOW_FLOATING_BALL, true)
        if (!isEnabled) {
            remove()
            return
        }

        val decorView = activity.window.decorView as? ViewGroup ?: return
        if (currentFloatingView != null && currentFloatingView?.parent === decorView) {
            return
        }

        val dp = activity.resources.displayMetrics.density
        val sizePx = (46 * dp).toInt()

        val floatingBtn = TextView(activity).apply {
            text = "⚙️"
            textSize = 20f
            gravity = Gravity.CENTER

            // 晶莹黑色圆底 + Twitter 蓝边框
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E615202B"))
                setStroke((1.5f * dp).toInt(), Color.parseColor("#1D9BF0"))
            }
            background = bg
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
            currentFloatingView = floatingBtn
            Logger.d("Attached draggable floating ball to decorView.")
        } catch (e: Throwable) {
            Logger.w("Failed to attach floating ball", e)
        }
    }

    fun remove() {
        try {
            currentFloatingView?.let {
                val parent = it.parent as? ViewGroup
                parent?.removeView(it)
            }
            currentFloatingView = null
        } catch (_: Throwable) {}
    }

    private fun showQuickActionDialog(activity: Activity) {
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val mediaItems = MediaCache.resolve().toMutableList()
        if (mediaItems.none { it.isVideo }) {
            ExoCacheProbe.findCurrentHls(activity, MediaCache.currentMediaId)?.let {
                mediaItems.add(0, it)
            }
        }
        val videos = mediaItems.filter { it.isVideo }
        val photos = mediaItems.filter { !it.isVideo }

        if (videos.isNotEmpty()) {
            options.add("📥 下载当前视频")
            actions.add {
                Downloader.download(activity, videos.first())
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
            prefs.edit().putBoolean(PREF_SHOW_FLOATING_BALL, false).apply()
            remove()
            Toast.makeText(activity, "悬浮球已隐藏，如需再次开启可长按顶部 X 进入设置", Toast.LENGTH_LONG).show()
        }

        AlertDialog.Builder(activity)
            .setTitle("✨ TwitterPolish 快捷菜单")
            .setItems(options.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
