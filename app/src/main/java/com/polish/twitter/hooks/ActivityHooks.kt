package com.polish.twitter.hooks

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.polish.twitter.core.Logger
import com.polish.twitter.core.XHost
import com.polish.twitter.ui.FloatingMenuManager
import com.polish.twitter.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

/**
 * Activity 基类上的唯一一组 Hook：当前 Activity 追踪、悬浮球挂载、触摸分发。
 * 以前 onResume / dispatchTouchEvent 被三个类各挂一遍，每次触摸跑多套逻辑；现在集中在这里分发。
 */
object ActivityHooks {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentRef: WeakReference<Activity>? = null

    val current: Activity? get() = currentRef?.get()

    fun install(playerLongPress: PlayerLongPress) {
        // onResume 是 protected，getMethod 只找 public，必须用 getDeclaredMethod。
        XposedBridge.hookMethod(Activity::class.java.getDeclaredMethod("onResume"), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                currentRef = WeakReference(activity)
                if (XHost.isHostActivity(activity.javaClass.name)) {
                    activity.window?.decorView?.post { FloatingMenuManager.attach(activity) }
                }
            }
        })

        val dispatchTouch = Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java)
        XposedBridge.hookMethod(dispatchTouch, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val event = param.args[0] as? MotionEvent ?: return
                val consumed = try {
                    LogoLongPress.onTouch(activity, event) or playerLongPress.onTouch(activity, event)
                } catch (t: Throwable) {
                    Logger.d("Touch dispatch failed: ${t.message}")
                    false
                }
                if (consumed) param.result = true
            }
        })
        Logger.i("ActivityHooks installed (onResume + dispatchTouchEvent)")
    }

    /** 主界面顶栏中央 X 图标长按 → 设置。不做双击，避免与 X 自带的「点顶栏回到顶部」冲突。 */
    private object LogoLongPress {
        private var pending: Runnable? = null
        private var fired = false
        private var downX = 0f
        private var downY = 0f

        fun onTouch(activity: Activity, event: MotionEvent): Boolean {
            if (!XHost.isMainActivity(activity.javaClass.name)) return false
            val density = activity.resources.displayMetrics.density
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    fired = false
                    val centerX = activity.resources.displayMetrics.widthPixels / 2f
                    val inLogo = Math.abs(event.x - centerX) <= 100 * density && event.y in 0f..XHost.headerHeightPx(density)
                    if (!inLogo) return false
                    downX = event.x
                    downY = event.y
                    val runnable = Runnable {
                        fired = true
                        haptic(activity)
                        SettingsDialog.show(activity)
                    }
                    pending = runnable
                    mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(activity).scaledTouchSlop
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) cancel()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancel()
                    // 长按已弹设置：吞掉 UP，避免松手又触发 X 图标的点击。
                    return fired.also { fired = false }
                }
            }
            return false
        }

        private fun cancel() {
            pending?.let { mainHandler.removeCallbacks(it) }
            pending = null
        }

        private fun haptic(activity: Activity) {
            try {
                val vibrator = activity.getSystemService(Vibrator::class.java)
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (_: Throwable) {
            }
        }
    }

    interface PlayerLongPress {
        /** 返回 true 表示本事件已被长按下载消费。 */
        fun onTouch(activity: Activity, event: MotionEvent): Boolean
    }
}
