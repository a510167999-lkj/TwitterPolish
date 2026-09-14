package com.polish.twitter.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.core.XHost
import com.polish.twitter.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Proxy

class SettingsHook : BaseHook() {

    override val name: String = "SettingsHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var currentActivity: Activity? = null

    /** 悬浮入口按钮。记录其当前宿主 decorView，Activity 重建后可正确重新挂载。 */
    private var floatingSettingsButton: View? = null
    private var floatingButtonHost: ViewGroup? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            hookMainActivityLifecycle(classLoader)
            installDiscovered(classLoader)
        } catch (e: Throwable) {
            Logger.e("Failed to initialize SettingsHook", e)
        }
    }

    private fun hookMainActivityLifecycle(classLoader: ClassLoader) {
        for (name in XHost.MAIN_ACTIVITY_CANDIDATES) {
            try {
                val clazz = classLoader.loadClass(name)
                XposedBridge.hookAllMethods(clazz, "onResume", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        currentActivity = param.thisObject as? Activity
                    }
                })
            } catch (_: ClassNotFoundException) {
            }
        }
    }

    private var discoveredInstalled = false

    @Synchronized
    fun installDiscovered(classLoader: ClassLoader) {
        if (discoveredInstalled) return
        val method = DexKitManager.getMethod(DexKitManager.KEY_SETTINGS_ROOT_CLASS, classLoader) ?: return
        // 只挂载已验证渲染方法；不再覆盖不确定含义的宿主 Function0 回调。
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                mainHandler.post {
                    val activity = currentActivity ?: return@post
                    showSettingsFloatingButton(activity)
                }
            }
        })
        discoveredInstalled = true
        Logger.i("Hooked validated settings entry: $method")
    }

    private fun showSettingsFloatingButton(activity: Activity) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return
        val decorView = activity.window.decorView as? ViewGroup ?: return

        // 同一 Activity 已挂载则跳过；宿主变了（Activity 重建/切换）则先卸载旧的再挂新的
        if (floatingSettingsButton?.parent === decorView) {
            return
        }
        removeFloatingButton()

        val dp = activity.resources.displayMetrics.density
        val btn = TextView(activity).apply {
            text = "✨ TwitterPolish 设置"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())

            // 胶囊背景
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1D9BF0")) // Twitter 经典品牌蓝
                cornerRadius = 20 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#40FFFFFF"))
            }
            background = bg
            elevation = 16 * dp

            setOnClickListener {
                SettingsDialog.show(activity)
            }
        }

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (32 * dp).toInt()
        }

        try {
            decorView.addView(btn, layoutParams)
            floatingSettingsButton = btn
            floatingButtonHost = decorView
            Logger.d("Added native TwitterPolish entry button to Settings screen.")
        } catch (e: Throwable) {
            Logger.w("Failed to add floating button to decorView", e)
        }
    }

    private fun removeFloatingButton() {
        try {
            val btn = floatingSettingsButton
            val host = floatingButtonHost
            if (btn != null && host != null) {
                if (btn.parent === host) host.removeView(btn)
            }
        } catch (_: Throwable) {
        }
        floatingSettingsButton = null
        floatingButtonHost = null
    }
}
