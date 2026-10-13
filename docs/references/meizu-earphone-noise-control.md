# 魅族耳机噪声控制参考

参考包：`reference-apks/theme-meizu22/com.meizu.earphone/base.apk`，版本 `100.12.23`。

交互参考 coloros-mod 的 `AncTileHooks`：可控蓝牙耳机连接时，音量条图标显示当前噪声模式，点击在降噪和通透之间切换。

Flyme 实现以魅族耳机 App 为依据：

- `RemotePrefService`：显式绑定，action 为 `com.meizu.earphone.ACTION_REMOTE_BIND`。
- `IRemotePrefService`：事务 4 初始化设备，事务 8 读取 App 接纳的设备，事务 5 调用 `setAncSwitch(deviceId, mode)`。
- `AudioDevice.writeToParcel`：返回对象标记后，前两个字符串依次为设备 ID 和型号 ID；不反序列化或执行 App 的混淆模型类。
- `MzBluetoothService` / `IMzBluetooth`：事务 54 将蓝牙地址映射为 SDK 设备 ID，62 检查连接，63 检查协议就绪，56 读取型号，19 请求噪声状态，18 读取状态。事务 7 建立耳机协议连接，不执行配对或音频路由切换。
- 魅族设备在 SDK 服务绑定完成后必须执行事务 67 `connectMzfpService`，并传入 `IMzServiceCallBack` Binder；这是原生 `MzBluetoothManager` 的初始化步骤。仅绑定 SDK 服务不会启动系统耳机服务的识别流程。设备初始化应先于 SDK 连接状态检查。
- `l5.b`：关闭为 1，降噪为 2，通透为 3。点击关闭状态时进入通透，与 coloros-mod 的两态切换规则一致。
- 图标直接读取 App 的 `dc_icon_anc_on_normal`、`dc_icon_anc_transparent_normal`、`dc_icon_anc_off_normal` 原始矢量资源；配色继续由音量条的阈值逻辑决定。

设置默认关闭。只在媒体音量路由为蓝牙且 App 接纳的设备连接、协议就绪时应用；失败时恢复音量图标。控制中心可见时在后台线程查询状态，隐藏后停止查询。

参考版本的 UI 区分 LIVE AI 与 PANDAER 型号，PANDAER 3S Pro 的型号 ID 为 `904852`；运行时不按设备名称猜测，也不对 App 未接纳的设备发出模式切换。

2026-10-14 在 MEIZU 22 确认：开关已开启、A2DP 路由已连接、hook 已注册，
但 SettingsProvider 遗漏 `EARPHONE_NOISE_CONTROL` 列及对应值，SystemUI 的
`getColumnIndex` 返回 -1，始终将功能判定为关闭。因此未触发耳机服务绑定。
现补齐该设置桥，并在设置加载日志记录实际读取值。
Binder 服务可用性、图标触摸范围及实物切换效果仍需加载修正版后验证。

同日后续日志确认开关已读取为 true，两个服务及系统耳机服务均已连接，
但持续出现 `Earphone app has not accepted the device`，原生应用的
`getCacheDevice` 返回 null。`initBtSdk` 在主线程依次调用 `prepare()` 和
`directConnectDevice()`，前者异步绑定 SDK；初次调用不保证生成设备缓存。
修复为未接纳设备时每隔至少 5 秒重试初始化，已接纳后停止重试。
仍保留应用返回的设备 ID、型号、SDK 连接与协议就绪校验。

连续点击排查：设备日志显示后续点击仍发送 `setAncSwitch(..., 2)`，而查询缓存
一直为 3。`sendGetNoiseControlMode` 为异步请求，紧接着读取不能当作新状态。
现用独立状态记录已发送目标，旧回报不覆盖待确认目标，收到对应回报后恢复
设备状态跟随；命令在工作线程串行决定目标。通过同一 SDK 的事务 20
`setNoiseControlMode` 发送并检查布尔返回值，避免应用包装层根据旧缓存跳过
反向切换。连接和应用接纳校验仍由查询路径完成。

图标增加圆形遮罩的前景波纹，模式刷新保留同一波纹对象；功能关闭或媒体
路由不匹配时恢复原前景和点击属性。111 项宿主状态检查覆盖旧回报、交替
点击、延迟回报、外部模式变化、设备切换及重置；实际耳机往返切换待验证。

触摸范围以图标中心扩展为最小 48dp 的正方形，边长至少比图标最大边长增加
16dp。只对已启用的降噪图标在父布局分发入口接管区域内开始的触摸序列，
用 TouchDelegate 保留按压、移动取消与抬起点击语义，区域外沿用原有滑动处理。
不改变图标布局与尺寸；关闭功能或设备失效时取消正在代理的触摸。
