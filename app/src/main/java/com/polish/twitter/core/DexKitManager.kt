package com.polish.twitter.core

import android.content.Context
import android.content.pm.PackageInfo
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File

object DexKitManager {

    private var isInitialized = false
    private val cacheMap = mutableMapOf<String, String>()

    // 缓存 Key 定义
    const val KEY_OKHTTP_CLIENT_BUILD = "okhttp_client_build"
    const val KEY_ACTION_SHEET_SHOW = "action_sheet_show"
    const val KEY_TIMELINE_ITEM_IS_PROMOTED = "timeline_item_is_promoted"

    /**
     * 初始化 DexKit 并加载/更新符号缓存
     */
    fun init(context: Context, classLoader: ClassLoader) {
        if (isInitialized) return

        try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = packageInfo.longVersionCode

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedVersionCode = prefs.getLong(Constants.KEY_CACHED_VERSION_CODE, -1L)
            val cachedJson = prefs.getString(Constants.KEY_DEXKIT_CACHE, null)

            if (currentVersionCode == cachedVersionCode && !cachedJson.isNullOrBlank()) {
                // 命中缓存，直接载入已解析的混淆类名与方法签名
                Logger.i("DexKit cache hit for version: $currentVersionCode")
                loadCacheFromJson(cachedJson)
                isInitialized = true
                return
            }

            // 缓存未命中或 X 版本升级，启动 DexKit 动态扫描
            Logger.i("DexKit cache miss (Current: $currentVersionCode, Cached: $cachedVersionCode). Starting bytecode scan...")
            val apkPath = context.applicationInfo.sourceDir

            System.loadLibrary("dexkit")
            DexKitBridge.create(apkPath)?.use { bridge ->
                scanSymbols(bridge, classLoader)
            }

            // 将新解析的结果持久化至本地
            val newJson = JSONObject(cacheMap as Map<*, *>).toString()
            prefs.edit()
                .putLong(Constants.KEY_CACHED_VERSION_CODE, currentVersionCode)
                .putString(Constants.KEY_DEXKIT_CACHE, newJson)
                .apply()

            Logger.i("DexKit symbol scan completed and cached successfully.")
            isInitialized = true
        } catch (e: Throwable) {
            Logger.e("DexKit initialization error", e)
        }
    }

    private fun scanSymbols(bridge: DexKitBridge, classLoader: ClassLoader) {
        try {
            // 1. 检索 OkHttpClient.Builder.build() 方法
            val okhttpBuildMethod = bridge.findMethod {
                matcher {
                    name = "build"
                    returnType = "okhttp3.OkHttpClient"
                }
            }.firstOrNull()

            okhttpBuildMethod?.let {
                cacheMap[KEY_OKHTTP_CLIENT_BUILD] = "${it.className}#${it.name}"
                Logger.d("Found OkHttpClient build: ${cacheMap[KEY_OKHTTP_CLIENT_BUILD]}")
            }

            // 2. 检索推广推文判断方法 (通过特征字符串 "promotedContent" 或 "is_promoted")
            val promotedMethods = bridge.findMethod {
                matcher {
                    usingStrings("promotedContent", "promotedMetadata")
                    returnType = "boolean"
                }
            }
            promotedMethods.firstOrNull()?.let {
                cacheMap[KEY_TIMELINE_ITEM_IS_PROMOTED] = "${it.className}#${it.name}"
                Logger.d("Found isPromoted method: ${cacheMap[KEY_TIMELINE_ITEM_IS_PROMOTED]}")
            }

            // 3. 检索推文 ActionSheet / BottomSheet 菜单展示方法
            val actionSheetMethods = bridge.findMethod {
                matcher {
                    usingStrings("share_tweet", "tweet_share")
                }
            }
            actionSheetMethods.firstOrNull()?.let {
                cacheMap[KEY_ACTION_SHEET_SHOW] = "${it.className}#${it.name}"
                Logger.d("Found ActionSheet method: ${cacheMap[KEY_ACTION_SHEET_SHOW]}")
            }
        } catch (e: Throwable) {
            Logger.w("Error during DexKit bytecode scan", e)
        }
    }

    private fun loadCacheFromJson(json: String) {
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                cacheMap[key] = obj.getString(key)
            }
        } catch (e: Throwable) {
            Logger.w("Failed to parse DexKit cache JSON", e)
        }
    }

    fun getMethodDescriptor(key: String): Pair<String, String>? {
        val descriptor = cacheMap[key] ?: return null
        val parts = descriptor.split("#")
        return if (parts.size == 2) Pair(parts[0], parts[1]) else null
    }
}
