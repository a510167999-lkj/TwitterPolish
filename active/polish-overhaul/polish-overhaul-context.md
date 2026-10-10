# polish-overhaul 上下文

## 相关文件

- 入口：`MainHook.kt`
- 网络/时间线：`hooks/NetworkTimelineHook.kt`、`processor/TimelineProcessor.kt`、`core/Constants.kt`
- 将删除：`hooks/TimelineFilterHook.kt`、`hooks/SettingsHook.kt`、`hooks/TabSwitcherHook.kt`、`utils/SnowflakeUtils.kt`（如无其它引用）
- 入口 UI：`hooks/LogoLongPressHook.kt`、`ui/FloatingMenuManager.kt`、`ui/SettingsDialog.kt`、`dev/polish/ui/PolishUi.java`
- 媒体：`hooks/MediaDownloadHook.kt`、`utils/PlayerPlaybackTracker.kt`、`utils/Downloader.kt`、`utils/HostOkHttp.kt`
- DexKit：`core/DexKitManager.kt`（SCHEMA 需 +1 让旧缓存失效）
- 测试：`app/src/test/java/com/polish/twitter/*.kt`

## 环境

- JDK17：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- SDK：`/opt/homebrew/share/android-commandlinetools`
- 真机：`f3d1467`，Android 16，LSPosed v2.2.0 (7854)，X 12.33.0
- 12.33 反编译：scratchpad `x1233/src`（不入库）

## 约束 / 风险

- 设备红线：不重启、不写 LSPosed 数据库；刷新只用 `adb install` + `am force-stop com.twitter.android`。
- 手机锁屏时无法做 UI 验证，需要用户解锁。
- 默认关注依赖 X 内部 DataStore 逻辑，跨版本可能变；必须 DexKit 定位 + 结构校验，失败只记日志不乱挂。
- 删除重排后，旧版设置项 `pref_enable_chrono_sort` 残留在宿主 prefs 中，无害，不迁移。
