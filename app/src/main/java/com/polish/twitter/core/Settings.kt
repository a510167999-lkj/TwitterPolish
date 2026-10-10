package com.polish.twitter.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部功能开关的内存快照。热路径（网络响应、触摸事件）只读 volatile 字段，不碰 SharedPreferences。
 * 设置存在宿主 X 自己的 prefs 文件里，由 SettingsDialog 写入、这里统一监听刷新。
 */
object Settings {
    @Volatile var adBlock = true; private set
    @Volatile var bypassSensitive = false; private set
    @Volatile var mediaDownload = true; private set
    @Volatile var defaultFollowing = true; private set
    @Volatile var floatingBall = true; private set

    // SharedPreferences 对 listener 只持 WeakReference，必须自己留强引用。
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Synchronized
    fun init(context: Context) {
        if (listener != null) return
        val prefs = prefs(context)
        refresh(prefs)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> refresh(p) }
        listener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private fun refresh(prefs: SharedPreferences) {
        adBlock = prefs.getBoolean(Constants.PREF_ENABLE_AD_BLOCK, true)
        bypassSensitive = prefs.getBoolean(Constants.PREF_BYPASS_SENSITIVE, false)
        mediaDownload = prefs.getBoolean(Constants.PREF_ENABLE_MEDIA_DOWNLOAD, true)
        defaultFollowing = prefs.getBoolean(Constants.PREF_ENABLE_DEFAULT_FOLLOWING, true)
        floatingBall = prefs.getBoolean(Constants.PREF_SHOW_FLOATING_BALL, true)
    }
}
