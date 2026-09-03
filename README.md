# TwitterPolish - X (Twitter) LSPosed 增强模块

<p align="center">
  <b>适配 X 各种版本 · 屏蔽瀑布流所有广告 · 瀑布流纯时间序排列 · 视频与原图一键下载</b>
</p>

---

## 🌟 核心特性与实现原理

### 1. 跨版本自适应 (Cross-Version Resilient)
- **OkHttp 协议层拦截**：X 客户端无论界面如何混淆改名，底层网络通信始终采用标准 OkHttp。模块通过动态代理拦截 OkHttp 请求与响应链，在 GraphQL 响应进入 UI 渲染前直接净化 JSON，不受客户端混淆改版影响。
- **DexKit 动态字节码检索与智能缓存**：
  - 集成 `LuckyPray/DexKit`，在目标 APK 运行时通过特征字符串（如 `"promotedContent"`、`"video_info"`、`"HomeTimeline"`）动态定位目标类与方法；
  - 配备本地签名缓存系统（根据 X 客户端 `versionCode` 持久化解析结果），冷启动 0 延迟，仅在 X 升级版本后自动触发重扫。

### 2. 瀑布流广告全量屏蔽 (Timeline Ad-Blocking)
- **推广推文 (Promoted Tweets)**：深度递归过滤带有 `promotedMetadata`、`promoted_metadata` 或 `promoted-tweet` 前缀的商业推广。
- **关注推荐 (Who to follow)**：移除嵌入瀑布流中的推荐关注、猜你喜欢横条卡片。
- **话题与趋势推荐 (Topics & Trends)**：移除 `topic-`、`connect-` 等非关注者的算法推荐卡片。
- **模型层双重拦截**：通过 DexKit Hook 推文模型判断方法，强制 `isPromoted() -> false`，避免瀑布流出现空白占位或滑动掉帧。

### 3. 瀑布流纯时间顺序排列 (Chronological Waterfall)
- **Twitter Snowflake 时间戳算法**：
  - 推文 ID 为 64 位 Snowflake ID，高 41 位表示时间戳（基于 Twitter Epoch `1288834974657L`）：
    $$\text{Timestamp} = (\text{TweetId} \gg 22) + 1288834974657\text{ ms}$$
  - Snowflake ID 具有绝对单调递增性（ID 越大 = 时间越新）。
- **URT sortIndex 与 SQLite 数据库深度同步**：
  - 剔除穿插在信息流中的陈旧推文，按 Snowflake ID 降序严格重排；
  - 独家解决 X 客户端本地通过 `SELECT * FROM timeline_entry ORDER BY sort_index DESC` 读取时的算法回弹问题：重构每个条目的 `sortIndex` 降序键值并写回 JSON，确保客户端无论以何种方式渲染均为严格纯时间倒序；
  - 完整保留并动态修正 `TimelineTimelineCursor`（顶部/底部加载游标），确保无限滑动加载流畅不断流。

### 4. 视频与图片最高清下载 (Media Downloading)
- **无损原图**：将图片 CDN 链接（`pbs.twimg.com`）后缀一律转为 `name=orig`，直连官方无损母图。
- **最高画质视频**：嗅探并 Hook 官方播放器 ExoPlayer，自动锁定最高码率 1080p MP4 直链。
- **前台通知栏动态进度**：自建多线程下载引擎与专用通知渠道，实时输出百分比与 MB 传输量，下载后自动刷新系统相册。
- **复制链接与悬浮球一键触发**：分享推文“复制链接”时自动提示下载，或通过屏幕悬浮球一键捕获当前播放的视频。

### 5. 客户端内嵌控制台与悬浮齿轮 (In-App Settings & Quick Ball)
- **屏幕边缘悬浮球（⚙️）**：应用内启动后吸附于屏幕边缘，可自由上下拖拽吸边，点击瞬间唤出快捷菜单与插件设置。
- **双击音量减键快捷唤出**：物理按键极速呼出设置弹窗。
- **官方设置集成**：在 X 原生“设置和支持 -> 设置与隐私”底部自动注入 TwitterPolish 选项。

