# 审计修复方案

按 `docs/2026-09-22_audit-twitterpolish-report.md` 的修复顺序改当前工作区。不扩大成新功能。

## 决策

- 凭证下载只走 https，关闭自动重定向。每一跳重新做白名单、字面量/解析后的地址判断。新主机不在白名单就停止。
- HLS 分片必须和最初播放列表是同一个主机。
- 黑名单只认 IP 字面量和 localhost 一类名字，不再用 `10.` 这种前缀。解析发生在真正连接前；DNS 失败按拒绝处理。
- 时间线 `bytes()` 之后只能交回重建后的响应，失败则抛 `IOException`。`RealCall` 钩子不再把这个异常吃掉。
- 敏感内容开关只删 `sensitive_media_warning` / `ext_sensitive_media_warning`，并把 `possibly_sensitive*` 置 false。其他插屏保留。没改到字段就不 `toString()`。
- `sameVideo` 在任一侧没有 mediaId 时返回 false。
- OkHttpClient 只从白名单主机的请求捕获；之后若出现 api/x/twitter 主机上的客户端，可以替换先前的 CDN 客户端，一旦拿到 API 客户端就不再换。
- 文件名去掉路径字符。SQL 表名必须是标识符。播放 URL 用 twimg 主机判断。带完整 URL 的日志降到 debug。设置页 Activity 改弱引用。
- 不在模块 Manifest 里加 `POST_NOTIFICATIONS`。通知是用 X 进程的 Context 发的，模块清单上的权限不会作用到宿主。

## 验收

- `DownloadSecurityTest` 覆盖 `[::1]`、`172.16.0.1`、`fd00::1`、http 不带凭证、重定向拼接。
- 敏感内容测试：媒体警告被清掉，非媒体插屏还在，没有标志时返回原字符串。
- `testDebugUnitTest` 通过。
