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
            hookSettingsClasses(classLoader)
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

    private fun hookSettingsClasses(classLoader: ClassLoader) {
        try {
            val settingsAClass = classLoader.loadClass(XHost.SETTINGS_ROOT_CLASS)

            // 1. Hook Settings 根页面渲染方法 (com.x.settings.a.b)
            // 当用户进入“设置与隐私”时，在屏幕底部贴附“✨ 插件设置”快捷按钮
            XposedBridge.hookAllMethods(settingsAClass, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    mainHandler.post {
                        val activity = currentActivity ?: return@post
                        showSettingsFloatingButton(activity)
                    }
                }
            })

            // 2. 点击设置页版本号（onUpdateClick 回调）调起插件设置弹窗。
            //    回调实例是任意 Kotlin lambda，不能用硬编码 Function0 类名匹配，
            //    改为识别“实现名为 *Function0 接口”的参数，并用动态代理替换为我们的回调：
            //    - 兼容 kotlin.jvm.functions.Function0 / kotlin.Function0 及不同 Kotlin 版本；
            //    - 不替换非函数参数。
            XposedBridge.hookAllMethods(settingsAClass, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (i in param.args.indices) {
                        val arg = param.args[i] ?: continue
                        if (isKotlinFunction0(arg)) {
                            param.args[i] = replaceWithSettingsCallback(arg, param.thisObject)
                        }
                    }
                }
            })

            Logger.i("Successfully hooked ${XHost.SETTINGS_ROOT_CLASS} for native settings menu entry.")
        } catch (e: Throwable) {
            Logger.d("Settings root class not found or hook failed: ${e.message}")
        }
    }

    /** 判断运行时对象是否为 Kotlin 函数类型（实现 *Function0）的实例。 */
    private fun isKotlinFunction0(arg: Any): Boolean {
        if (arg.javaClass.name.contains("Function0")) return true
        var c: Class<*>? = arg.javaClass
        while (c != null) {
            if (c.interfaces.any { it.name.contains("Function0") }) return true
            c = c.superclass
        }
        return false
    }

    /**
     * 用实现相同接口的动态代理替换原回调：invoke() 时跳转插件设置。
     * 其余 Object 方法（equals/hashCode/toString）正确代理，避免宿主逻辑异常。
     */
    private fun replaceWithSettingsCallback(original: Any, owner: Any?): Any {
        val loader = original.javaClass.classLoader ?: owner?.javaClass?.classLoader
            ?: SettingsHook::class.java.classLoader
        val interfaces = original.javaClass.interfaces
        return Proxy.newProxyInstance(loader, interfaces) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    mainHandler.post {
                        val activity = currentActivity ?: return@post
                        SettingsDialog.show(activity)
                    }
                    null
                }
                "toString" -> "TwitterPolishSettingsCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
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
