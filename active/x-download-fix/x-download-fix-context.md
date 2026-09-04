# 相关文件与约束

## 代码

- `app/src/main/java/com/polish/twitter/hooks/MediaDownloadHook.kt` — 长按 / URL 捕获（当前按混淆 Activity 名过滤，永远不命中）
- `app/src/main/java/com/polish/twitter/utils/Downloader.kt` — HttpURLConnection + MediaStore
- `app/src/main/java/com/polish/twitter/processor/MediaExtractor.kt` — 变体提取
- `app/src/main/java/com/polish/twitter/processor/TimelineProcessor.kt` — `cacheMediaFromEntry` 会把 `latestVideoUrl` 覆盖成时间线里最后一条视频
- `app/src/main/java/com/polish/twitter/hooks/NetworkTimelineHook.kt` — GraphQL 拦截
- `app/src/main/java/com/polish/twitter/ui/FloatingMenuManager.kt` / `SettingsDialog.kt` — 读 `latestVideoUrl`

## 设备

- `f3d1467` OnePlus 13 / Android 16，X `12.20.5-prod.01` (`312205001`)
- `com.twitter.android` 已授 `READ_MEDIA_VIDEO`，**未授** `POST_NOTIFICATIONS`（通知栏进度会被系统丢掉）
- 禁止：重启、热改 LSPosed 活库

## 实测证据

- 播放器 URL `.../vid/avc1/0/0/1920x1078/*.mp4` → HTTP 200、`content-length: 904`、header `ftyp iso5 cmf2 dash`
- 同 URL 无 Cookie 在电脑 curl 可访问，但文件本身不是完整视频
