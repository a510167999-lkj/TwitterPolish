package com.polish.twitter.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.polish.twitter.core.Logger
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Downloader {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val notificationIdCounter = AtomicInteger(1000)

    private const val CHANNEL_ID = "twitter_polish_download_channel"
    private const val CHANNEL_NAME = "TwitterPolish 媒体下载"

    /**
     * 启动带实时通知栏进度的后台下载任务
     */
    fun download(context: Context, downloadUrl: String, fileName: String, isVideo: Boolean) {
        val cleanUrl = downloadUrl.trim()
        if (cleanUrl.isBlank()) {
            showToast(context, "下载链接无效")
            return
        }

        showToast(context, "📥 开始下载${if (isVideo) "视频" else "图片"}，请查看通知栏进度...")

        executor.execute {
            performDownload(context, cleanUrl, fileName, isVideo)
        }
    }

    private fun performDownload(context: Context, downloadUrl: String, fileName: String, isVideo: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = notificationIdCounter.incrementAndGet()

        // 适配 Android 8.0+ 通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Twitter 视频与图片的实时下载进度"
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val targetDirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val subDirectory = "Twitter"
        val parentDir = File(Environment.getExternalStoragePublicDirectory(targetDirType), subDirectory)
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val outputFile = File(parentDir, fileName)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在下载 Twitter ${if (isVideo) "视频" else "原图"}")
            .setContentText("连接中...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        notificationManager?.notify(notificationId, notificationBuilder.build())

        var connection: HttpURLConnection? = null
        try {
            // 清理 URL：去除可能导致鉴权问题的追踪参数（保留 tag 参数）
            val cleanedUrl = downloadUrl.let {
                // video.twimg.com 直链不需要额外参数清理，但需要处理 CDN 签名参数
                if (it.contains("video.twimg.com") && it.contains("?")) it.substringBefore("?") + "?" + it.substringAfter("?").split("&").filter { p -> p.startsWith("tag=") || p.startsWith("container=") }.joinToString("&")
                else it
            }.trimEnd('?').trimEnd('&')

            Logger.i("Starting download: $cleanedUrl")
            val url = URL(cleanedUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            // Twitter CDN 需要的请求头，防止 403
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            connection.setRequestProperty("Referer", "https://x.com/")
            connection.setRequestProperty("Accept", "video/webm,video/mp4,video/*,*/*;q=0.9")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=0-")
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299 && responseCode != 206) {
                throw RuntimeException("HTTP 响应错误: $responseCode (URL: $cleanedUrl)")
            }


            val totalBytes = connection.contentLength.toLong()
            val input = connection.inputStream
            val output = FileOutputStream(outputFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            var lastNotifyTime = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                // 限制通知更新频率，防止卡顿 UI
                if (now - lastNotifyTime > 300 && totalBytes > 0) {
                    lastNotifyTime = now
                    val progress = ((totalRead * 100) / totalBytes).toInt()
                    val readMb = String.format("%.1f", totalRead / (1024f * 1024f))
                    val totalMb = String.format("%.1f", totalBytes / (1024f * 1024f))

                    notificationBuilder
                        .setProgress(100, progress, false)
                        .setContentText("$progress% ($readMb MB / $totalMb MB)")
                    notificationManager?.notify(notificationId, notificationBuilder.build())
                }
            }

            output.flush()
            output.close()
            input.close()

            Logger.i("Download finished successfully: ${outputFile.absolutePath}")

            // 注册到系统相册与媒体库
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outputFile.absolutePath),
                arrayOf(if (isVideo) "video/mp4" else "image/jpeg"),
                null
            )

            // 下载完成通知
            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.fromFile(outputFile),
                    if (isVideo) "video/*" else "image/*"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val finishNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("✅ Twitter 媒体下载完成")
                .setContentText("文件保存在: ${parentDir.name}/$fileName")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager?.notify(notificationId, finishNotification)
            showToast(context, "✅ 下载完成: $fileName")

        } catch (e: Throwable) {
            Logger.e("Download failed: ${e.message}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val failNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("❌ 下载失败")
                .setContentText(e.localizedMessage ?: "网络异常")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .build()

            notificationManager?.notify(notificationId, failNotification)
            showToast(context, "❌ 下载失败: ${e.localizedMessage}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
