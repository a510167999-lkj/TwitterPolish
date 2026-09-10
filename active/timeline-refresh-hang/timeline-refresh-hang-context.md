# 相关文件

- `hooks/NetworkTimelineHook.kt` — 消耗 body 后 return null；拦截器 + RealCall 双处理
- `processor/TimelineProcessor.kt` — 有 entries 就改写，第二次 toString 相等触发 return null
- `core/Constants.kt` — TIMELINE_ENDPOINTS 之外仍匹配全部 graphql
- `MainHook.kt` — Application.attach 无 once-guard，可能注入两个拦截器
- `docs/audit-report.md` — 记录本轮回归

设备：`f3d1467`，包名 `com.twitter.android`。不重启手机，不改 LSPosed 库。
