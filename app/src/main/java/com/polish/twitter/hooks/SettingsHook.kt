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
import com.polish.twitter.core.Logger
import com.polish.twitter.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class SettingsHook : BaseHook() {

    override val name: String = "SettingsHook"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var currentActivity: Activity? = null
    private var floatingSettingsButton: View? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            hookMainActivityLifecycle(classLoader)
            hookSettingsClasses(classLoader)
        } catch (e: Throwable) {
            Logger.e("Failed to initialize SettingsHook", e)
        }
    }

    private fun hookMainActivityLifecycle(classLoader: ClassLoader) {
        val candidateActivities = listOf(
            "com.x.android.main.MainActivity",
            "com.twitter.app.main.MainActivity",
            "com.twitter.android.MainActivity"
        )

        for (name in candidateActivities) {
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

    private fun hookSettingsClasses(classLoader: ClassLoader) {
        try {
            val settingsAClass = classLoader.loadClass("com.x.settings.a")

            // 1. Hook Settings 根页面渲染方法 (com.x.settings.a.b)
            // 当用户进入“设置与隐私”时，在屏幕右上角/右侧自动贴附一个精致的“✨ 插件设置”快捷悬浮按钮
            XposedBridge.hookAllMethods(settingsAClass, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    mainHandler.post {
                        val activity = currentActivity ?: return@post
                        showSettingsFloatingButton(activity)
                    }
                }
            })

            // 2. Hook Settings 页脚版本号渲染与点击 (com.x.settings.a.a)
            // 用户在“设置与隐私”或“其他资源”中点击底部的版本号即可直接调起设置弹窗（兼容 TwiFucker 经典交互）
            XposedBridge.hookAllMethods(settingsAClass, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 替换 onUpdateClick 回调
                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg != null && arg.javaClass.name.contains("Function0")) {
                            param.args[i] = object : kotlin.jvm.functions.Function0<Any?> {
                                override fun invoke(): Any? {
                                    mainHandler.post {
                                        val activity = currentActivity ?: return@post
                                        SettingsDialog.show(activity)
                                    }
                                    return null
                                }
                            }
                        }
                    }
                }
            })

            Logger.i("Successfully hooked com.x.settings.a for native settings menu entry.")
        } catch (e: Throwable) {
            Logger.d("com.x.settings.a not found or hook failed: ${e.message}")
        }
    }

    private fun showSettingsFloatingButton(activity: Activity) {
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return
        val decorView = activity.window.decorView as? ViewGroup ?: return

        // 避免重复添加
        if (floatingSettingsButton != null && floatingSettingsButton?.parent != null) {
            return
        }

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
            Logger.d("Added native TwitterPolish entry button to Settings screen.")
        } catch (e: Throwable) {
            Logger.w("Failed to add floating button to decorView", e)
        }
    }
}
