package com.polish.twitter

import android.app.Application
import android.content.Context
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.core.Settings
import com.polish.twitter.hooks.ActivityHooks
import com.polish.twitter.hooks.HomeTabHook
import com.polish.twitter.hooks.MediaDownloadHook
import com.polish.twitter.hooks.NetworkTimelineHook
import com.polish.twitter.utils.PlayerPlaybackTracker
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
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
                        onContextAvailable(param.args[0] as Context, lpparam.classLoader)
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
            Settings.init(context)

            // 不依赖扫描的网络、播放、入口立即安装，避免错过宿主首个页面。
            val media = MediaDownloadHook()
            val network = NetworkTimelineHook(media::notePlaybackIfVideo)
            install("ActivityHooks") { ActivityHooks.install(media) }
            install(network.name) { network.init(classLoader, context) }
            install(media.name) { media.init(classLoader, context) }

            // 首页标签依赖混淆类，必须等 DexKit 结果；首页在 Application 之后才创建，来得及。
            DexKitManager.init(context, classLoader) {
                install("network (DexKit)") { network.installDiscovered(classLoader) }
                install("HomeTabHook") { HomeTabHook.installDiscovered(classLoader) }
                install("PlayerTracker (DexKit)") {
                    DexKitManager.exoPlayerImpl(classLoader)?.let { PlayerPlaybackTracker.hookImplClass(it) }
                }
                Logger.i("DexKit pass done: ${DexKitManager.status}")
            }
        } catch (e: Throwable) {
            contextReady.set(false)
            Logger.e("Error during TwitterPolish context initialization", e)
        }
    }

    /** 单个功能失败不阻塞其余功能。 */
    private inline fun install(name: String, block: () -> Unit) {
        try {
            block()
            Logger.i("Installed $name")
        } catch (t: Throwable) {
            Logger.e("Failed to install $name", t)
        }
    }
}
