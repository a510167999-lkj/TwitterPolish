# 视频保存：画质过低 + 滑到下一条仍存上一条

## 前提

X 12.x 沉浸式播放器是多个 ExoPlayer 池化实例。上一条会继续缓冲，下一条常从缓存起播。当前 `currentMediaId` 来自任意 CDN 请求，保存时 `ExoCacheProbe` 再按这个过期 id 过滤。

GraphQL `video_info.variants` 里 progressive MP4 通常最高 720p；1080p 只在 HLS master。现逻辑 `progressive ?: hls`，且 chooser 有 progressive 就不再升级 HLS。

## 决策

1. 下载源：有 HLS master 就走 master（HlsDownloader 选最高档），progressive 仅作无 HLS 时的兜底。
2. 「正在播放」以 ExoPlayer 为准（首帧 / surface / isPlaying），CDN 预取与上一条分片不得覆盖。
3. `ExoCacheProbe` 已知 playing mediaId 时，禁止 fallback 到其它视频的 cache key。
4. 不重启手机，不改 LSPosed 库。装包后 `am force-stop com.twitter.android`。

## 验收

- 有 1080p HLS 的推文，保存结果不是 270p/480p/720p progressive。
- 滑到下一条立刻保存，文件对应当前画面，不是上一条。
- 单测覆盖：master 优先、player lock 忽略预取、probe 不串 id。
- `assembleDebug` 并装到 `f3d1467`。
