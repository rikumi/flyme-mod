# 欢律免注入降噪控制

控制中心复用“音量条显示切换降噪模式图标”开关。OPPO／一加耳机由已安装的欢律负责连接及协议通信；模块只注入原有的 SystemUI 和系统框架作用域，不加载欢律代码，不修改欢律组件的 exported 属性，不放宽系统组件权限检查。

## 使用条件与边界

- 当前私有接口按 `com.heytap.headset` **17.6.5** 适配，并核对 APK 签名。其它版本暂不调用，需检查接口后再扩展。
- 需先自行打开欢律，完成首次设置及蓝牙授权，并确认欢律内可控制耳机。
- 需要模块的系统框架和 SystemUI 作用域生效；更新后须重新加载相关进程（通常由用户重启设备）。无需勾选欢律。
- 当前仅支持主用户。耳机必须出现在蓝牙音频输出列表中，欢律返回连接状态 2、有效模式索引，且型号配置同时提供降噪和通透。
- 不按耳机名称猜测型号。型号、协议索引及按耳机能力决定的模式支持位均取自欢律运行时 Provider。
- 控制中心不可见后停止轮询，系统侧在最后一次请求约 6 秒后解除服务绑定。关闭功能时清除状态并解除绑定。
- 图标只显示回读状态，不将排队或发送命令当成耳机已切换成功。查询异常、断连或缓存超过 5 秒时恢复普通音量图标。
- 本次不新增小米支持。魅蓝独立 lifeme 协议尚未适配；原魅族耳机应用能识别的型号继续走原实现。

## 已检查的调用链

Flyme 参考文件：`reference-apks/small-window-meizu22-12.6/services.jar`。`ActivityManagerService.onTransact(int, Parcel, Parcel, int)` 及 `mContext` 均已确认。

模块为该 Binder 增加专用事务 `0x00464d4e`，校验实际调用 UID 必须等于主用户的 `com.android.systemui`，校验接口令牌、蓝牙地址和操作（读取／降噪／通透）。其它事务直接执行原实现。请求只在开关开启时处理，不接受任意组件、URI 或 Messenger 命令。系统身份在处理结束后恢复。

系统侧专用工作线程使用以下欢律内部 IPC：

1. 绑定 `com.oplus.melody.alive.service.MelodyAliveService`，`onBind` 返回 Messenger。
2. 查询 `content://com.heytap.headset.alive.RepositoryProvider/3060?macAddress=...` 的 `value` 列，解析 `EarphoneDTO` JSON 的 `connectionState`、`macAddress`、`noiseReductionModeIndex`、`supportNoiseReductionInfo`。
3. 查询 `content://com.heytap.headset.alive.WhitelistProvider/find_whitelist?macAddress=...` 的 `content` 列，解析 `function.noiseReductionMode`。子模式继承父模式类别，`decideByEarDevice` 为真时核对设备支持位。
4. 发送 Messenger `what=3058`，Bundle 为 `macAddress` 和 `value`（协议索引）。欢律 `EarphoneRepository` 转到 `setCurrentNoiseReduction(index, 1, address)`，再由其 BluetoothService 命令 1014 构造实际耳机命令。

统一显示模式仍为 1 关闭、2 降噪、3 通透。欢律模式类型 1 为关闭、2 为通透，3／4／5／10 为降噪；选择降噪的优先级沿用 ColorOS Mod 的 5、4、3、10。缺少有效配置或配置冲突时不启用控制。

Binder 请求仅返回缓存或排队结果，不在系统 Binder 调用线程、SystemUI 主线程上执行第三方 Provider 查询。最多保留一个正在执行或排队的任务，避免第三方应用无响应时不断积累请求。缓存过期后不继续显示旧状态。

## 参考包与验证

欢律参考包来源：<https://www.oppojia.com/app/64051871.html>。
APK v2 签名证书 SHA-256：`159e6f37c336f2f41272542dbc1dc85721dd567ed4ac68ac13f2e43003ce0dce`。
不分发参考 APK 或完整型号配置。

`tests/heymelody-noise/HeyMelodyNoiseModesTest.java` 覆盖代际索引差异、子模式、模式优先级、设备能力过滤、异常索引、配置冲突和缺失能力。保留原有魅族异步模式状态测试。

构建和静态源码验证不代表设备验收；仍须连接 Flyme 设备，验证系统侧私有组件访问、欢律冷启动／断连恢复、控制中心图标和实际耳机切换。
