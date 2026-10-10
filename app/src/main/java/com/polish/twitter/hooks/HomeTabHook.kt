package com.polish.twitter.hooks

import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.core.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 默认进入「正在关注」。
 *
 * X 首页创建时调用 `initialTab(forced: Boolean)` 决定初始标签（12.33 为 com.x.home.tabbed.u.a）：
 * forced 为 true 时是外部指定了时间线（如深链），保持原样；否则读上次停留的标签。
 * 另有远程开关 `android_home_back_open_for_you_on_app_restart_enabled`（同类的 b()），
 * 后台超过 N 分钟回来会强制切回「为你推荐」。两处都要接管，只改一处会被另一处拉回去。
 */
object HomeTabHook {

    fun installDiscovered(classLoader: ClassLoader) {
        val initialTab = DexKitManager.getMethod(DexKitManager.KEY_HOME_INITIAL_TAB, classLoader)
        val restartForYou = DexKitManager.getMethod(DexKitManager.KEY_HOME_RESTART_FOR_YOU, classLoader)
        val following = DexKitManager.homeFollowingTab(classLoader)
        if (initialTab == null || following == null) {
            Logger.w("HomeTabHook not installed: initialTab=${initialTab != null} following=${following != null}")
            return
        }

        XposedBridge.hookMethod(initialTab, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Settings.defaultFollowing) return
                if (param.args.getOrNull(0) == true) return
                param.result = following
            }
        })
        Logger.i("HomeTabHook: initial tab -> Following via $initialTab")

        if (restartForYou == null) {
            Logger.w("HomeTabHook: restart-to-ForYou switch not found; X may still jump back after long background")
            return
        }
        XposedBridge.hookMethod(restartForYou, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (Settings.defaultFollowing) param.result = false
            }
        })
        Logger.i("HomeTabHook: restart-to-ForYou disabled via $restartForYou")
    }
}