---

## 📁 项目目录结构

```text
TwitterPolish/
├── app/
│   ├── build.gradle.kts                         # 模块构建脚本
│   ├── proguard-rules.pro                       # 混淆保持规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml              # Xposed 模块声明与权限
│       │   ├── assets/
│       │   │   └── xposed_init                  # Xposed 入口指针
│       │   ├── resources/META-INF/xposed/
│       │   │   ├── module.prop                  # LSPosed 模块属性
│       │   │   └── scope.list                   # 作用域声明 (com.twitter.android)
│       │   └── java/com/polish/twitter/
│       │       ├── MainHook.kt                  # Xposed 主入口 (IXposedHookLoadPackage)
│       │       ├── core/
│       │       │   ├── Constants.kt             # 全局常量与端点
│       │       │   ├── Logger.kt                # 格式化日志
│       │       │   └── DexKitManager.kt         # DexKit 动态扫描与签名缓存
│       │       ├── hooks/
│       │       │   ├── BaseHook.kt              # Hook 抽象基类
│       │       │   ├── NetworkTimelineHook.kt   # OkHttp / GraphQL 响应拦截
│       │       │   ├── TimelineFilterHook.kt    # 数据模型层去广告 Hook
│       │       │   ├── MediaDownloadHook.kt     # 媒体下载长按与菜单注入
│       │       │   └── TabSwitcherHook.kt       # 默认标签页管理
│       │       ├── processor/
│       │       │   ├── TimelineProcessor.kt     # 核心算法：广告清洗 + Snowflake 倒序重排
│       │       │   └── MediaExtractor.kt        # 核心算法：1080p 视频与原图直链提取
│       │       └── utils/
│       │           ├── Downloader.kt            # DownloadManager 后台调度与相册刷新
│       │           └── SnowflakeUtils.kt        # Snowflake 时间戳解析转换
│       └── test/java/com/polish/twitter/
│           └── TimelineProcessorTest.kt         # 单元测试 (去广告、排序、媒体解析)
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

---

## 🔨 构建与安装

### 1. 命令行构建
```bash
# 赋予 gradlew 执行权限（若需要）
chmod +x gradlew

# 编译 Debug APK
./gradlew assembleDebug

# 或编译 Release APK
./gradlew assembleRelease
```
生成的 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。

### 2. 在 Android Studio / IDE 中使用
- 直接通过 Android Studio 打开 `TwitterPolish` 目录。
- 等待 Gradle 同步完成，点击 `Build > Build Bundle(s) / APK(s) > Build APK(s)` 即可完成构建。

---

## 📱 使用指南

### 1. Root 用户 (LSPosed)
1. 在手机上安装编译生成的 `TwitterPolish` APK。
2. 打开 **LSPosed 管理器**，在模块列表中找到 **TwitterPolish**。
3. 勾选启用该模块，并确认推荐作用域已勾选 **X (`com.twitter.android`)**。
4. 强行停止 X (Twitter) 客户端并重新打开。

### 2. 免 Root 用户 (LSPatch)
1. 安装 **LSPatch** 工具应用。
2. 提取或准备好官方 X 客户端 APK。
3. 在 LSPatch 中新建修补，选择 X 客户端，并在便携模式（Portable）或本地模式中嵌入 `TwitterPolish` 模块。
4. 卸载原版 X，安装修补后的 APK 即可免 Root 享受所有功能。

### 3. 操作说明
- **广告拦截**：打开 X 客户端进入主页流，所有推广推文、推荐关注模块已自动被彻底过滤。
- **纯时间倒序**：主页瀑布流推文已自动按照真实发布时间倒序排列，最新推文始终在最前。
- **视频与图片下载**：在推文中**长按**任意图片或视频，屏幕将弹出下载确认对话框，点击“立即下载”即由系统后台调度下载并自动存入相册。
