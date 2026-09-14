package com.polish.twitter.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

object DexKitManager {
    const val KEY_OKHTTP_CLIENT_BUILD = "okhttp_client_build"
    const val KEY_TIMELINE_ITEM_IS_PROMOTED = "timeline_item_is_promoted"
    const val KEY_EXOPLAYER_IMPL = "exoplayer_impl"
    const val KEY_SETTINGS_ROOT_CLASS = "settings_root_class"
    private const val SCHEMA = 4
    private val keys = setOf(KEY_OKHTTP_CLIENT_BUILD, KEY_TIMELINE_ITEM_IS_PROMOTED,
        KEY_EXOPLAYER_IMPL, KEY_SETTINGS_ROOT_CLASS)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "twitter-polish-dex").apply { isDaemon = true }
    }
    @Volatile private var symbols: Map<String, String> = emptyMap()
    @Volatile var status: String = "尚未扫描"
        private set
    private var started = false

    /** 主线程只排队；缓存验证和 APK 扫描也在同一后台任务中执行。 */
    @Synchronized
    fun init(context: Context, loader: ClassLoader, onReady: () -> Unit) {
        if (started) return
        started = true
        status = "后台检查兼容性"
        worker.execute {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                loadOrScan(context, loader)
                status = "结构校验通过 ${symbols.size}/${keys.size} 项；实际效果待验证"
            } catch (error: Throwable) {
                status = "扫描失败，基础入口仍可用"
                Logger.e("DexKit initialization error", error)
            } finally {
                // 即使部分目标未找到，也安装已验证目标；各功能自行隔离失败。
                try { onReady() } catch (error: Throwable) { Logger.e("Deferred hooks failed", error) }
                worker.shutdown()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadOrScan(context: Context, loader: ClassLoader) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val paths = (listOf(context.applicationInfo.sourceDir) +
            context.applicationInfo.splitSourceDirs.orEmpty()).distinct().sorted()
        val identity = "$SCHEMA|${context.packageName}|$version|${info.lastUpdateTime}|" +
            paths.joinToString("|") { path -> File(path).let { "$path:${it.length()}:${it.lastModified()}" } }
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val cached = runCatching {
            if (prefs.getString("dexkit_identity", null) != identity) return@runCatching null
            decodeCache(prefs.getString(Constants.KEY_DEXKIT_CACHE, null), loader)
        }.getOrNull()
        if (cached != null) {
            symbols = cached
            return
        }
        prefs.edit().remove(Constants.KEY_DEXKIT_CACHE).remove("dexkit_identity").apply()
        System.loadLibrary("dexkit")
        val candidates = keys.associateWith { linkedSetOf<String>() }
        var complete = true
        for (path in paths) {
            try {
                DexKitBridge.create(path).use { bridge ->
                    bridge.setThreadNum(1)
                    fun collect(key: String, query: () -> List<String>) {
                        try { candidates.getValue(key).addAll(query()) }
                        catch (error: Throwable) {
                            complete = false
                            Logger.w("DexKit feature failed: $key", error)
                        }
                    }
                    collect(KEY_OKHTTP_CLIENT_BUILD) {
                        bridge.findMethod { matcher { name = "build"; returnType = "okhttp3.OkHttpClient"; paramCount = 0 } }
                            .map { it.descriptor }
                    }
                    collect(KEY_TIMELINE_ITEM_IS_PROMOTED) {
                        bridge.findMethod { matcher { usingStrings("promotedContent", "promotedMetadata"); returnType = "boolean"; paramCount = 0 } }
                            .map { it.descriptor }
                    }
                    collect(KEY_EXOPLAYER_IMPL) {
                        bridge.findMethod { matcher { usingStrings("ExoPlayerImpl") } }.map { it.descriptor }
                    }
                    collect(KEY_SETTINGS_ROOT_CLASS) {
                        bridge.findMethod { matcher { usingStrings("onUpdateClick") } }.map { it.descriptor }
                    }
                }
            } catch (error: Throwable) {
                complete = false
                Logger.w("DexKit APK scan failed: $path", error)
            }
        }
        // 未扫描完整时不能证明唯一，不发布也不缓存不完整的候选集合。
        if (!complete) error("Incomplete base/split scan")
        symbols = candidates.mapNotNull { (key, values) ->
            val valid = values.filter { resolveValidated(key, it, loader) != null }
            val target = MethodTarget.unique(valid)
            if (target == null) Logger.w("DexKit $key skipped: ${valid.size} validated candidates")
            target?.let { key to it }
        }.toMap()
        if (symbols.isNotEmpty()) {
            prefs.edit().putString("dexkit_identity", identity)
                .putString(Constants.KEY_DEXKIT_CACHE, JSONObject(symbols).toString()).apply()
        }
    }

    internal fun decodeCache(raw: String?, loader: ClassLoader): Map<String, String>? = runCatching {
        if (raw.isNullOrBlank()) return null
        val json = JSONObject(raw)
        require(json.length() > 0)
        val result = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            require(key in keys)
            val descriptor = json.get(key) as? String ?: error("Invalid descriptor value")
            require(resolveValidated(key, descriptor, loader) != null)
            result[key] = descriptor
        }
        result.toMap()
    }.getOrNull()

    private fun resolveValidated(key: String, descriptor: String, loader: ClassLoader): Executable? {
        val member = MethodTarget.resolve(descriptor, loader) ?: return null
        val method = member as? Method
        val valid = when (key) {
            KEY_OKHTTP_CLIENT_BUILD -> method != null && method.name == "build" &&
                method.parameterCount == 0 && !Modifier.isStatic(method.modifiers) &&
                method.returnType.name == "okhttp3.OkHttpClient" &&
                member.declaringClass.methods.any { it.name == "addInterceptor" &&
                    it.parameterTypes.map(Class<*>::getName) == listOf("okhttp3.Interceptor") }
            KEY_TIMELINE_ITEM_IS_PROMOTED -> method != null && method.parameterCount == 0 &&
                method.returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(method.modifiers)
            KEY_SETTINGS_ROOT_CLASS -> method != null && method.returnType == Void.TYPE &&
                method.parameterTypes.count { it.name == "kotlin.jvm.functions.Function0" } == 1 &&
                method.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }
            KEY_EXOPLAYER_IMPL -> listOf("androidx.media3.common.Player", "com.google.android.exoplayer2.Player").any { name ->
                runCatching { Class.forName(name, false, loader).isAssignableFrom(member.declaringClass) }.getOrDefault(false)
            } && member.declaringClass.methods.any { it.name == "getCurrentMediaItem" && it.parameterCount == 0 }
            else -> false
        }
        return member.takeIf { valid }
    }

    internal fun getTarget(key: String, loader: ClassLoader): Executable? =
        symbols[key]?.let { resolveValidated(key, it, loader) }

    fun getMethod(key: String, loader: ClassLoader): Method? = getTarget(key, loader) as? Method
}
