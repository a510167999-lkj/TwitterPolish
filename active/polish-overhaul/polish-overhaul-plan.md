# polish-overhaul 实现方案

## 目标

按 2026-10-10 代码审查结论全面重构 TwitterPolish，宿主基线 X 12.33.0（versionCode 312330001），真机 f3d1467。

## 已定的产品决策（用户 2026-10-10 确认）

1. 删除客户端「按时间倒序」重排；改为真正实现「默认进入正在关注」。
2. 入口只保留：悬浮球 + 顶部 Logo 长按。删除 Logo 双击、双击音量减、设置页注入按钮。

## 真机基线证据（重构前，X 12.33 / 模块 1.0.7）

- DexKit `settings_root_class` 命中 `com.stripe.android.link.ui.wallet...` —— 设置页按钮挂错对象。
- DexKit `timeline_item_is_promoted`、`exoplayer_impl` 均 0 候选 —— 模型层去广告从未生效。
- OkHttp 类名未混淆，`RealCall` hook 安装成功。

## 步骤

1. 时间线处理：删重排/sortIndex 改写/游标改写；广告判定改为看值（非 null/true）；保留敏感媒体开关。测试覆盖 TweetDetail/Bookmarks 顺序不变、`promotedMetadata:null` 不误删。
2. 默认关注：反编译 12.33 定位 `preferred_home_tab` / `last_home_visit_epoch_millis` 判定逻辑，DexKit 用字符串锚点定位，失败 fail loud（日志 + 设置面板状态）。
3. 入口合并：单个 `Activity.dispatchTouchEvent` hook 分发 Logo 长按与视频长按；单个 `onResume` 追踪当前 Activity；删除 `SettingsHook`、`TabSwitcherHook`、`TimelineFilterHook`、音量键。视频长按只在已登记的播放器画面里命中，不再每次 DOWN 遍历整棵 View 树。
4. 网络层：去掉 `MediaDownloadHook` 里重复的 RealCall hook，并入 `NetworkTimelineHook` 一处。
5. 下载：复制链接只在缓存里有该推文媒体时提示；图片 MIME 按扩展名；minSdk 26→32（X 自身 minSdk=32），删除 API<29 存储分支；删除未使用的 Authorization/csrf 捕获。
6. 清理死代码与 README（以代码实际行为为准，去掉夸大表述）。
7. 构建 + 单测 + 真机验证。

## 关键决策

- minSdk 改为 32：宿主 X 12.33 minSdk=32，模块不可能跑在更低系统上；同时修掉 HLS 在 API<29 必败的问题。这是有意修改，不是顺手改。
- `META-INF/xposed/*` 不动：当前加载正常，删改收益小、有让 LSPosed 识别方式变化的风险。
- 不碰下载核心链路（HLS 解析/合成/安全校验），它们有单测且上一轮已真机修过。

## 验收条件

- `./gradlew testDebugUnitTest assembleDebug` 通过。
- 真机（需用户解锁）：模块加载无崩溃；冷启动 X 默认在「正在关注」；LSPosed 日志里 DexKit 默认关注目标命中；时间线有广告过滤日志且推文详情顺序正常；Logo 长按弹设置；音量键不被拦截；悬浮球可用；视频长按下载仍能出菜单。
