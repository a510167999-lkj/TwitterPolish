package com.polish.twitter.core

/**
 * X 宿主的关键类名集中管理。
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

    fun isMainActivity(className: String?): Boolean {
        return className in MAIN_ACTIVITY_CANDIDATES
    }

    /** X 自己的页面（不含广告、支付等第三方 SDK 的 Activity），只在这些页面挂悬浮球。 */
    fun isHostActivity(className: String): Boolean =
        className.startsWith("com.x.") || className.startsWith("com.twitter.")

    /** 顶栏高度近似值：Logo 长按只认这一带；视频长按避开这一带。 */
    fun headerHeightPx(density: Float): Float = 160 * density
}
