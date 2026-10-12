# 状态栏歌词避让验证

默认开启；只隐藏 PhoneStatusBarViewController.clock，使用 INVISIBLE 保留图标布局，并保存原生时钟的可见性请求，关闭及移出后恢复原生请求状态。

已对照 Flyme Android 16 framework 的 WindowState.setFrames(ClientWindowFrames,int,int)、setViewVisibility(int)、onSurfaceShownChanged(boolean)、show/hide(boolean,boolean)、onDisplayChanged(DisplayContent)、removeImmediately() 及 WindowManagerService.onInitReady()。Surface shown 字段在回调前更新，帧取 getFrame() 布局后屏幕坐标，不能使用 LayoutParams.y。初始与重新启用时在 mGlobalLock 下读取 mWindowMap；后续仅由窗口生命周期事件更新单个窗口。

参考 ../coloros-mod 的窗口事件桥设计，未沿用 OplusWindowManager 接口或延时扫描。主线程单次 post 合并当前事件，Settings.Global 快照只有变化时发布；没有定时器、循环轮询、每帧监听或全量周期扫描。SystemUI 通过 ContentObserver 接收快照，状态栏布局事件处理旋转与高度变化。

实际 UID 包列表验证第三方身份，排除系统 UID、系统/更新系统应用、SystemUI 和模块自身；只跟踪悬浮窗口类型，不跟踪普通 Activity、Toast、输入法和系统状态栏。多个 WindowState 独立记录，移除一个窗口不会恢复仍受其它窗口覆盖的时钟；解绑及 Binder 死亡最终通过 removeImmediately 清除。透明、隐藏、未显示 Surface 和其它显示器不遮挡。

`python3 tests/statusbar-overlay/test_lifecycle.py` 编译并运行实际三个生产类（不是另一份算法实现），覆盖多窗口同帧、布局更新、显隐、Surface、移除、开闭、原生 GONE 请求、detach/attach、重复监听恢复、系统/模块排除、其它窗口类型、旋转高度、显示器和严格覆盖边界。

未修改设备开关、未主动创建浮窗或重启作用域，因此歌词避让的实际字体与覆盖效果、Binder 死亡恢复尚未实机验收。宿主检查不替代设备验证。
