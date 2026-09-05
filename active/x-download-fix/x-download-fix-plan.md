# X 下载修复方案

## 前提

Antigravity 会话 `e2b19bae`（开发 X 功能增强插件）在额度耗尽前停在「进入播放器后长按没反应」。
已验证：X 12.20.5 全程只有 `com.x.android.main.MainActivity`；OkHttp 抓到的 `video.twimg.com/.../vid/avc1/0/0/WxH/*.mp4` 是 DASH init（`ftyp...cmf2dash`，约 900 字节），不是可播完整视频。

## 关键决策

1. **下载源只用 GraphQL `video_info.variants` 里的 progressive MP4**，按 tweetId / mediaId 索引。播放器请求只用来识别「当前在播哪条」，绝不拿来当文件。
2. **长按走 `Activity.dispatchTouchEvent`**，不按 Activity 类名过滤，不 `setOnLongClickListener`（会吞单击，挡进播放器）。
3. **下载优先复用宿主 OkHttpClient**（Cookie / TLS 与播放同一套），写入前检查 `ftyp`，拒绝 dash init。
4. 不碰 LSPosed 数据库、不重启手机。装包后 `am force-stop com.twitter.android`。

## 验收

- 复制推文链接 / 播放器画面长按 / 悬浮球，能下到可播的完整 MP4 或 `name=orig` 原图。
- 瀑布流 inline 视频单击仍能进播放器。
- 单测覆盖：DASH URL 拒绝、按 tweetId 解析、ftyp 校验。
- `assembleDebug` 成功并装到 `f3d1467`。
