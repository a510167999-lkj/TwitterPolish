package com.polish.twitter.hooks

import android.content.Context
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class TimelineFilterHook : BaseHook() {

    override val name: String = "TimelineFilterHook"

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
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
                    // 强制所有推文/条目的 isPromoted() 检查返回 false
                    param.result = false
                }
            })
            Logger.i("Hooked isPromoted method: $className#$methodName -> false")
        } catch (e: Throwable) {
            Logger.w("Failed to hook isPromoted method", e)
        }
    }
}
