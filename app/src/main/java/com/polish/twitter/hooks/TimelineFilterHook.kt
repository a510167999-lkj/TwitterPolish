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
        val descriptor = DexKitManager.getMethodDescriptor(DexKitManager.KEY_TIMELINE_ITEM_IS_PROMOTED)
        if (descriptor == null) {
            Logger.d("No cached isPromoted method descriptor found, skipping model hook.")
            return
        }

        try {
            val (className, methodName) = descriptor
            val targetClass = classLoader.loadClass(className)
            XposedBridge.hookAllMethods(targetClass, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!adBlockEnabled) return
                    // DexKit 只保证方法名；同名带参方法不能一律改返回值，否则时间线条目会整批消失。
                    if (param.args.isNotEmpty()) return
                    param.result = false
                }
            })
            Logger.i("Hooked isPromoted method: $className#$methodName -> false")
        } catch (e: Throwable) {
            Logger.w("Failed to hook isPromoted method", e)
        }
    }
}
