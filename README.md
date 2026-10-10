# TwitterPolish

X（Twitter）Android 客户端的 LSPosed 模块：去掉时间线广告与推荐、默认进入「正在关注」、下载视频与原图。

其他版本靠 DexKit 特征扫描适配，扫描不到的功能会自动停用并在设置面板里显示，不会乱挂。

## X 12.33.0 真机状态（2026-10-10，LSPosed v2.2.0，Android 16）

| 功能 | 状态 |
| --- | --- |
| 默认进入「正在关注」 | ✅ 已验证：手动选「为你推荐」后冷启动仍进入「正在关注」 |
| 长按顶部 X 图标打开设置 | ✅ 已验证 |
| 悬浮球（显示 / 冷启动重新挂载 / 菜单 / 隐藏） | ✅ 已验证 |
| 音量键不再被拦截 | ✅ 已验证 |
| 全屏播放器长按下载视频 | ✅ 已验证：识别手指下的播放器，HLS 合成 MP4（720×1280 + AAC，时长与播放器一致） |
| 复制链接下载原图 | ✅ 已验证：多图推文逐张保存 `name=orig` 原图（3072×4096） |
| 去广告与推荐 | ⚠️ 已适配 12.33 响应格式，185 条真实条目无误删；尚未抓到真实广告样本验证删除效果 |

## 功能

### 去广告与推荐

时间线走 OkHttp，但 X 优先显示本地缓存，下拉刷新后才会发请求。模块在 OkHttp 上拦截时间线类 GraphQL 响应（首页、列表、个人页、推文详情、书签、搜索），在客户端渲染之前删除以下条目：

- 推广推文：`entryId` 带 promoted 前缀，`tweet_display_type` 为 `*PromotedTweet`，或任意层级出现**有值**的 `promotedMetadata` / `promoted_metadata` 等推广字段；
- 推荐关注、话题与趋势推荐模块。

只删除，不改其余条目的顺序。推文详情页的会话结构、书签的收藏顺序、搜索结果的排序都由服务端决定。

可选「自动展示敏感内容」：只清除敏感媒体警告字段，「不可用」「年龄限制」等其他插屏保留。

### 默认进入「正在关注」

关注流由服务端按时间倒序返回，本身就是纯时间线。X 有两处会把你送回「为你推荐」：

1. 首页创建时，读取上次停留的标签，读不到就默认「为你推荐」；
2. 远程开关 `android_home_back_open_for_you_on_app_restart_enabled`：在后台待了一段时间后回来，强制切回「为你推荐」。

模块用 DexKit 定位这两处（锚点分别是字符串 `PreferredTabRepo` 和上述开关名），并让它们都落到「正在关注」。从外部链接指定了时间线的情况不受影响。修改该开关后需要重启 X 才生效。

### 视频与原图下载

- 视频：长按正在播放的视频画面，弹出下载菜单。模块读出手指下那块画面所属的播放器（12.33 起播放器类被混淆，用 DexKit 按 `ExoPlayerImpl` 日志 tag 定位），从播放器和 GraphQL 中找到这条视频的 HLS master 播放列表，选择最高分辨率的流，并用 MediaMuxer 把音视频合成为完整 MP4。认不出是哪条视频时直接提示，不会猜一条去下。
- 图片：把 `pbs.twimg.com` 链接改写为 `name=orig`，下载原图。
- 复制推文链接时，如果这条推文已经刷到过、并且有媒体，会弹出下载选择；纯文字推文不会弹。
- 文件保存在 `Movies/Twitter/` 和 `Pictures/Twitter/`，通知栏显示下载进度。
- 下载只允许 https 和 X 的媒体域名，凭据只会发给 X 自己的主机，重定向每一跳都重新校验。

### 入口

- **悬浮球**：可以拖动，松手后吸附到屏幕边缘；点击后可下载当前媒体或打开设置。在设置里可以隐藏。
- **长按顶部 X 图标**：打开设置。

## 安装

1. 安装 APK，在 LSPosed 管理器中启用 TwitterPolish，并勾选作用域 X（`com.twitter.android`）。
2. 强行停止 X 后重新打开。
3. X 升级后第一次启动时，模块会在后台重新扫描（几秒钟），扫描结果按 X 版本缓存。

LSPatch 免 Root 方式未经测试。

## 构建与测试

需要 JDK 17 和 Android SDK 34。

```bash
./gradlew testDebugUnitTest   # 单元测试
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## 代码结构

```text
app/src/main/java/com/polish/twitter/
├── MainHook.kt                 # 入口：只接管 X 主进程，按功能隔离安装
├── core/
│   ├── DexKitManager.kt        # 特征扫描 + 结构校验 + 按 X 版本缓存
│   ├── MethodTarget.kt         # JVM 方法描述符解析
│   ├── Settings.kt             # 开关快照（热路径不读 SharedPreferences）
│   ├── XHost.kt                # 宿主 Activity 名单
│   ├── Constants.kt / Logger.kt
├── hooks/
│   ├── NetworkTimelineHook.kt  # OkHttp 拦截器 + RealCall，网络层唯一入口
│   ├── HomeTabHook.kt          # 默认进入正在关注
│   ├── ActivityHooks.kt        # onResume / dispatchTouchEvent 唯一挂点，分发 Logo 长按与视频长按
│   └── MediaDownloadHook.kt    # 播放器追踪、视频长按、复制链接
├── processor/
│   ├── TimelineProcessor.kt    # 去广告、敏感媒体标志
│   ├── MediaExtractor.kt / MediaCache.kt / HlsPlaylistParser.kt
├── utils/                      # 下载、HLS 合成、下载安全校验、播放器追踪
└── ui/                         # 设置面板、悬浮球
```

决策记录在 `active/` 目录下。
