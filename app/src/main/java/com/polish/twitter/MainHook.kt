package com.polish.twitter

import android.app.Application
import android.content.Context
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.hooks.BaseHook
import com.polish.twitter.hooks.LogoLongPressHook
import com.polish.twitter.hooks.MediaDownloadHook
import com.polish.twitter.hooks.NetworkTimelineHook
import com.polish.twitter.hooks.SettingsHook
import com.polish.twitter.hooks.TabSwitcherHook
import com.polish.twitter.hooks.TimelineFilterHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    private var hooks: List<BaseHook>? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.TARGET_PACKAGE) {
            return
        }

        Logger.i("TwitterPolish injected into process: ${lpparam.processName} (Package: ${lpparam.packageName})")

        try {
            // Hook Application.attach(Context) 以安全获取目标应用的真实 Context
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as Context
                        val classLoader = lpparam.classLoader

                        onContextAvailable(context, classLoader)
                    }
                }
            )
        } catch (e: Throwable) {
            Logger.e("Failed to hook Application.attach", e)
        }
    }

    private fun onContextAvailable(context: Context, classLoader: ClassLoader) {
        try {
            Logger.i("Application context initialized. Starting DexKit and Hook subsystems...")

            // 1. 动态初始化 DexKit 并加载/更新符号缓存
            DexKitManager.init(context, classLoader)

            // 2. 延迟在上下文就绪后初始化各个 Hook，杜绝类加载时 Handler 崩溃
            val activeHooks = listOf(
                NetworkTimelineHook(),
                TimelineFilterHook(),
                MediaDownloadHook(),
                TabSwitcherHook(),
                LogoLongPressHook(),
                SettingsHook()
            )
            hooks = activeHooks

            // 3. 依次初始化并注册各业务 Hook
            for (hook in activeHooks) {
                try {
                    Logger.d("Initializing Hook: ${hook.name}...")
                    hook.init(classLoader, context)
                    Logger.i("Hook ${hook.name} initialized successfully.")
                } catch (t: Throwable) {
                    Logger.e("Failed to initialize Hook: ${hook.name}", t)
                }
            }

            Logger.i("🎉 All TwitterPolish hooks registered.")
        } catch (e: Throwable) {
            Logger.e("Error during TwitterPolish context initialization", e)
        }
    }
}
