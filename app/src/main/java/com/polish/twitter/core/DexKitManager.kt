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
    const val KEY_EXOPLAYER_IMPL = "exoplayer_impl"
    const val KEY_SETTINGS_ROOT_CLASS = "settings_root_class"
    private const val DEXKIT_SCHEMA = 3  // 每次新增特征都 bump，使缓存失效并重新扫描
    private const val KEY_DEXKIT_SCHEMA = "dexkit_schema"

    /**
     * 初始化 DexKit 并加载/更新符号缓存
     */
    fun init(context: Context) {
        if (isInitialized) return

        try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = packageInfo.longVersionCode

            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val cachedVersionCode = prefs.getLong(Constants.KEY_CACHED_VERSION_CODE, -1L)
            val cachedJson = prefs.getString(Constants.KEY_DEXKIT_CACHE, null)
            val cachedSchema = prefs.getInt(KEY_DEXKIT_SCHEMA, 0)

            if (currentVersionCode == cachedVersionCode &&
                cachedSchema == DEXKIT_SCHEMA &&
                !cachedJson.isNullOrBlank()
            ) {
                // 命中缓存，直接载入已解析的混淆类名与方法签名
                Logger.i("DexKit cache hit for version: $currentVersionCode schema=$DEXKIT_SCHEMA")
                loadCacheFromJson(cachedJson)
                isInitialized = true
                return
            }

            // 缓存未命中或 X 版本升级，启动 DexKit 动态扫描
            Logger.i("DexKit cache miss (Current: $currentVersionCode, Cached: $cachedVersionCode). Starting bytecode scan...")
            val apkPath = context.applicationInfo.sourceDir

            System.loadLibrary("dexkit")
            val bridge: DexKitBridge? = DexKitBridge.create(apkPath)
            bridge?.use { scanSymbols(it) }

            if (cacheMap.isEmpty()) {
                Logger.e("DexKit scan produced no symbols — dependent hooks will be inactive until next cold start")
            } else {
                val newJson = JSONObject(cacheMap as Map<*, *>).toString()
                prefs.edit()
                    .putLong(Constants.KEY_CACHED_VERSION_CODE, currentVersionCode)
                    .putString(Constants.KEY_DEXKIT_CACHE, newJson)
                    .putInt(KEY_DEXKIT_SCHEMA, DEXKIT_SCHEMA)
                    .apply()
                Logger.i("DexKit symbol scan completed and cached successfully.")
            }
            isInitialized = true
        } catch (e: Throwable) {
            Logger.e("DexKit initialization error", e)
        }
    }

    private fun scanSymbols(bridge: DexKitBridge) {
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

            val exoImplMethods = bridge.findMethod {
                matcher {
                    usingStrings("ExoPlayerImpl")
                }
            }
            val exoImpl = exoImplMethods.firstOrNull {
                it.className.contains("exoplayer", ignoreCase = true)
            } ?: exoImplMethods.firstOrNull()
            exoImpl?.let {
                cacheMap[KEY_EXOPLAYER_IMPL] = "${it.className}#${it.name}"
                Logger.d("Found ExoPlayerImpl: ${cacheMap[KEY_EXOPLAYER_IMPL]}")
            }

            // 4. 检索设置根容器类（版本号页入口所在）
            //    采用多特征扫描 + 分层兜底策略，提高跨版本稳定性：
            //
            //    第一层：特征字符串扫描
            //      - "onUpdateClick"：Kotlin 编译后的回调参数名（X 12.x 已知有效）
            //      - "versionName"：Compose 参数名（可能变化，作为次优特征）
            //      - "checkForUpdate" / "update_now"：检查更新相关字符串
            //    第二层：版本号字面量匹配
            //      - 格式如 "12.20.5-prod.01"，随 X 升级会变化但必然存在
            //    第三层：包扫描兜底
            //      - 直接扫描 com.x.settings 包下所有含 "settings" 或 "version" 的类
            val settingsClass = scanSettingsRootClass(bridge)
            if (settingsClass != null) {
                cacheMap[KEY_SETTINGS_ROOT_CLASS] = settingsClass
                Logger.i("Found settings root class via DexKit: $settingsClass")
            } else {
                Logger.w("DexKit could not locate settings root class — SettingsHook will fall back to hardcoded candidates")
            }
        } catch (e: Throwable) {
            Logger.w("Error during DexKit bytecode scan", e)
        }
    }

    /**
     * 多策略扫描设置根容器类（版本号页入口所在）。
     *
     * 分层兜底策略：
     * 1. 特征字符串扫描：尝试多个可能的字符串特征
     * 2. 版本号字面量匹配：查找形如 "12.20.5-prod.01" 的版本号字符串
     * 3. 包扫描兜底：直接扫描 com.x.settings 包下的所有类
     *
     * 返回最可能的设置根类名，或 null 表示未找到。
     */
    private fun scanSettingsRootClass(bridge: DexKitBridge): String? {
        // 第一层：特征字符串扫描（多个候选特征）
        val featureStrings = listOf("onUpdateClick", "versionName", "checkForUpdate", "update_now")
        for (feature in featureStrings) {
            val methods = bridge.findMethod {
                matcher {
                    usingStrings(feature)
                }
            }
            val candidate = methods.firstOrNull { it ->
                it.className.contains("settings", ignoreCase = true) ||
                    it.className.contains("version", ignoreCase = true) ||
                    it.className.contains("about", ignoreCase = true)
            }?.className
            if (candidate != null) {
                Logger.d("Found settings root via feature '$feature': $candidate")
                return candidate
            }
        }

        // 第二层：版本号字面量匹配（格式如 "12.20.5-prod.01"）
        // 直接用特征串 "prod" 作为近似特征，避免 Regex API 不支持的问题
        try {
            val methods = bridge.findMethod {
                matcher {
                    usingStrings("prod")
                }
            }
            val candidate = methods.firstOrNull { cls ->
                cls.className.contains("settings", ignoreCase = true) ||
                    cls.className.contains("about", ignoreCase = true)
            }?.className
            if (candidate != null) {
                Logger.d("Found settings root via 'prod' string: $candidate")
                return candidate
            }
        } catch (e: Throwable) {
            Logger.w("Version pattern matching failed: ${e.message}")
        }

        // 注意：包扫描 API 暂不支持按类名前缀过滤，如需增强可扩展
        return null
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

    /**
     * 持久化设置根类名（非方法描述，仅存类名）。
     * 首次扫描命中时调用，供后续冷启动直接读缓存而不重复扫描。
     */
    internal fun cacheSettingsRootClass(className: String) {
        cacheMap[KEY_SETTINGS_ROOT_CLASS] = className
    }

    /** 读取已缓存的设置根类名（供 SettingsHook 使用）。 */
    fun getCachedSettingsRootClass(): String? = cacheMap[KEY_SETTINGS_ROOT_CLASS]
}
