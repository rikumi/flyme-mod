# Google 服务开关磁贴（issue #1）

需求：[issue #1](https://github.com/rikumi/flyme-mod/issues/1)。在控制中心编辑页面添加
“Google 服务”磁贴，开关与“设置 → 通用 → Google 服务 → Google 基础服务”同步。
添加磁贴不会修改当前开关；不保存另一份模块开关。
点击后收起控制中心，并显示“正在启用 Google 服务…”或“正在停用 Google 服务…”提示。

## 图标来源

图标下载自 [Google 官方静态资源](https://www.gstatic.com/images/branding/product/1x/googleg_48dp.png)，
保留原始 48×48 RGBA PNG，置于 `drawable-xhdpi/ic_google_service_tile.png`，对应 24dp。
SHA-256：`07720415844812cd39f10d2cfb6943092539552f8308c9fad970ed75eb08bbe2`。

## 原生调用与权限

- 参考 Settings：`reference-apks/com.android.settings.apk`，versionCode `12001000`，
  SHA-256 `73cfb5953c5520cce0a554902517057569a86b59edd42ff455de4e7a97ba8090`。
- 2026-10-14 从 MEIZU 22（Flyme 12.6.0.0A）只读提取 Settings：versionCode
  `12001000`，versionName `12.1.0`，SHA-256
  `2492895636abdaba444231dfe55ca605aa04efbfee33cb12274d74d0fee504d5`。
- 两个 APK 的 `com.meizu.settings.universal.GoogleBasicServicePreferenceController`
  均以 `Settings.Global.getInt(resolver, "cn_gms_core", 0) == 1` 读取状态，
  `setChecked(boolean)` 以 `Settings.Global.putInt(resolver, "cn_gms_core", enabled ? 1 : 0)`
  写入状态，其余操作只更新页面内 Google 设置和备份条目的可见性。
- 参考 SystemUI：versionCode `16260625`；实际声明
  `com.android.systemui.SystemUIApplication.onCreate(): void`，Manifest 声明
  `android.permission.WRITE_SECURE_SETTINGS`。

磁贴服务通过指定 SystemUI 包的有序广播请求读取可用性或切换。接收器在主进程
`SystemUIApplication.onCreate` 后注册，以模块自定义的 signature 权限限制发送者，
避免第三方应用调用。SystemUI 子进程不注册，以免一次请求产生重复切换。
接收器检查 GMS 是否安装及锁屏状态，并直接写入原生开关，由 Flyme 处理服务生命周期。
磁贴监听同一 Global URI，在系统设置修改开关后更新显示。

收起使用 SystemUI 发出的 `ACTION_CLOSE_SYSTEM_DIALOGS` 广播，仅指定 SystemUI 包。
已核对 `CentralSurfacesImpl.mBroadcastReceiver` 接收后调用
`mShadeController.animateCollapseShade(flags)`，`ShadeControllerImpl` 分别处理通知面板
和控制中心收起；SystemUI Manifest 声明 `BROADCAST_CLOSE_SYSTEM_DIALOGS` 权限。
关闭面板停止监听时保留正在执行的切换请求，避免丢失失败提示。

## 验证边界

`python3 tests/google-service-tile/run.py` 执行实际磁贴和接收器代码，使用 Android
生命周期替身验证添加时不写入、双向状态同步、切换时读取最新状态、重复点击、解锁、
子进程隔离、未安装 GMS、写入失败、模块未加载及监听释放。
权限声明仍需 Android 包管理器执行；此测试不证明实机广播授权或 GMS 冻结效果。

安装后需要让 SystemUI 加载新版模块，再手动在控制中心编辑页面添加磁贴。
尚待实机验证：磁贴开启/关闭与系统设置双向同步、锁屏解锁后切换、GMS 服务实际状态。
