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

当前没有连接设备；Binder 服务可用性、图标触摸范围及实物切换效果需实机验证。
