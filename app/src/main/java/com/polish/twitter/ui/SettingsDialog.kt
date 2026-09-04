package com.polish.twitter.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Process
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.polish.twitter.core.Constants
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.utils.Downloader

object SettingsDialog {

    fun show(activity: Activity) {
        try {
            val prefs = activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

            val isAdBlock = prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true)
            val isChronoSort = prefs.getBoolean(Constants.PREF_ENABLE_CHRONO_SORT, true)
            val isMediaDownload = prefs.getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true)
            val isDefaultFollowing = prefs.getBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, true)

            val dp = activity.resources.displayMetrics.density
            val pad16 = (16 * dp).toInt()
            val pad8 = (8 * dp).toInt()

            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad16, pad8, pad16, pad8)
            }

            // 标题说明
            val descView = TextView(activity).apply {
                text = "勾选需要启用的功能，设置即时保存并在下次刷新或重启时生效。"
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, pad8)
            }
            rootLayout.addView(descView)

            // 1. 去广告开关
            val cbAdBlock = createCheckBox(activity, "🚫 屏蔽瀑布流所有广告", "移除推广推文、推荐关注、话题推荐与趋势轮播", isAdBlock, dp)
            rootLayout.addView(cbAdBlock)

            // 2. 纯时间序开关
            val cbChronoSort = createCheckBox(activity, "⏱️ 瀑布流纯时间顺序排列", "基于推文 Snowflake 时间戳严格倒序排列，最新推文排在最上方", isChronoSort, dp)
            rootLayout.addView(cbChronoSort)

            // 3. 媒体下载开关
            val cbMediaDownload = createCheckBox(activity, "📥 启用视频与原图下载", "在推文中长按图片或视频即可弹出最高清画质下载对话框", isMediaDownload, dp)
            rootLayout.addView(cbMediaDownload)

            val isShowFloatingBall = prefs.getBoolean("show_floating_ball", true)

            // 4. 默认进入关注流开关
            val cbDefaultFollowing = createCheckBox(activity, "🎯 默认进入【正在关注】标签页", "启动应用时优先显示正在关注流，而非算法推荐流", isDefaultFollowing, dp)
            rootLayout.addView(cbDefaultFollowing)

            // 5. 悬浮球开关
            val cbShowFloatingBall = createCheckBox(activity, "⚙️ 屏幕显示快捷悬浮球", "在屏幕边缘显示快捷齿轮，点击可快速打开设置或下载视频/原图", isShowFloatingBall, dp)
            rootLayout.addView(cbShowFloatingBall)

            // 5. 快速下载当前媒体按钮
            val btnQuickDownload = Button(activity).apply {
                text = "📥 下载当前媒体 / 查看下载说明"
                setOnClickListener {
                    val media = MediaCache.resolve()
                    if (media.isNotEmpty()) {
                        Downloader.download(activity, media.first())
                    } else {
                        Toast.makeText(activity, "💡 先打开带视频的推文，或分享→复制链接，再点下载。播放器画面长按也可。", Toast.LENGTH_LONG).show()
                    }
                }
            }
            rootLayout.addView(btnQuickDownload)

            val scrollView = ScrollView(activity).apply {
                addView(rootLayout)
            }

            AlertDialog.Builder(activity)
                .setTitle("✨ TwitterPolish 设置")
                .setView(scrollView)
                .setPositiveButton("保存并生效") { _, _ ->
                    prefs.edit()
                        .putBoolean(Constants.PREF_ENABLE_AD_BLOCK, cbAdBlock.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_CHRONO_SORT, cbChronoSort.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, cbMediaDownload.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, cbDefaultFollowing.isChecked)
                        .putBoolean("show_floating_ball", cbShowFloatingBall.isChecked)
                        .apply()
                    if (cbShowFloatingBall.isChecked) {
                        FloatingMenuManager.attach(activity)
                    } else {
                        FloatingMenuManager.remove()
                    }
                    Toast.makeText(activity, "✅ 设置已保存，刷新时间线即可生效", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("重启 X 客户端") { _, _ ->
                    prefs.edit()
                        .putBoolean(Constants.PREF_ENABLE_AD_BLOCK, cbAdBlock.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_CHRONO_SORT, cbChronoSort.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, cbMediaDownload.isChecked)
                        .putBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, cbDefaultFollowing.isChecked)
                        .putBoolean("show_floating_ball", cbShowFloatingBall.isChecked)
                        .apply()
                    restartApp(activity)
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Throwable) {
            Logger.e("Failed to show TwitterPolish settings dialog", e)
        }
    }

    private fun createCheckBox(context: Context, title: String, subtitle: String, checked: Boolean, dp: Float): CheckBox {
        return CheckBox(context).apply {
            text = "$title\n$subtitle"
            isChecked = checked
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * dp).toInt()
            }
            layoutParams = params
        }
    }

    private fun restartApp(activity: Activity) {
        try {
            val pm = activity.packageManager
            val intent = pm.getLaunchIntentForPackage(activity.packageName)
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
