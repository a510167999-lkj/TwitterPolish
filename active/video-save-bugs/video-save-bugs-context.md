# 相关文件

- `processor/MediaExtractor.kt` — `pickBestProgressiveMp4 ?: pickBestHls` 丢掉 master
- `processor/MediaCache.kt` — `currentMediaId` 任意 CDN 请求都会改；`prefer()` 偏向 progressive
- `hooks/MediaDownloadHook.kt` — 长按保存；HLS 变体 playlist 被当成下载源
- `utils/ExoCacheProbe.kt` — `ifEmpty { keys }` 在 id 未命中时串到上一条
- `ui/FloatingMenuManager.kt` / `SettingsDialog.kt` — 各自一套 resolve，不升级 HLS
- `utils/HlsDownloader.kt` — 给 master 才能选最高分辨率

设备：`f3d1467` OnePlus 13 / Android 16，包名 `com.twitter.android`。
