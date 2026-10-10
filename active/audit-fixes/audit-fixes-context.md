# 审计修复上下文

- 分支：`fix/audit-report-findings`（从 `main` 拉出，工作区里已有未提交的敏感内容开关）
- 报告：`docs/2026-09-22_audit-twitterpolish-report.md`
- 下载入口：`Downloader.openSource`、`HlsDownloader.open`、`HostOkHttp.open`
- 判断：`DownloadSecurity`
- 时间线：`NetworkTimelineHook.processResponseDirectly` / `rebuildResponse`
- 敏感字段：`TimelineProcessor.scrubSensitiveFlags`（未提交）
- 其它：`DownloadResolver.sameVideo`、`SettingsHook.currentActivity`、`ExoCacheProbe`、`MediaDownloadHook.notePlaybackIfVideo`

## 约束

- Java `URI.getHost()` 对 IPv6 带方括号。`InetAddress.getByName("::ffff:127.0.0.1")` 会得到 IPv4 回环。`fd00::/7` 不是 `isSiteLocalAddress`，要单独判断。
- 宿主 OkHttp 跟随重定向时不会去掉手动设置的 Cookie。关重定向必须在 `newBuilder().followRedirects(false)` 之后的客户端上发请求；做不到就退回自己控制的 `HttpURLConnection`。
- 单测不能对公网主机做 DNS。解析后的地址判断只在下载时调用。
