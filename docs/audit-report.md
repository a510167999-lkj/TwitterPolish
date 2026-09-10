# TwitterPolish 代码审计与修复报告

- **审计日期**：2026-09-09
- **修复验证**：2026-09-10（两轮，含真机回归）
- **审计范围**：`app/src/main` 全部 Kotlin 源文件、AndroidManifest、Gradle 构建脚本、Xposed 配置、单元测试
- **报告位置**：`docs/audit-report.md`（原在 `build/reports/`，因 build 目录被构建系统清理而迁移至此）

---

## 第一轮修复（2026-09-10 凌晨，对应 P1/P2/P3 全部审计发现）

全部 P1（7 项）/ P2（8 项）/ P3（6 项）已修复，干净重编译 0 警告，单测 28/28 通过。

### P1 修复明细

| 编号 | 问题 | 修复 |
|---|---|---|
| P1-1 | 凭证泄露：下载无域白名单 | 新增 `utils/DownloadSecurity.kt`；`HostOkHttp.open` 白名单外拒绝；`Downloader.openSource` 白名单外匿名连接；`HlsDownloader.open` 黑名单拒绝 |
| P1-2 | 共享 SimpleDateFormat 非线程安全 | `TimelineProcessor` 改 `ThreadLocal` |
| P1-3 | 循环中 remove(i) 跳元素，连续广告漏删 | `cleanJsonObject` instructions 改倒序遍历 |
| P1-4 | `MediaCache.byMediaId` 无容量上限 | 改 LRU LinkedHashMap，上限 250 |
| P1-5 | URL 匹配过宽（/2/、/1.1/ 命中全部 API） | 启用 `Constants.TIMELINE_ENDPOINTS` 端点匹配；非 JSON 直接放行；未修改 `return null` 不重建 body |
| P1-6 | 改写响应体后 Content-Length 头失效 | 重建 body 时移除 `Content-Length`，按分支恢复/清除 `Content-Encoding` |
| P1-7 | gradle-wrapper.jar 缺失 | 补齐 wrapper，pin 到 Gradle 8.9（本机已有发行版，离线可用） |

### P2 修复明细

1. 硬编码混淆类名 → 新增 `core/XHost.kt` 集中维护；`SettingsHook` 回调改接口检测 + 动态 Proxy（兼容 kotlin.jvm/kotlin Function0）
2. 拦截器去重 `Proxy.isProxyClass` 过宽 → 仅与本模块实例 `===` 比对
3. 未按进程过滤 + `hooks` 字段死代码 → `MainHook` 仅接管主进程；移除字段
4. 广告判定 14 次 contains → 合并单趟正则，结构化模块检查前置
5. HLS 音画未交错写入 → `HlsDownloader` 双轨按 PTS 交错写 MediaMuxer
6. `TimelineFilterHook` 不尊重去广告开关 → volatile 快照 + prefs 监听
7. 悬浮球/悬浮按钮多 Activity 失效 → `FloatingMenuManager` 改 WeakHashMap<Activity,View>；`SettingsHook` 按宿主 decorView 判断重建
8. 每请求/每触摸读 prefs → 三个 Hook 均改 volatile 快照 + 监听

### P3 修复明细

- SnowflakeUtils 复用进主流程；删除无引用 companion；SettingsDialog 抽取 `saveAll`；常量统一；9 处编译警告清零
- 有意保留：Logger 双写（LSPosed 日志即用户上报渠道）、proguard 不开启 minify（需大量 keep，风险大于收益）

---

## 🔁 第二轮：真机回归修复（2026-09-10）

**现象**：长按任何视频都保存同一个视频（相册出现两个相同 mediaId、相同大小的文件）。

这不是小 bug，而是媒体锁定机制的三处方法缺陷叠加：

| 问题 | 根因 | 修复 |
|---|---|---|
| 锁死旧视频 | `playerLockedMediaId` 无限期 sticky，一旦设置所有新视频 URL 被 `"Ignoring prefetch/stale"` 拒绝，`currentMediaId` 永不更新 | 锁加 **10 秒 TTL**（`MediaCache.PLAYER_LOCK_TTL_NS`），过期自动失效，新增 `freshPlayerLockMediaId()` |
| 长按抓到预取下一条 | `DownloadResolver`：view→player 解析失败时回落全局播放器选择（`selectPlayer`） | 长按路径（有 videoView）**绝不回落**全局选择；悬浮球/设置路径（无 videoView）才允许 |
| view→player 频繁查不到 | `bindOutput` 只登记自身 key，X 自研播放器视图树里 Surface 与 PlayerView 是兄弟节点 | 绑定**子树扩散**：登记整个子树的 video-like 视图（不覆盖已有映射） |
| 预取覆盖锁 | `noteFromPlayer` 无可见性检查，预取 player 的渲染/播放事件覆盖锁 | 加 `isOnScreen` 过滤：view 不在屏内的事件不更新锁 |
| 混合帖子只能单选 | UI 只逐项列出，无批量入口 | 长按选择器 + 悬浮球菜单加“📥 全部下载（N 项）” |

**验证**：单测 29/29（新增锁时效回归用例）；版本 bump 到 **1.0.6 / 10006**；已安装真机（lastUpdateTime 00:39:48）。

