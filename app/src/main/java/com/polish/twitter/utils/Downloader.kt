package com.polish.twitter.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.polish.twitter.core.Logger
import com.polish.twitter.processor.ExtractedMedia
import com.polish.twitter.processor.MediaExtractor
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Downloader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val notificationIdCounter = AtomicInteger(1000)

    private const val CHANNEL_ID = "twitter_polish_download_channel"
    private const val CHANNEL_NAME = "TwitterPolish 媒体下载"
    private const val MIN_VIDEO_BYTES = 50_000L

    fun download(context: Context, media: ExtractedMedia) {
        download(context, media.url, media.fileName, media.isVideo, media.hlsAudioUrl)
    }

    fun download(
        context: Context,
        downloadUrl: String,
        fileName: String,
        isVideo: Boolean,
        hlsAudioUrl: String = ""
    ) {
        val cleanUrl = downloadUrl.trim()
        val safeName = DownloadSecurity.safeDownloadFileName(fileName)
        if (!DownloadSecurity.isHttps(cleanUrl) || DownloadSecurity.isBlockedHost(cleanUrl)) {
            Logger.d("Refusing unsafe download")
            showToast(context, "❌ 下载链接不安全，已拦截")
            return
        }
        if (MediaExtractor.isHlsPlaylistUrl(cleanUrl)) {
            showToast(context, "📥 正在从播放流合成完整视频…")
            Logger.d("HLS download requested")
            executor.execute {
                performHlsDownload(context.applicationContext, cleanUrl, hlsAudioUrl, safeName)
            }
            return
        }
        if (isVideo && MediaExtractor.isDashSegmentUrl(cleanUrl)) {
            Logger.d("Refusing DASH segment URL")
            showToast(context, "❌ 这是播放器分片。请等视频开播后再长按，将改为合成完整视频")
            return
        }

        showToast(context, "📥 开始下载${if (isVideo) "视频" else "图片"}…")
        Logger.d("Download requested: $safeName")

        executor.execute {
            performDownload(context.applicationContext, cleanUrl, safeName, isVideo)
        }
    }

    private fun performDownload(context: Context, downloadUrl: String, fileName: String, isVideo: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = notificationIdCounter.incrementAndGet()
        ensureNotificationChannel(notificationManager)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在下载 Twitter ${if (isVideo) "视频" else "原图"}")
            .setContentText("连接中...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        tryNotify(notificationManager, notificationId, notificationBuilder.build())

        var outputStream: java.io.OutputStream? = null
        var outputUri: Uri? = null
        var sourceClose: (() -> Unit)? = null

        try {
            Logger.d("Connecting download")
            val source = openSource(downloadUrl)
            sourceClose = source.close
            val inputStream = source.stream
            var totalBytes = source.contentLength

            if (isVideo && totalBytes in 1 until MIN_VIDEO_BYTES) {
                throw RuntimeException("CDN 返回的是 ${totalBytes}B 分片，不是完整视频")
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(fileName, isVideo))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) "${Environment.DIRECTORY_MOVIES}/Twitter" else "${Environment.DIRECTORY_PICTURES}/Twitter"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isVideo)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            outputUri = context.contentResolver.insert(collection, values)
                ?: throw RuntimeException("MediaStore 无法创建文件")
            outputStream = context.contentResolver.openOutputStream(outputUri)
                ?: throw RuntimeException("无法打开 MediaStore 输出流")

            val buffer = ByteArray(16 * 1024)
            // 打开输出流后立即转局部变量，全程使用局部引用（此处经流程分析已保证非空）
            val out = outputStream
            var bytesRead: Int
            var totalRead = 0L
            var lastNotifyMs = 0L
            var headerChecked = !isVideo
            val headerBuf = ByteArray(32)
            var headerLen = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (!headerChecked) {
                    val copy = minOf(bytesRead, headerBuf.size - headerLen)
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, headerBuf, headerLen, copy)
                        headerLen += copy
                    }
                    if (headerLen >= 12) {
                        if (!MediaExtractor.isPlayableMp4Header(headerBuf.copyOf(headerLen))) {
                            throw RuntimeException("文件不是完整 MP4（播放器分片），已中止")
                        }
                        headerChecked = true
                    }
                }
                out.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastNotifyMs > 300 && totalBytes > 0) {
                    lastNotifyMs = now
                    val pct = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                    val readMb = "%.1f".format(totalRead / 1_048_576f)
                    val totalMb = "%.1f".format(totalBytes / 1_048_576f)
                    notificationBuilder
                        .setProgress(100, pct, false)
                        .setContentText("$pct% ($readMb / $totalMb MB)")
                    tryNotify(notificationManager, notificationId, notificationBuilder.build())
                }
            }

            if (isVideo && !headerChecked) {
                throw RuntimeException("视频数据过短，不是完整文件")
            }
            if (isVideo && totalRead < MIN_VIDEO_BYTES) {
                throw RuntimeException("只下到 ${totalRead}B，不是完整视频")
            }

            out.flush()
            out.close()
            inputStream.close()

            context.contentResolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )

            Logger.i("Download complete: $fileName (${totalRead / 1024} KB)")

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputUri, mimeType(fileName, isVideo))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, notificationId, viewIntent, pendingFlags)

            val doneNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("✅ Twitter 媒体下载完成")
                .setContentText("已保存到 ${if (isVideo) "Movies" else "Pictures"}/Twitter/$fileName")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            tryNotify(notificationManager, notificationId, doneNotif)
            showToast(context, "✅ 下载完成（${totalRead / 1024} KB）")

        } catch (e: Throwable) {
            Logger.e("Download failed: ${e.message}", e)
            outputStream?.runCatching { close() }

            if (outputUri != null) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (_: Throwable) {}
            }

            val errNotif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("❌ 下载失败")
                .setContentText(e.localizedMessage ?: "网络异常，请检查连接")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
            tryNotify(notificationManager, notificationId, errNotif)
            showToast(context, "❌ 下载失败: ${e.localizedMessage}")
        } finally {
            sourceClose?.invoke()
        }
    }

    private fun performHlsDownload(context: Context, playlistUrl: String, audioUrl: String, fileName: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = notificationIdCounter.incrementAndGet()
        ensureNotificationChannel(notificationManager)
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在合成 Twitter 视频")
            .setContentText("连接播放列表…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        tryNotify(notificationManager, notificationId, notificationBuilder.build())

        val workDir = File(context.cacheDir, "tp_hls_${System.currentTimeMillis()}")
        val muxed = File(workDir, fileName)
        var outputUri: Uri? = null
        var outputStream: java.io.OutputStream? = null
        try {
            HlsDownloader.downloadToFile(
                playlistUrl = playlistUrl,
                audioPlaylistUrl = audioUrl,
                output = muxed,
                workDir = workDir
            ) { msg ->
                notificationBuilder.setContentText(msg)
                tryNotify(notificationManager, notificationId, notificationBuilder.build())
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Twitter")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = context.contentResolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw RuntimeException("MediaStore 无法创建文件")
            outputStream = context.contentResolver.openOutputStream(outputUri)
                ?: throw RuntimeException("无法打开输出流")
            HlsDownloader.copyFileTo(muxed, outputStream)
            outputStream.close()
            outputStream = null
            context.contentResolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )

            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputUri, "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(context, notificationId, viewIntent, pendingFlags)
            tryNotify(
                notificationManager,
                notificationId,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("✅ Twitter 视频已保存")
                    .setContentText("Movies/Twitter/$fileName（${muxed.length() / 1024} KB）")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
            showToast(context, "✅ 下载完成（${muxed.length() / 1024} KB）")
        } catch (e: Throwable) {
            Logger.e("HLS download failed: ${e.message}", e)
            if (outputUri != null) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (_: Throwable) {}
            }
            tryNotify(
                notificationManager,
                notificationId,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("❌ 下载失败")
                    .setContentText(e.localizedMessage ?: "合成失败")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build()
            )
            showToast(context, "❌ 下载失败: ${e.localizedMessage}")
        } finally {
            outputStream?.runCatching { close() }
            workDir.deleteRecursively()
        }
    }

    private data class Source(
        val stream: InputStream,
        val contentLength: Long,
        val close: () -> Unit
    )

    private fun openSource(downloadUrl: String): Source {
        val trusted = DownloadSecurity.mayAttachCredentials(downloadUrl)
        val opened = DownloadClient.open(downloadUrl, trusted)
        Logger.d("Download opened, credentials=$trusted length=${opened.contentLength}")
        return Source(opened.stream, opened.contentLength, opened.close)
    }

    /** 原图可能是 PNG；一律写 image/jpeg 会让相册按错误格式解析。 */
    internal fun mimeType(fileName: String, isVideo: Boolean): String = when {
        isVideo -> "video/mp4"
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }

    private fun tryNotify(nm: NotificationManager?, id: Int, notification: android.app.Notification) {
        try {
            nm?.notify(id, notification)
        } catch (t: Throwable) {
            Logger.d("Notification dropped (POST_NOTIFICATIONS?): ${t.message}")
        }
    }

    private fun ensureNotificationChannel(nm: NotificationManager?) {
        if (nm != null) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "TwitterPolish 视频与图片下载进度"
                enableVibration(false)
                enableLights(false)
            }
            try {
                nm.createNotificationChannel(ch)
            } catch (t: Throwable) {
                Logger.d("createNotificationChannel: ${t.message}")
            }
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
