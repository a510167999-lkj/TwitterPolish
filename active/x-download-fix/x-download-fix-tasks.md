# 任务清单

- [x] 建 `fix/x-video-download` 分支与本目录
- [x] `MediaExtractor`：DASH 识别、mediaId、分享链接 tweetId、递归收集媒体
- [x] `MediaCache`：按 tweetId/mediaId 索引，播放 URL 只更新「当前在播」
- [x] `Downloader` + `HostOkHttp`：宿主客户端、ftyp 校验、MediaStore
- [x] `MediaDownloadHook`：dispatchTouchEvent 长按大画面；剪贴板按 tweetId 解析
- [x] 去掉 `TimelineProcessor` 对 `latestVideoUrl` 的覆盖；GraphQL 写入 cache
- [x] 悬浮球 / 设置改读 `MediaCache`
- [x] 单测（DASH 拒绝、按 ID 解析、ftyp）
- [x] `assembleDebug` + 真机安装 + force-stop X
- [x] HLS 合成：ExoPlayer 缓存取 m3u8，下载 CMAF 音视频分片后 MediaMuxer 合并
