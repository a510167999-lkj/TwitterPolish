package com.polish.twitter.core

/**
 * X 宿主的关键混淆类名集中管理。
 *
 * 这些类名随 X 客户端大版本升级会变化，多处 Hook 共用同一清单，
 * 集中定义便于升级时统一维护；缺失/变化时各 Hook 有 try/catch 兜底并静默降级。
 */
object XHost {

    /** 主界面 Activity 候选（跨版本） */
    val MAIN_ACTIVITY_CANDIDATES = listOf(
        "com.x.android.main.MainActivity",
        "com.twitter.app.main.MainActivity",
        "com.twitter.android.MainActivity",
        "com.twitter.android.HomeTimelineActivity"
    )

    /** 设置根容器（Compose）——版本号页入口所在类 */
    const val SETTINGS_ROOT_CLASS = "com.x.settings.a"

    fun isMainActivity(className: String?): Boolean {
        return className in MAIN_ACTIVITY_CANDIDATES
    }
}
