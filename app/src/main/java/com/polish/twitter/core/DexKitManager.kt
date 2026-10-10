package com.polish.twitter.core

import android.content.Context
import android.os.Build
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors

object DexKitManager {
    const val KEY_OKHTTP_CLIENT_BUILD = "okhttp_client_build"
    /** 首页创建时决定初始标签：读 DataStore 里的 preferred_home_tab，读不到返回「为你推荐」。 */
    const val KEY_HOME_INITIAL_TAB = "home_initial_tab"
    /** 远程开关：后台超过 N 分钟回来强制切回「为你推荐」。 */
    const val KEY_HOME_RESTART_FOR_YOU = "home_restart_for_you"
    /** 「正在关注」标签对象的 toString()；用来拿它的单例 INSTANCE。 */
    const val KEY_HOME_FOLLOWING_TAB = "home_following_tab"
    /** ExoPlayerImpl 构造器：12.33 起播放器类名与方法名全被混淆，只剩日志 tag "ExoPlayerImpl" 稳定。 */
    const val KEY_EXOPLAYER_IMPL = "exoplayer_impl"
    private const val SCHEMA = 6
    // 顺序有意义：FOLLOWING 的校验依赖已解析的 INITIAL_TAB 返回类型。
    private val keys = listOf(KEY_OKHTTP_CLIENT_BUILD, KEY_HOME_INITIAL_TAB, KEY_HOME_RESTART_FOR_YOU,
        KEY_HOME_FOLLOWING_TAB, KEY_EXOPLAYER_IMPL)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "twitter-polish-dex").apply { isDaemon = true }
    }
    @Volatile private var symbols: Map<String, String> = emptyMap()
    @Volatile var status: String = "尚未扫描"
        private set
    private var started = false

    /**
     * 缓存命中：在调用线程（Application.attach）同步校验并立即安装，耗时只是几次反射。
     * 首页标签在 MainActivity 创建时就决定了，放到后台线程会和它赛跑，真机上输过（1.4s 后才装上）。
     * 缓存未命中（X 升级后首次启动）：后台扫描，本次启动的首页标签可能来不及接管。
     */
    @Synchronized
    fun init(context: Context, loader: ClassLoader, onReady: () -> Unit) {
        if (started) return
        started = true
        val begin = System.nanoTime()
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val identity = runCatching { identityOf(context) }.getOrNull()
        val cached = identity?.let { id ->
            runCatching {
                if (prefs.getString("dexkit_identity", null) != id) null
                else decodeCache(prefs.getString(Constants.KEY_DEXKIT_CACHE, null), loader)
            }.getOrNull()
        }
        if (cached != null) {
            symbols = cached
            Logger.i("DexKit cache hit in ${(System.nanoTime() - begin) / 1_000_000}ms")
            finish(onReady)
            return
        }
        status = "后台检查兼容性"
        Logger.i("DexKit cache miss; scanning in background (home tab hook may miss this launch)")
        worker.execute {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                scan(context, loader, prefs, identity ?: identityOf(context))
            } catch (error: Throwable) {
                Logger.e("DexKit initialization error", error)
            } finally {
                finish(onReady)
                worker.shutdown()
            }
        }
    }

    private fun finish(onReady: () -> Unit) {
        val missing = keys.filter { it !in symbols }
        status = if (missing.isEmpty()) "适配完整 ${symbols.size}/${keys.size}"
            else "部分未适配：${missing.joinToString()}"
        if (missing.isNotEmpty()) Logger.w("DexKit unresolved on this X version: $missing")
        // 即使部分目标未找到，也安装已验证目标；各功能自行隔离失败。
        try { onReady() } catch (error: Throwable) { Logger.e("Deferred hooks failed", error) }
    }

    @Suppress("DEPRECATION")
    private fun identityOf(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "$SCHEMA|${context.packageName}|$version|${info.lastUpdateTime}|" +
            apkPaths(context).joinToString("|") { path -> File(path).let { "$path:${it.length()}:${it.lastModified()}" } }
    }

    private fun apkPaths(context: Context): List<String> =
        (listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty())
            .distinct().sorted()

    private fun scan(context: Context, loader: ClassLoader, prefs: android.content.SharedPreferences, identity: String) {
        val paths = apkPaths(context)
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
                    fun exact(value: String) = listOf(value)
                    collect(KEY_OKHTTP_CLIENT_BUILD) {
                        bridge.findMethod { matcher { name = "build"; returnType = "okhttp3.OkHttpClient"; paramCount = 0 } }
                            .map { it.descriptor }
                    }
                    collect(KEY_HOME_INITIAL_TAB) {
                        bridge.findMethod { matcher {
                            usingStrings(exact("PreferredTabRepo"), StringMatchType.Equals, false)
                            paramTypes("boolean")
                        } }.map { it.descriptor }
                    }
                    collect(KEY_HOME_RESTART_FOR_YOU) {
                        bridge.findMethod { matcher {
                            usingStrings(exact("android_home_back_open_for_you_on_app_restart_enabled"),
                                StringMatchType.Equals, false)
                            returnType = "boolean"
                            paramCount = 0
                        } }.map { it.descriptor }
                    }
                    collect(KEY_HOME_FOLLOWING_TAB) {
                        bridge.findMethod { matcher {
                            name = "toString"
                            paramCount = 0
                            usingStrings(exact("Following"), StringMatchType.Equals, false)
                        } }.map { it.descriptor }
                    }
                    collect(KEY_EXOPLAYER_IMPL) {
                        bridge.findMethod { matcher {
                            usingStrings(exact("ExoPlayerImpl"), StringMatchType.Equals, false)
                        } }.filter { it.isConstructor }.map { it.descriptor }
                    }
                }
            } catch (error: Throwable) {
                complete = false
                Logger.w("DexKit APK scan failed: $path", error)
            }
        }
        // 未扫描完整时不能证明唯一，不发布也不缓存不完整的候选集合。
        if (!complete) error("Incomplete base/split scan")
        val resolved = linkedMapOf<String, String>()
        for (key in keys) {
            val valid = candidates.getValue(key).filter { resolveValidated(key, it, loader, resolved) != null }
            val target = MethodTarget.unique(valid)
            if (target == null) Logger.w("DexKit $key skipped: ${candidates.getValue(key).size} raw / ${valid.size} validated")
            else resolved[key] = target
        }
        symbols = resolved
        if (symbols.isNotEmpty()) {
            prefs.edit().putString("dexkit_identity", identity)
                .putString(Constants.KEY_DEXKIT_CACHE, JSONObject(symbols as Map<*, *>).toString()).apply()
        }
    }

    internal fun decodeCache(raw: String?, loader: ClassLoader): Map<String, String>? = runCatching {
        if (raw.isNullOrBlank()) return null
        val json = JSONObject(raw)
        require(json.length() > 0)
        json.keys().forEach { require(it in keys) }
        val result = linkedMapOf<String, String>()
        for (key in keys) {
            if (!json.has(key)) continue
            val descriptor = json.get(key) as? String ?: error("Invalid descriptor value")
            require(resolveValidated(key, descriptor, loader, result) != null)
            result[key] = descriptor
        }
        result.toMap()
    }.getOrNull()

    private fun resolveValidated(
        key: String,
        descriptor: String,
        loader: ClassLoader,
        resolved: Map<String, String>
    ): Executable? {
        val target = MethodTarget.resolve(descriptor, loader) ?: return null
        if (key == KEY_EXOPLAYER_IMPL) return target.takeIf { isExoPlayerImpl(it, loader) }
        val method = target as? Method ?: return null
        val valid = when (key) {
            KEY_OKHTTP_CLIENT_BUILD -> method.name == "build" &&
                method.parameterCount == 0 && !Modifier.isStatic(method.modifiers) &&
                method.returnType.name == "okhttp3.OkHttpClient" &&
                method.declaringClass.methods.any { it.name == "addInterceptor" &&
                    it.parameterTypes.map(Class<*>::getName) == listOf("okhttp3.Interceptor") }
            KEY_HOME_INITIAL_TAB -> !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                Modifier.isAbstract(method.returnType.modifiers)
            KEY_HOME_RESTART_FOR_YOU -> !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 && method.returnType == Boolean::class.javaPrimitiveType &&
                resolved[KEY_HOME_INITIAL_TAB]?.let { MethodTarget.resolve(it, loader) }
                    ?.declaringClass == method.declaringClass
            KEY_HOME_FOLLOWING_TAB -> {
                // 只认「首页标签类型」的子类，避免命中个人页等其它 "Following" 对象。
                val tabType = resolved[KEY_HOME_INITIAL_TAB]
                    ?.let { MethodTarget.resolve(it, loader) as? Method }?.returnType
                tabType != null && method.declaringClass.superclass == tabType &&
                    followingInstance(method) != null
            }
            else -> false
        }
        return method.takeIf { valid }
    }

    private fun isExoPlayerImpl(target: Executable, loader: ClassLoader): Boolean {
        val owner = target.declaringClass
        if (target !is java.lang.reflect.Constructor<*> || Modifier.isAbstract(owner.modifiers)) return false
        return listOf("androidx.media3.exoplayer.ExoPlayer", "com.google.android.exoplayer2.ExoPlayer").any { name ->
            runCatching { Class.forName(name, false, loader).isAssignableFrom(owner) }.getOrDefault(false)
        }
    }

    /** 「正在关注」单例：Kotlin object 的静态 INSTANCE，且 toString() 确为 "Following"。 */
    private fun followingInstance(toString: Method): Any? = runCatching {
        val field = toString.declaringClass.getDeclaredField("INSTANCE")
        require(Modifier.isStatic(field.modifiers))
        field.isAccessible = true
        field.get(null)?.takeIf { it.toString() == "Following" }
    }.getOrNull()

    internal fun getTarget(key: String, loader: ClassLoader): Executable? =
        symbols[key]?.let { resolveValidated(key, it, loader, symbols) }

    fun getMethod(key: String, loader: ClassLoader): Method? = getTarget(key, loader) as? Method

    /** 播放器实现类（ExoPlayer 接口名在 12.33 仍保留，实现类被混淆成 d0 之类）。 */
    fun exoPlayerImpl(loader: ClassLoader): Class<*>? = getTarget(KEY_EXOPLAYER_IMPL, loader)?.declaringClass

    fun homeFollowingTab(loader: ClassLoader): Any? =
        getMethod(KEY_HOME_FOLLOWING_TAB, loader)?.let { followingInstance(it) }
}
