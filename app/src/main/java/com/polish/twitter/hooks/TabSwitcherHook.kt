package com.polish.twitter.hooks

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.polish.twitter.core.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

class TabSwitcherHook : BaseHook() {

    override val name: String = "TabSwitcherHook"

    override fun init(classLoader: ClassLoader, context: Context) {
        try {
            hookMainActivity(classLoader)
        } catch (e: Throwable) {
            Logger.w("Failed to initialize TabSwitcherHook", e)
        }
    }

    private fun hookMainActivity(classLoader: ClassLoader) {
        val candidateActivities = listOf(
            "com.x.android.main.MainActivity",
            "com.twitter.app.main.MainActivity",
            "com.twitter.android.MainActivity",
            "com.twitter.android.HomeTimelineActivity"
        )

        for (activityName in candidateActivities) {
            try {
                val activityClass = classLoader.loadClass(activityName)
                XposedBridge.hookAllMethods(activityClass, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        Logger.d("MainActivity onCreate hooked: ${activity.javaClass.name}")
                        activity.window?.decorView?.post {
                            com.polish.twitter.ui.FloatingMenuManager.attach(activity)
                        }
                    }
                })
            } catch (ignored: ClassNotFoundException) {
            }
        }
    }
}