---

## 部署记录

| 时间 | 版本 | 操作 | 设备 |
|---|---|---|---|
| 2026-09-10 00:23 | 1.0.5 / 10005 | `adb install -r`（第一轮修复版） | f3d1467 |
| 2026-09-10 00:38 | 1.0.5 / 10005 | `adb install -r`（第二轮修复版） | f3d1467 |
| 2026-09-10 00:39 | **1.0.6 / 10006** | `adb install -r`（bump 版本便于 LSPosed 列表确认） | f3d1467 |

**部署纪律**：仅 `adb install -r` + `am force-stop com.twitter.android`。不重启手机，不热改 LSPosed 活库。

---

## 第三轮：下拉刷新卡住（2026-09-10）

**现象**：X 时间线无法刷新，一直转圈。

**根因（第一轮 P1-5/P1-6 引入的回归）**：

`ResponseBody.bytes()` **关闭底层流且不缓存**。第一轮审计把「JSON 未改写则 `return null`」当成优化，并写了错误注释「bytes() 内部缓存、可重读」。客户端仍按原 `Content-Length` 等数据，刷新永远结束不了。

两条路径叠在一起：

1. **拦截器 + RealCall.afterHook 双重处理**。第一次改写并重建 body；第二次再 `bytes()`，`JSONObject.toString()` 与输入相同 → 再 `return null` → HomeTimeline 自己也被掏空。
2. **`contains("graphql")` 仍在**。Viewer / CreateTweet / BadgeCount 等全部 GraphQL 都被 `bytes()`，未改写就交回空 body。下拉刷新会并发打这些接口，任意一条挂起都会让转圈停不下来。

### 本轮修复

| 编号 | 问题 | 修复 |
|---|---|---|
| R1 | `bytes()` 后 `return null` | 读过 body 之后**必须**重建 ResponseBody；失败打日志，禁止交回已消耗的原响应 |
| R2 | 拦截器与 RealCall 处理两次 | WeakHashMap 标记已重建实例，第二次跳过 |
| R3 | URL 匹配过宽 | 只认 GraphQL 路径最后一段 ∈ `TIMELINE_ENDPOINTS`；去掉 blanket `graphql` / `/timeline` |
| R4 | 拦截器 Proxy `invoke(proxy)` | 正确实现 equals/hashCode/toString，避免递归栈溢出 |
| R5 | `Application.attach` 无 once-guard | `AtomicBoolean`，避免注入两个拦截器 |
| R6 | 无广告仍 `JSONObject.toString()` | 无广告且不排序时交回原 entries 数组 |
| R7 | SharedPreferences listener 被 GC | 三个 Hook 持有 listener 强引用 |
| R8 | DexKit 空扫描仍写入缓存 | 空结果不持久化，下次冷启动重扫 |
| R9 | `isPromoted` hookAllMethods | 只改无参方法返回值，避免同名带参方法把时间线条目整批弄没 |

版本 **1.0.7 / 10007**。单测 **32/32**。已 `adb install -r` 到 `f3d1467` 并 `am force-stop com.twitter.android`。

### 全量源码审计（本轮范围：`app/src/main` 全部 Kotlin）

**已处理的会卡死/会丢响应问题**：见上表 R1–R9。

**有意保留 / 未在本轮改动的风险**（不修以免扩大范围）：

| 级别 | 位置 | 说明 |
|---|---|---|
| 行为 | `TimelineProcessor` 时间序重写 `sortIndex` | 客户端 SQLite `ORDER BY sort_index DESC`。若刷新后「转圈停了但新推不出现」，先关掉「纯时间顺序」验证。 |
| 兼容 | `XHost.SETTINGS_ROOT_CLASS` = `com.x.settings.a`，方法名 `a`/`b` | 短命混淆名。X 大版本升级后设置入口会静默失效，应改 DexKit 特征扫描。 |
| 兼容 | `SettingsHook` 替换该方法上所有 `Function0` | 可能误伤同方法里其它 lambda。目前只影响设置页。 |
| 兼容 | DexKit `isPromoted` / ActionSheet / ExoPlayerImpl `firstOrNull` | 扫描命中多个方法时取第一个。R9 已把错误挂钩的破坏面收窄到无参方法。 |
| 下载 | `ExoCacheProbe` 只读打开 `exoplayer_internal.db` | 播放器同时写库可能 SQLITE_BUSY，捕获后返回空，长按会提示「还没拿到播放列表」。 |
| 下载 | `Downloader` 未声明 `POST_NOTIFICATIONS` | Android 13+ 进度通知可能被丢，下载本身仍走 MediaStore。 |
| 泄漏 | `MediaDownloadHook.currentActivity` 强引用 | 进程内常驻，泄漏的是当前 Activity。 |
| 安全 | `DownloadSecurity` 未覆盖 IPv6 `::1` | IPv4 内网/回环已拦。媒体 URL 实际都是 twimg。 |
| 日志 | `Logger` 双写 Logcat + XposedBridge | 有意保留，LSPosed 日志即用户上报渠道。 |

**未再发现**第二处「消耗 OkHttp body 后交回原响应」的路径。全库只有 `NetworkTimelineHook` 调用 `bytes()`。
