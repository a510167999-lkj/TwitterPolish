package com.polish.twitter.utils

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.polish.twitter.core.DexKitManager
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.MediaCache
import com.polish.twitter.processor.MediaExtractor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 沉浸式播放器同时活着多个 ExoPlayer。
 * 长按必须跟「手指下面那块 Surface」绑定，不能信任意 CDN 请求或上一个渲染过的播放器。
 */
object PlayerPlaybackTracker {

    private val players = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val hookedClasses = Collections.synchronizedSet(mutableSetOf<String>())
    private val listening = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val outputToPlayer = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val playerToOutput = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())

    @Volatile
    private var lastRendered: WeakReference<Any>? = null

    @Volatile
    private var classLoader: ClassLoader? = null

    fun hook(classLoader: ClassLoader) {
        this.classLoader = classLoader
        hookNamedBuilders(classLoader)
        hookDexKitImpl(classLoader)
        hookExoInterface(classLoader)
        hookPlayerView(classLoader)
    }

    fun attach(player: Any?) {
        if (player == null) return
        players[player] = true
        hookConcrete(player.javaClass)
        addListener(player)
    }

    /**
     * 长按：只认按到的那块画面所属播放器。认不出返回 null，由调用方决定要不要降级。
     */
    fun syncViewIntoCache(view: View): String? {
        val player = playerForView(view) ?: return null
        val uri = uriFromPlayer(player) ?: return null
        MediaCache.notePlaybackUrl(uri, fromPlayer = true)
        lastRendered = WeakReference(player)
        val id = MediaExtractor.extractMediaId(uri)
        Logger.i("Long-press view=${view.javaClass.simpleName} ${view.width}x${view.height} media=$id")
        return id
    }

    fun syncPlayingIntoCache(): String? {
        val player = selectPlayer() ?: return null
        val uri = uriFromPlayer(player) ?: return null
        MediaCache.notePlaybackUrl(uri, fromPlayer = true)
        val id = MediaExtractor.extractMediaId(uri)
        Logger.i("Playing player=${player.javaClass.name} media=$id")
        return id
    }

    fun playerForView(view: View): Any? {
        var node: View? = view
        while (node != null) {
            lookupViewKeys(node)?.let { return it }
            val parent = node.parent
            node = parent as? View
        }
        return findInChildren(view)
    }

    private fun lookupViewKeys(view: View): Any? {
        lookupOutput(view)?.let { return it }
        if (view is SurfaceView) {
            lookupOutput(view.holder)?.let { return it }
            try {
                view.holder.surface?.let { lookupOutput(it) }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        if (view is TextureView) {
            try {
                view.surfaceTexture?.let { lookupOutput(it) }?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun findInChildren(view: View): Any? {
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            lookupViewKeys(child)?.let { return it }
            findInChildren(child)?.let { return it }
        }
        return null
    }

    private fun lookupOutput(key: Any?): Any? {
        if (key == null) return null
        return outputToPlayer[key]?.get()
    }

    private fun selectPlayer(): Any? {
        val live = synchronized(players) { players.keys.toList() }
        val onScreen = live.mapNotNull { player ->
            val view = outputView(player) ?: return@mapNotNull null
            if (!isOnScreen(view)) return@mapNotNull null
            Triple(player, isPlaying(player), view.width.toLong() * view.height.toLong())
        }
        val playingVisible = onScreen.filter { it.second }
        val pool = if (playingVisible.isNotEmpty()) playingVisible else onScreen
        if (pool.isNotEmpty()) return pool.maxBy { it.third }.first

        val playing = live.filter { isPlaying(it) }
        if (playing.size == 1) return playing[0]
        val rendered = lastRendered?.get()
        if (rendered != null && rendered in playing) return rendered
        if (rendered != null && rendered in live) return rendered
        return playing.lastOrNull()
    }

    private fun outputView(player: Any): View? {
        val output = playerToOutput[player]?.get() ?: return null
        return output as? View
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
                    hookConcrete(clazz)
                }
                Logger.i("PlayerTracker hooked $name")
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookDexKitImpl(classLoader: ClassLoader) {
        val desc = DexKitManager.getMethodDescriptor(DexKitManager.KEY_EXOPLAYER_IMPL) ?: return
        try {
            val clazz = classLoader.loadClass(desc.first)
            XposedBridge.hookAllConstructors(clazz, attachCtor)
            hookConcrete(clazz)
            Logger.i("PlayerTracker hooked DexKit impl ${desc.first}")
        } catch (t: Throwable) {
            Logger.w("PlayerTracker DexKit impl hook failed: ${t.message}")
        }
    }

    private fun hookExoInterface(classLoader: ClassLoader) {
        for (name in listOf(
            "androidx.media3.exoplayer.ExoPlayer",
            "com.google.android.exoplayer2.ExoPlayer",
            "androidx.media3.common.Player"
        )) {
            try {
                hookConcrete(classLoader.loadClass(name))
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
                        val view = param.thisObject as? View ?: return
                        val player = param.args.getOrNull(0)
                        if (player == null) {
                            unbindOutput(view)
                        } else {
                            attach(player)
                            bindOutput(player, view)
                        }
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

    private fun hookConcrete(clazz: Class<*>) {
        if (!hookedClasses.add(clazz.name)) return
        try {
            XposedBridge.hookAllMethods(clazz, "setMediaItem", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attach(param.thisObject)
                }
            })
        } catch (_: Throwable) {
        }
        hookVideoOutputs(clazz)
    }

    private fun hookVideoOutputs(clazz: Class<*>) {
        val seen = mutableSetOf<Method>()
        for (method in clazz.methods) {
            if (method.parameterTypes.size != 1) continue
            if (!seen.add(method)) continue
            val n = method.name
            val arg = method.parameterTypes[0]
            val isClear = n.contains("clear", ignoreCase = true)
            val knownSet = n == "setVideoSurface" || n == "setVideoSurfaceView" ||
                n == "setVideoTextureView" || n == "setVideoSurfaceHolder" ||
                n == "setVideoOutput"
            val byType = arg == Surface::class.java ||
                arg == SurfaceView::class.java ||
                arg == TextureView::class.java ||
                SurfaceHolder::class.java.isAssignableFrom(arg)
            if (!knownSet && !isClear && !byType) continue
            if (!knownSet && !isClear && n.length > 4 && !n.contains("Video", ignoreCase = true) &&
                !n.contains("Surface", ignoreCase = true) && !n.contains("Texture", ignoreCase = true)
            ) {
                continue
            }
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val player = param.thisObject
                        attach(player)
                        val output = param.args.getOrNull(0)
                        if (output == null || isClear) {
                            unbindPlayer(player)
                        } else {
                            bindOutput(player, output)
                        }
                    }
                })
            } catch (_: Throwable) {
            }
        }
    }

    private fun bindOutput(player: Any, output: Any) {
        unbindPlayer(player)
        playerToOutput[player] = WeakReference(output)
        for (key in outputKeys(output)) {
            outputToPlayer[key] = WeakReference(player)
        }
        // 子树扩散：X 自研播放器视图树里 SurfaceView 与 PlayerView 可能是兄弟节点，
        // 只登记自身 key 会导致长按命中的 view 查不到映射、错误回落全局播放器选择。
        // 扩散到整个子树的 video-like 视图（不覆盖已有映射，兄弟 Surface 属其它 player 时不抢）。
        if (output is View) {
            for (child in videoLikeViews(output)) {
                for (key in outputKeys(child)) {
                    if (outputToPlayer[key]?.get() == null) {
                        outputToPlayer[key] = WeakReference(player)
                    }
                }
            }
        }
    }

    private fun unbindPlayer(player: Any) {
        val old = playerToOutput.remove(player)?.get()
        if (old != null) {
            for (key in outputKeys(old)) {
                val mapped = outputToPlayer[key]?.get()
                if (mapped === player) outputToPlayer.remove(key)
            }
            // 同时清掉该 player 扩散登记的子树 key
            if (old is View) {
                for (child in videoLikeViews(old)) {
                    for (key in outputKeys(child)) {
                        val mapped = outputToPlayer[key]?.get()
                        if (mapped === player) outputToPlayer.remove(key)
                    }
                }
            }
        }
    }

    private fun unbindOutput(output: Any) {
        val player = outputToPlayer[output]?.get()
        for (key in outputKeys(output)) {
            outputToPlayer.remove(key)
        }
        if (player != null) unbindPlayer(player)
    }

    /** 收集视图子树中所有 video-like 视图（供绑定扩散与查找）。 */
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

    private fun outputKeys(output: Any): List<Any> {
        val keys = mutableListOf(output)
        try {
            when (output) {
                is SurfaceView -> {
                    keys.add(output.holder)
                    output.holder.surface?.let { keys.add(it) }
                }
                is TextureView -> {
                    output.surfaceTexture?.let { keys.add(it) }
                }
                is SurfaceHolder -> {
                    output.surface?.let { keys.add(it) }
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
        val item = try {
            XposedHelpers.callMethod(player, "getCurrentMediaItem")
        } catch (_: Throwable) {
            try {
                findGetCurrentMediaItem(player.javaClass)?.invoke(player)
            } catch (_: Throwable) {
                null
            }
        } ?: return null
        return uriFromMediaItem(item)
    }

    private fun findGetCurrentMediaItem(clazz: Class<*>): Method? {
        clazz.methods.firstOrNull {
            it.name == "getCurrentMediaItem" && it.parameterTypes.isEmpty()
        }?.let { return it }
        return clazz.methods.firstOrNull { m ->
            m.parameterTypes.isEmpty() &&
                !Modifier.isStatic(m.modifiers) &&
                (m.returnType.name.contains("MediaItem") || hasUriConfig(m.returnType))
        }
    }

    private fun hasUriConfig(cls: Class<*>): Boolean {
        return try {
            cls.declaredFields.any { field ->
                field.type.declaredFields.any { it.type.name == "android.net.Uri" }
            }
        } catch (_: Throwable) {
            false
        }
    }

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
            for (field in mediaItem.javaClass.fields) {
                val value = field.get(mediaItem) ?: continue
                val uriField = value.javaClass.fields.firstOrNull { it.type.name == "android.net.Uri" }
                    ?: continue
                val uri = uriField.get(value)?.toString()
                if (!uri.isNullOrBlank()) return uri
            }
        } catch (_: Throwable) {
        }
        return null
    }
}
