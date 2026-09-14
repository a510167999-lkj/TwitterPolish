package com.polish.twitter.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Process
import android.widget.CompoundButton
import android.widget.Toast
import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import com.polish.twitter.utils.DownloadResolver
import com.polish.twitter.utils.Downloader
import dev.polish.ui.PolishUi

object SettingsDialog {

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val panel = PolishUi.screen(activity, "TwitterPolish", "时间线、媒体下载与快捷入口")
            val status = panel.section("宿主信息", "宿主版本不代表模块已激活或所有功能已适配。")
            PolishUi.info(status, "X 客户端", PolishUi.hostVersion(activity, Constants.TARGET_PACKAGE))

            val timeline = panel.section("时间线", "保存后刷新时间线；默认标签页在下次启动时生效。")
            val cbAdBlock = PolishUi.toggle(timeline, "屏蔽瀑布流广告与推荐",
                "过滤推广推文、推荐关注及话题推荐；兼容性取决于宿主数据结构。",
                prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true))
            val cbChronoSort = PolishUi.toggle(timeline, "按时间倒序排列",
                "基于推文 Snowflake 时间戳排序，保留分页游标。",
                prefs.getBoolean(Constants.PREF_ENABLE_CHRONO_SORT, true))
            val cbDefaultFollowing = PolishUi.toggle(timeline, "默认进入正在关注",
                "启动时优先显示关注流，而非算法推荐流。",
                prefs.getBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, true))

            val mediaCard = panel.section("媒体下载", "手动下载入口始终可用；开关控制长按与复制链接提示。")
            val cbMediaDownload = PolishUi.toggle(mediaCard, "启用视频与原图下载提示",
                "长按视频画面或复制推文链接时显示下载入口。",
                prefs.getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true))
            PolishUi.action(mediaCard, "下载当前媒体 / 查看下载说明", true) {
                val media = DownloadResolver.resolve(activity)
                if (media.isNotEmpty()) {
                    Downloader.download(activity, media.first())
                } else {
                    Toast.makeText(activity, "先播放视频几秒，再长按画面或点这里。支持从 HLS 播放列表合成 MP4。", Toast.LENGTH_LONG).show()
                }
            }

            val shortcuts = panel.section("快捷入口", "隐藏悬浮球后仍可使用顶部 X 长按入口。")
            val cbShowFloatingBall = PolishUi.toggle(shortcuts, "显示快捷悬浮球",
                "点击打开设置或下载当前媒体，可拖动调整位置。",
                prefs.getBoolean(Constants.PREF_SHOW_FLOATING_BALL, true))
            panel.note("点击保存才会写入设置；取消不保存本次开关修改。")

            val dialog = PolishUi.dialog(activity, panel)
                .setPositiveButton("保存并生效") { _, _ ->
                    saveAll(prefs, cbAdBlock, cbChronoSort, cbMediaDownload, cbDefaultFollowing, cbShowFloatingBall)
                    if (cbShowFloatingBall.isChecked) FloatingMenuManager.attach(activity)
                    else FloatingMenuManager.remove()
                    Toast.makeText(activity, "设置已保存；刷新时间线，默认标签页下次启动生效", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("保存并重启 X") { _, _ ->
                    saveAll(prefs, cbAdBlock, cbChronoSort, cbMediaDownload, cbDefaultFollowing, cbShowFloatingBall)
                    restartApp(activity)
                }
                .setNegativeButton("取消", null)
                .show()
            PolishUi.styleDialog(dialog)
        } catch (e: Throwable) {
            Logger.e("Failed to show TwitterPolish settings dialog", e)
        }
    }

    private fun saveAll(
        prefs: android.content.SharedPreferences,
        cbAdBlock: CompoundButton,
        cbChronoSort: CompoundButton,
        cbMediaDownload: CompoundButton,
        cbDefaultFollowing: CompoundButton,
        cbShowFloatingBall: CompoundButton
    ) {
        prefs.edit()
            .putBoolean(Constants.PREF_ENABLE_AD_BLOCK, cbAdBlock.isChecked)
            .putBoolean(Constants.PREF_ENABLE_CHRONO_SORT, cbChronoSort.isChecked)
            .putBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, cbMediaDownload.isChecked)
            .putBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, cbDefaultFollowing.isChecked)
            .putBoolean(Constants.PREF_SHOW_FLOATING_BALL, cbShowFloatingBall.isChecked)
            .apply()
    }

    private fun restartApp(activity: Activity) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                activity.startActivity(intent)
            }
            Process.killProcess(Process.myPid())
        } catch (t: Throwable) {
            Logger.e("Failed to restart app", t)
        }
    }
}
