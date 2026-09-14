package com.polish.twitter

import android.app.Application
import android.content.Context
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
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
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : IXposedHookLoadPackage {

    private val contextReady = AtomicBoolean(false)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Constants.TARGET_PACKAGE) {
            return
        }

        // 只接管主进程：子进程（推送/后台等）同样持有目标包名，
        // 若全套初始化会在每个进程触发昂贵的 DexKit 扫描与无意义的 UI Hook。
        if (lpparam.processName != lpparam.packageName) {
            Logger.d("TwitterPolish skip sub-process: ${lpparam.processName}")
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
        if (!contextReady.compareAndSet(false, true)) {
            return
        }
        try {
            Logger.i("Application context initialized. Starting DexKit and Hook subsystems...")

            val network = NetworkTimelineHook()
            val media = MediaDownloadHook()
            val settings = SettingsHook()
            // 不依赖扫描的网络、播放、快捷入口立即安装，避免错过宿主首个页面。
            val activeHooks = listOf(network, media, TabSwitcherHook(), LogoLongPressHook(), settings)

            // 3. 依次初始化并注册各业务 Hook（单个失败不阻塞其余）
            for (hook in activeHooks) {
                try {
                    Logger.d("Initializing Hook: ${hook.name}...")
                    hook.init(classLoader, context)
                    Logger.i("Hook ${hook.name} initialized successfully.")
                } catch (t: Throwable) {
                    Logger.e("Failed to initialize Hook: ${hook.name}", t)
                }
            }

            DexKitManager.init(context, classLoader) {
                val deferred = listOf<Pair<String, () -> Unit>>(
                    "network" to { network.installDiscovered(classLoader) },
                    "timeline model" to { TimelineFilterHook().init(classLoader, context) },
                    "player" to { com.polish.twitter.utils.PlayerPlaybackTracker.installDiscovered(classLoader) },
                    "settings" to { settings.installDiscovered(classLoader) }
                )
                deferred.forEach { (name, install) ->
                    try { install() } catch (error: Throwable) { Logger.e("Deferred $name hook failed", error) }
                }
                Logger.i("DexKit installation pass completed; runtime behavior remains unverified.")
            }
        } catch (e: Throwable) {
            contextReady.set(false)
            Logger.e("Error during TwitterPolish context initialization", e)
        }
    }
}
