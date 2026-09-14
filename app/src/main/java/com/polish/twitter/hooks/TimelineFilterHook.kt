package com.polish.twitter.hooks

import android.content.Context
import android.content.SharedPreferences
import com.polish.twitter.core.Constants
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class TimelineFilterHook : BaseHook() {

    override val name: String = "TimelineFilterHook"

    // 模型层拦截应尊重用户开关：关闭“屏蔽广告”时不得强制 isPromoted=false。
    // volatile 快照 + 监听变更，避免每个推文渲染都读 SharedPreferences。
    @Volatile
    private var adBlockEnabled = true

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            adBlockEnabled = prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == Constants.PREF_ENABLE_AD_BLOCK) {
                    adBlockEnabled = prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true)
                }
            }
            prefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
            hookModelPromotedCheck(classLoader)
        } catch (e: Throwable) {
            Logger.w("Failed to initialize TimelineFilterHook", e)
        }
    }

    private fun hookModelPromotedCheck(classLoader: ClassLoader) {
        val method = DexKitManager.getMethod(DexKitManager.KEY_TIMELINE_ITEM_IS_PROMOTED, classLoader)
        if (method == null) {
            Logger.d("No cached isPromoted method descriptor found, skipping model hook.")
            return
        }

        try {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!adBlockEnabled) return
                    param.result = false
                }
            })
            Logger.i("Hooked validated isPromoted method: $method")
        } catch (e: Throwable) {
            Logger.w("Failed to hook isPromoted method", e)
        }
    }
}
