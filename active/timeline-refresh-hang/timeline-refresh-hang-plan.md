# 修复：下拉刷新卡住

## 前提

`NetworkTimelineHook.processResponseDirectly` 调用 OkHttp `ResponseBody.bytes()` 后，底层流已关闭且**不会缓存**。第一轮审计把「JSON 未改写则 `return null`」当成优化，错误注释称 bytes() 可重读。客户端仍按原 `Content-Length` 等数据，刷新转圈永不结束。

叠加：拦截器与 `RealCall.afterHook` 对同一响应处理两次；第二次 `JSONObject.toString()` 与输入相同 → 再 `return null`。`contains("graphql")` 仍命中全部 GraphQL，非时间线接口同样被掏空。

## 决策

1. `bytes()` 之后必须重建 body；失败才打日志，禁止交回已消耗的原响应。
2. 重建后的响应用 WeakHashMap 打标，RealCall 跳过已处理实例。
3. URL 只匹配 `Constants.TIMELINE_ENDPOINTS`，去掉 blanket `graphql`。
4. 拦截器 Proxy 正确实现 equals/hashCode/toString，避免递归。
5. `Application.attach` 只初始化一次。

## 验收

- 单测：非时间线 GraphQL 不匹配；无广告且关闭排序时返回同一字符串实例。
- `./gradlew testDebugUnitTest assembleDebug` 通过。
- 装到 `f3d1467` 后用户强停 X，下拉刷新应结束转圈并出新推。
