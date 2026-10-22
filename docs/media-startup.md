# 禁用主动显示未播放媒体

入口为控制中心的“播放控制”分组，默认关闭。开启后：

- 阻止 `MediaResumeListener` 的启动恢复、通知后的恢复能力检测，以及 `ResumeMediaBrowser.connectBrowser`，不主动向应用媒体库请求可播放内容。
- 阻止新旧媒体流水线的 `addResumptionControls`，拒绝开关变化前已经排队的浏览结果。
- 保留通知处理、MediaController 和原生播放状态监听。在最终展示过滤器中，仅放行本次 SystemUI 进程观察到真实 `STATE_PLAYING` 的内容；缓冲、连接、暂停及“可恢复”标记不算播放证据。播放过的内容暂停后仍可保留。
- 播放资格按用户、包名、会话令牌、标题、作者、可用的媒体 ID 和时长区分，不因同一应用曾播放其他媒体而放行未播放内容。记录只保存在内存中，最多 256 条；重启后不会凭恢复列表认定媒体播放过。
- 开关变化时，在主线程断开已知浏览连接并重新过滤现有卡片。关闭后恢复原生查询和展示路径，不修改应用或系统媒体记录；不主动重新发起一次浏览。

开启期间，通过 MediaBrowser 查询媒体库的恢复按钮也不再连接应用；仍存在的实际媒体会话继续使用原生播放、暂停控制。

## 源码依据

以本地 `reference-apks/com.android.systemui.apk`（SystemUI 16260625）对应反编译代码为依据，重新从该 APK 核对 `ResumeMediaBrowser`；包含：

- `MediaResumeListener.loadMediaResumptionControls()`、`tryUpdateResumptionList(String, ComponentName)`、`onMediaDataLoaded(String, String, MediaData, boolean, int, boolean)`。
- `ResumeMediaBrowser.connectBrowser(MediaBrowser, String)`、`disconnect()`。
- `LegacyMediaDataManagerImpl` 与 `MediaDataProcessor` 的恢复入口、媒体分发入口及 `context` 字段。
- `LegacyMediaDataFilterImpl` 与 `MediaDataFilterImpl` 的展示加载、移除入口。过滤放在这里，避免切断上游超时和会话状态监听，导致首次播放后无法显示。

## Flyme 专用播放控制

`QsMediaController` 除了监听 Android 媒体流水线，还会直接读取活动媒体会话，`initMedia` 会立即读取元数据，随后经 `getQsMediaData` 和 `QsMediaView.refreshData` 显示。因此只过滤 Android 的 `MediaDataFilter` 不能覆盖 Flyme 控制中心。

新增针对这条直接会话路径的过滤：没有本次进程中的真实播放证据时，调用原生 `QsMediaView.setMediaDataRemoved` 恢复默认标题、封面和按钮状态；不调用 `resetCurrentController`，保留元数据与播放状态监听。真正播放后仍可正常显示。元数据刷新和最终视图入口均检查，防止排队刷新重新显示旧内容。开关改变时同步重新检查已注册的控制中心视图。

该显示路径本身只能证明“读取了已有会话元数据”，不能据此断言应用当时收到媒体库浏览请求。

## 验证

主机测试覆盖未播放隐藏、真实播放、暂停保留、更换曲目、用户/应用/会话隔离、开关关闭、开关重新开启、进程重启及记录容量边界。JDK 21 构建和签名检查不能代替 Telegram 与控制中心的实机验证。
