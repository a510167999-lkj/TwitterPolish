package com.polish.twitter.hooks

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.MotionEvent
import com.polish.twitter.core.Logger
import com.polish.twitter.ui.FloatingMenuManager
import com.polish.twitter.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class LogoLongPressHook : BaseHook() {

    override val name: String = "LogoLongPressHook"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var lastVolumeDownTime = 0L

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            hookActivityLifecycle(classLoader)
            hookActivityTouchAndKey()
        } catch (e: Throwable) {
            Logger.e("Failed to initialize LogoLongPressHook", e)
        }
    }

    private fun hookActivityLifecycle(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        if (name.contains("MainActivity") || name.contains("Twitter") || name.contains(".x.")) {
                            Logger.d("Activity onResume detected: $name, attaching floating ball...")
                            activity.runOnUiThread {
                                FloatingMenuManager.attach(activity)
                            }
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hasFocus = param.args[0] as? Boolean ?: false
                        if (!hasFocus) return
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        if (name.contains("MainActivity") || name.contains("Twitter") || name.contains(".x.")) {
                            activity.runOnUiThread {
                                FloatingMenuManager.attach(activity)
                            }
                        }
                    }
                }
            )

            Logger.i("Successfully hooked Activity.onResume & onWindowFocusChanged for FloatingMenuManager")
        } catch (e: Throwable) {
            Logger.e("Failed to hook Activity lifecycle", e)
        }
    }

    /**
     * 直接在 android.app.Activity 基类上挂载触控和按键拦截，杜绝子类未重写导致的漏 Hook
     */
    private fun hookActivityTouchAndKey() {
        try {
            var downX = 0f
            var downY = 0f
            var isLongPressTriggered = false
            var longPressRunnable: Runnable? = null
            var lastTapTime = 0L

            // 1. 触控拦截：双击或长按顶栏 X 图标
            val dispatchTouchMethod = Activity::class.java.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            XposedBridge.hookMethod(dispatchTouchMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != "com.x.android.main.MainActivity") return

                    val event = param.args[0] as? MotionEvent ?: return
                    val density = activity.resources.displayMetrics.density
                    val screenWidth = activity.resources.displayMetrics.widthPixels
                    val centerX = screenWidth / 2f
                    val halfLogoWidth = 100 * density
                    val maxHeaderY = 160 * density
                    val slop = 30 * density

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            isLongPressTriggered = false

                            val isInsideTopLogo = (downX >= (centerX - halfLogoWidth) && downX <= (centerX + halfLogoWidth)) &&
                                    (downY >= 0 && downY <= maxHeaderY)

                            if (isInsideTopLogo) {
                                val now = System.currentTimeMillis()
                                if ((now - lastTapTime) < 350) {
                                    triggerHaptic(activity)
                                    SettingsDialog.show(activity)
                                    lastTapTime = 0L
                                    param.result = true
                                    return
                                }
                                lastTapTime = now

                                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                val runnable = Runnable {
                                    isLongPressTriggered = true
                                    triggerHaptic(activity)
                                    SettingsDialog.show(activity)
                                }
                                longPressRunnable = runnable
                                mainHandler.postDelayed(runnable, 380)
                            }
                        }

                        MotionEvent.ACTION_MOVE -> {
                            if (longPressRunnable != null && !isLongPressTriggered) {
                                val dx = Math.abs(event.x - downX)
                                val dy = Math.abs(event.y - downY)
                                if (dx > slop || dy > slop) {
                                    mainHandler.removeCallbacks(longPressRunnable!!)
                                    longPressRunnable = null
                                }
                            }
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressRunnable?.let {
                                mainHandler.removeCallbacks(it)
                                longPressRunnable = null
                            }
                            if (isLongPressTriggered) {
                                param.result = true
                            }
                        }
                    }
                }
            })

            // 2. 硬件按键备选兜底：连续按两次音量减键（400ms 内）呼出设置
            val dispatchKeyMethod = Activity::class.java.getMethod("dispatchKeyEvent", KeyEvent::class.java)
            XposedBridge.hookMethod(dispatchKeyMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val event = param.args[0] as? KeyEvent ?: return

                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        val now = System.currentTimeMillis()
                        if (now - lastVolumeDownTime < 450) {
                            triggerHaptic(activity)
                            SettingsDialog.show(activity)
                            lastVolumeDownTime = 0L
                            param.result = true
                            return
                        }
                        lastVolumeDownTime = now
                    }
                }
            })

            Logger.i("Successfully hooked Activity.dispatchTouchEvent & dispatchKeyEvent")
        } catch (e: Throwable) {
            Logger.e("Failed to hook Activity touch/key methods", e)
        }
    }

    private fun triggerHaptic(context: Context) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (_: Throwable) {
        }
    }
}
