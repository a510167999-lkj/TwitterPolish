package com.polish.twitter.utils

import android.app.Activity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 沉浸式播放器同时活着多个 ExoPlayer。
 * 长按必须跟「手指下面那块 Surface」绑定，不能信任意 CDN 请求或上一个渲染过的播放器。
 *
 * 画面→播放器的对应关系在长按那一刻现读：播放器内部保存着当前输出（Surface / SurfaceHolder /
 * TextureView），按字段类型扫出来和手指下的视图做身份比对。X 12.33 把 setVideoSurface* 与
 * clearVideoSurface* 都混淆成单字母且签名相同，靠挂方法分不清设置还是清除，读状态才可靠。
 */
object PlayerPlaybackTracker {

    private val players = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val listening = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val outputFieldCache = Collections.synchronizedMap(HashMap<Class<*>, List<Field>>())

    @Volatile
    private var lastRendered: WeakReference<Any>? = null

    @Volatile
    private var classLoader: ClassLoader? = null

    fun hook(classLoader: ClassLoader) {
        this.classLoader = classLoader
        hookNamedBuilders(classLoader)
        hookPlayerView(classLoader)
    }

    /** DexKit 找到的播放器实现类（混淆后的 ExoPlayerImpl）：构造即登记。 */
    fun hookImplClass(clazz: Class<*>) {
        XposedBridge.hookAllConstructors(clazz, attachCtor)
        Logger.i("PlayerTracker hooked ${clazz.name} constructors")
    }

    fun attach(player: Any?) {
        if (player == null) return
        players[player] = true
        addListener(player)
    }

    /**
     * 长按：只认按到的那块画面所属播放器。认不出返回 null，由调用方决定要不要降级。
     */
    fun syncViewIntoCache(view: View): String? {
        val player = playerForView(view)
        if (player == null) {
            Logger.i("Long-press: no player for ${view.javaClass.name}; live players=${players.size}")
            return null
        }
        val uri = uriFromPlayer(player)
        val id = uri?.let { MediaExtractor.extractMediaId(it) }
        Logger.i("Long-press view=${view.javaClass.simpleName} ${view.width}x${view.height} media=$id")
        if (uri == null || id == null) return null
        MediaCache.notePlaybackUrl(uri, fromPlayer = true)
        lastRendered = WeakReference(player)
        return id
    }

    fun syncPlayingIntoCache(activity: Activity?): String? {
        val player = selectPlayer(activity) ?: return null
        val uri = uriFromPlayer(player) ?: return null
        MediaCache.notePlaybackUrl(uri, fromPlayer = true)
        val id = MediaExtractor.extractMediaId(uri)
        Logger.i("Playing player=${player.javaClass.name} media=$id")
        return id
    }

    /** 手指下的视图本身或它的子树里，有哪块画面正被某个播放器输出。 */
    fun playerForView(view: View): Any? {
        val live = synchronized(players) { players.keys.toList() }
        if (live.isEmpty()) return null
        for (candidate in listOf(view) + videoLikeViews(view)) {
            val keys = outputKeys(candidate)
            live.firstOrNull { player -> currentOutputs(player).any { out -> keys.any { it === out } } }
                ?.let { return it }
        }
        return null
    }

    /**
     * 悬浮球 / 设置入口没有手指位置：在当前界面可见的画面里，优先正在播放的、面积最大的那块。
     */
    private fun selectPlayer(activity: Activity?): Any? {
        val decor = activity?.window?.decorView
        if (decor != null) {
            val onScreen = videoLikeViews(decor).filter { isOnScreen(it) }.mapNotNull { view ->
                val player = playerForView(view) ?: return@mapNotNull null
                Triple(player, isPlaying(player), view.width.toLong() * view.height.toLong())
            }
            val playingVisible = onScreen.filter { it.second }
            val pool = playingVisible.ifEmpty { onScreen }
            if (pool.isNotEmpty()) return pool.maxBy { it.third }.first
        }
        val live = synchronized(players) { players.keys.toList() }
        val playing = live.filter { isPlaying(it) }
        if (playing.size == 1) return playing[0]
        val rendered = lastRendered?.get()
        if (rendered != null && rendered in playing) return rendered
        return null
    }

