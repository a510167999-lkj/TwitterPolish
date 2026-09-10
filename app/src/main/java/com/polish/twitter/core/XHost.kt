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

    /** 设置根容器 Compose 候选（按可能性排列；最终由 DexKit 特征扫描确定） */
    val SETTINGS_ROOT_CANDIDATES = listOf(
        "com.x.settings.a",   // X 12.x 版本
        "com.twitter.settings.SettingsActivity",
        "com.twitter.android.settings.SettingsActivity"
    )

    fun isMainActivity(className: String?): Boolean {
        return className in MAIN_ACTIVITY_CANDIDATES
    }
}
