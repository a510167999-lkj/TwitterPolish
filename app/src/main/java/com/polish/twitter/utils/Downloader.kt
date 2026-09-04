package com.polish.twitter.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
     *
     * @param context  Context（用于创建通知与保存文件）
     * @param downloadUrl  视频/图片直链
     * @param fileName  文件名（含后缀）
     * @param isVideo  是否为视频
     */
    fun download(context: Context, downloadUrl: String, fileName: String, isVideo: Boolean) {
        val cleanUrl = downloadUrl.trim()
        if (cleanUrl.isBlank()) {
            showToast(context, "❌ 下载链接无效")
            return
        }

        showToast(context, "📥 开始下载${if (isVideo) "视频" else "图片"}，请查看通知栏进度...")
        Logger.i("Download requested: $cleanUrl -> $fileName")

        executor.execute {
            performDownload(context, cleanUrl, fileName, isVideo)
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

        notificationManager?.notify(notificationId, notificationBuilder.build())

        var connection: HttpURLConnection? = null
        var outputStream: java.io.OutputStream? = null
        var outputUri: Uri? = null

        try {
            Logger.i("Connecting to: $downloadUrl")
            val url = URL(downloadUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            // 与 X 客户端同款 UA，防 CDN 403
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Referer", "https://x.com/")
            connection.setRequestProperty("Accept", "video/mp4,video/webm,video/*,*/*;q=0.9")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.connect()

            val responseCode = connection.responseCode
            Logger.i("HTTP $responseCode for $downloadUrl")
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP 错误 $responseCode")
            }

            val totalBytes = connection.contentLengthLong
            val inputStream = connection.inputStream

            // ------- 根据 Android 版本选择不同的写入策略 -------
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: 使用 MediaStore API，无需 WRITE_EXTERNAL_STORAGE 权限
                val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
                val collection = if (isVideo)
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val values = ContentValues().apply {
                    put(if (isVideo) MediaStore.Video.Media.DISPLAY_NAME else MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(if (isVideo) MediaStore.Video.Media.MIME_TYPE else MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(
                        if (isVideo) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Images.Media.RELATIVE_PATH,
                        if (isVideo) "${Environment.DIRECTORY_MOVIES}/Twitter" else "${Environment.DIRECTORY_PICTURES}/Twitter"
                    )
                    put(if (isVideo) MediaStore.Video.Media.IS_PENDING else MediaStore.Images.Media.IS_PENDING, 1)
                }

                outputUri = context.contentResolver.insert(collection, values)
                    ?: throw RuntimeException("MediaStore 无法创建文件")
                outputStream = context.contentResolver.openOutputStream(outputUri)
                    ?: throw RuntimeException("无法打开 MediaStore 输出流")
            } else {
                // Android 9 及以下：直接写入外部存储
                val dirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val dir = File(Environment.getExternalStoragePublicDirectory(dirType), "Twitter")
                dir.mkdirs()
                val outFile = File(dir, fileName)
                outputUri = Uri.fromFile(outFile)
                outputStream = FileOutputStream(outFile)
            }

            // ------- 流式写入 + 通知进度 -------
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            var lastNotifyMs = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream!!.write(buffer, 0, bytesRead)
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
                    notificationManager?.notify(notificationId, notificationBuilder.build())
                }
            }

            outputStream!!.flush()
            outputStream.close()
            outputStream = null
            inputStream.close()

            // MediaStore: 将 IS_PENDING 清零，让相册可见
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                val cv = ContentValues().apply {
                    put(if (isVideo) MediaStore.Video.Media.IS_PENDING else MediaStore.Images.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri, cv, null, null)
            } else {
                // Legacy: 手动通知媒体库
                val filePath = outputUri?.path ?: return
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(filePath),
                    arrayOf(if (isVideo) "video/mp4" else "image/jpeg"),
                    null
                )
            }

            Logger.i("Download complete: $fileName (${totalRead / 1024} KB)")

            // ------- 完成通知 -------
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(outputUri, if (isVideo) "video/*" else "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
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
            notificationManager?.notify(notificationId, doneNotif)
            showToast(context, "✅ 下载完成")

        } catch (e: Throwable) {
            Logger.e("Download failed: ${e.message}", e)
            outputStream?.runCatching { close() }

            // 回滚 MediaStore 中的挂起条目
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
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
            notificationManager?.notify(notificationId, errNotif)
            showToast(context, "❌ 下载失败: ${e.localizedMessage}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun ensureNotificationChannel(nm: NotificationManager?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "TwitterPolish 视频与图片下载进度"
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