    private fun hookNamedBuilders(classLoader: ClassLoader) {
        val names = listOf(
            "androidx.media3.exoplayer.ExoPlayer\$Builder",
            "com.google.android.exoplayer2.ExoPlayer\$Builder",
            "androidx.media3.exoplayer.ExoPlayerImpl",
            "com.google.android.exoplayer2.ExoPlayerImpl"
        )
        for (name in names) {
            try {
                val clazz = classLoader.loadClass(name)
                if (name.endsWith("Builder")) {
                    XposedBridge.hookAllMethods(clazz, "build", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            attach(param.result)
                        }
                    })
                } else {
                    XposedBridge.hookAllConstructors(clazz, attachCtor)
                }
                Logger.i("PlayerTracker hooked $name")
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookPlayerView(classLoader: ClassLoader) {
        for (name in listOf(
            "androidx.media3.ui.PlayerView",
            "com.google.android.exoplayer2.ui.PlayerView",
            "com.google.android.exoplayer2.ui.StyledPlayerView"
        )) {
            try {
                val clazz = classLoader.loadClass(name)
                XposedBridge.hookAllMethods(clazz, "setPlayer", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        attach(param.args.getOrNull(0))
                    }
                })
                Logger.i("PlayerTracker hooked $name.setPlayer")
            } catch (_: Throwable) {
            }
        }
    }

    private val attachCtor = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            attach(param.thisObject)
        }
    }

    /** 播放器当前持有的输出对象（字段类型为 Surface / SurfaceHolder / SurfaceView / TextureView / Object）。 */
    private fun currentOutputs(player: Any): List<Any> {
        val fields = outputFieldCache.getOrPut(player.javaClass) { outputFields(player.javaClass) }
        return fields.mapNotNull { field ->
            try {
                field.get(player)?.takeIf {
                    it is Surface || it is SurfaceHolder || it is View
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun outputFields(clazz: Class<*>): List<Field> {
        val out = mutableListOf<Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (field in c.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val t = field.type
                if (t == Any::class.java || t == Surface::class.java ||
                    SurfaceHolder::class.java.isAssignableFrom(t) || View::class.java.isAssignableFrom(t)
                ) {
                    try {
                        field.isAccessible = true
                        out.add(field)
                    } catch (_: Throwable) {
                    }
                }
            }
            c = c.superclass
        }
        return out
    }

    /** 播放器当前输出对应的视图（只有 TextureView / SurfaceView 能直接拿到）。 */
    private fun outputView(player: Any): View? = currentOutputs(player).firstOrNull { it is View } as? View

    /** 收集视图子树中所有 video-like 视图。 */
    private fun videoLikeViews(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v is SurfaceView || v is TextureView) out.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun outputKeys(output: View): List<Any> {
        val keys = mutableListOf<Any>(output)
        try {
            when (output) {
                is SurfaceView -> {
                    keys.add(output.holder)
                    output.holder.surface?.let { keys.add(it) }
                }
                is TextureView -> {
                    output.surfaceTexture?.let { keys.add(it) }
                }
            }
        } catch (_: Throwable) {
        }
        return keys
    }

    private fun addListener(player: Any) {
        if (listening.put(player, true) == true) return
        val loader = classLoader ?: player.javaClass.classLoader ?: return
        val add = findAddListener(player.javaClass) ?: return
        val listenerType = add.parameterTypes[0]
        if (!listenerType.isInterface) return
        try {
            val listener = Proxy.newProxyInstance(loader, arrayOf(listenerType)) { proxy, method, args ->
                try {
                    when (method.name) {
                        "onRenderedFirstFrame" -> {
                            lastRendered = WeakReference(player)
                            noteFromPlayer(player)
                        }
                        "onIsPlayingChanged" -> {
                            val playing = args?.getOrNull(0) as? Boolean ?: false
                            if (playing) {
                                lastRendered = WeakReference(player)
                                noteFromPlayer(player)
                            }
                        }
                        "equals" -> return@newProxyInstance proxy === args?.getOrNull(0)
                        "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                        "toString" -> return@newProxyInstance "TwitterPolishPlayerListener"
                    }
                } catch (t: Throwable) {
                    Logger.d("Player listener: ${t.message}")
                }
                defaultValue(method)
            }
            add.invoke(player, listener)
            Logger.d("Added playback listener on ${player.javaClass.name}")
        } catch (t: Throwable) {
            listening.remove(player)
            Logger.d("addListener failed: ${t.message}")
        }
    }

    private fun defaultValue(method: Method): Any? {
        return when (method.returnType) {
            Void.TYPE -> null
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }

    private fun findAddListener(clazz: Class<*>): Method? {
        val methods = clazz.methods
        methods.firstOrNull { it.name == "addListener" && it.parameterTypes.size == 1 }?.let { return it }
        return methods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.returnType == Void.TYPE &&
                m.parameterTypes[0].isInterface &&
                m.parameterTypes[0].methods.any { it.name == "onRenderedFirstFrame" }
        }
    }

    private fun noteFromPlayer(player: Any) {
        val uri = uriFromPlayer(player) ?: return
        // 预取 player 也会渲染第一帧/播放：只有输出画面真实可见才更新锁，
        // 否则预取会覆盖“正在播放”的 mediaId，导致长按下错视频。
        val view = outputView(player)
        if (view == null || !isOnScreen(view)) {
            Logger.d("Ignoring player event from non-visible output (prefetch): ${player.javaClass.simpleName}")
            return
        }
        MediaCache.notePlaybackUrl(uri, fromPlayer = true)
    }

    /** 视图是否真实显示且在屏幕内（与 selectPlayer 的可见性判断同源）。 */
    private fun isOnScreen(view: View): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val loc = IntArray(2)
        try {
            view.getLocationOnScreen(loc)
        } catch (_: Throwable) {
            return false
        }
        val dm = view.resources.displayMetrics
        return loc[0] < dm.widthPixels && loc[1] < dm.heightPixels &&
            loc[0] + view.width > 0 && loc[1] + view.height > 0
    }

    private fun isPlaying(player: Any): Boolean {
        return try {
            XposedHelpers.callMethod(player, "isPlaying") as? Boolean ?: false
        } catch (_: Throwable) {
            try {
                val ready = XposedHelpers.callMethod(player, "getPlayWhenReady") as? Boolean ?: false
                val state = XposedHelpers.callMethod(player, "getPlaybackState") as? Int ?: -1
                ready && state == 3
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun uriFromPlayer(player: Any): String? {
        try {
            uriFromMediaItem(XposedHelpers.callMethod(player, "getCurrentMediaItem"))?.let { return it }
        } catch (_: Throwable) {
        }
        // 混淆后 getCurrentMediaItem 只剩单字母名：按返回类型的结构找（MediaItem.localConfiguration.uri）。
        for (method in mediaItemGetters(player.javaClass)) {
            try {
                uriFromMediaItem(method.invoke(player))?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun mediaItemGetters(clazz: Class<*>): List<Method> = clazz.methods.filter { m ->
        m.parameterTypes.isEmpty() && !Modifier.isStatic(m.modifiers) &&
            (m.returnType.name.contains("MediaItem") || hasUriConfig(m.returnType))
    }

    /** 类型有一个公开实例字段，其类型又有公开实例字段 Uri —— MediaItem → LocalConfiguration → uri。 */
    private fun hasUriConfig(cls: Class<*>): Boolean {
        return try {
            instanceFields(cls).any { field -> instanceFields(field.type).any { it.type.name == "android.net.Uri" } }
        } catch (_: Throwable) {
            false
        }
    }

    private fun instanceFields(cls: Class<*>): List<Field> =
        cls.fields.filter { !Modifier.isStatic(it.modifiers) }

    fun uriFromMediaItem(mediaItem: Any?): String? {
        if (mediaItem == null) return null
        for (fieldName in listOf("localConfiguration", "playbackProperties")) {
            try {
                val config = XposedHelpers.getObjectField(mediaItem, fieldName) ?: continue
                val uri = XposedHelpers.getObjectField(config, "uri") ?: continue
                val s = uri.toString()
                if (s.isNotBlank()) return s
            } catch (_: Throwable) {
            }
        }
        try {
            for (field in instanceFields(mediaItem.javaClass)) {
                val value = field.get(mediaItem) ?: continue
                val uriField = instanceFields(value.javaClass).firstOrNull { it.type.name == "android.net.Uri" }
                    ?: continue
                val uri = uriField.get(value)?.toString()
                if (!uri.isNullOrBlank()) return uri
            }
        } catch (_: Throwable) {
        }
        return null
    }
}
