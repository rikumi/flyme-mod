# 分离式面板双向横滑

以 `reference-apks/com.android.systemui.apk` 中的 SystemUI 16260625 为依据。
2026-10-13 已连接设备的 `versionCode=16260625`，`versionName=16.26.06.25_base`。

## 原生路径

- `NotificationShadeWindowView.dispatchTouchEvent(MotionEvent)` 为窗口触摸入口。
- `NotificationPanelViewController.onFinishInflate()` 可取得通知面板、
  `mSharedNotificationContainer` 和 `mCenterController`。共享通知容器是面板的兄弟节点，
  因此需要随通知面板一起移动。
- `CenterController` 的 `mCenterContent` 包含控制中心前景；`mBehindView` 是
  `center_behind` 的 `ScrimView`。只移动背景以外的子节点。
- `ShadeControllerImpl.makeExpandedVisible(boolean)` 在分离模式调用
  `LockscreenWallpaperManager.updatePanelWallPaperVisibility(true)`。
  后者控制 `keyguard_background_frame` 的原生壁纸背景。
- 控制中心使用应用背景模糊及 `center_behind`，通知中心显示锁屏壁纸背景。
  壁纸通过 `LockscreenWallpaperManager.surfaceControl` 镜像，父视图透明度不能
  替代这个 Surface 的透明度。壁纸的模糊背景使用 `BackgroundBlurDrawable`，
  其合成透明度同样单独处理。
  横滑期间将 `ControlCenterInteractor` 的共用窗口模糊归零，以固定半径的
  `BackgroundBlurDrawable` 保持底层完整覆盖，由上层壁纸的透明度完成背景渐变。
  两层同时降低透明度会透出未模糊的应用，因此不再同时淡出两层。到达控制中心后等待
  `NotificationShadeDepthController.onBlurApplied` 提交全强度原生模糊，再清理
  临时区域；返回通知中心则直接清理临时区域。
- `showBackgroundBlurDimLayer(float)` 维护独立 Surface 暗色层。
  横滑期间按控制中心占比调整；取消回到通知中心时立即清理残留层。
- `MzRootContainerBlurBinder.applyBlurState` 在横滑时暂停，避免将应用窗口模糊
  叠加到壁纸及前景。直接发布模糊状态，避免再次触发垂直回弹弹簧和渐显曲线。
- `PagedUnifiedTileLayout.mCurrentPage` 是实际页码，`mViewPager` 是 `ViewPager2`。
  触摸筛选放行内部 `ViewPager2$RecyclerViewImpl`，独立嵌套分页仍保留。
  首页右滑接管后取消原生分页边缘弹簧 `$spring`，其余分页操作保持原生处理。
- 时间与日期由 `clock_container_surface` 的 `ClockSurfaceContainer` 和
  `keyguard_root_view` 承载，位于通知列表之外，分别随通知前景横移。
- 横滑期间暂停 `CenterController.shouldHideBlurForControlCenter` 的背景隐藏规则，
  避免两面板同时展开时把通知背景及增色效果降到 1/255 透明度。
- 通知侧滑时依据 `ExpandableOutlineView.drawChild` 的原生圆角路径边界收窄
  增色区域，并重建圆角形状，模拟侧边裁剪。
- 控制中心状态栏保留在固定的 `center_content` 中，其他前景节点分别横移。
  按进度在通知侧 `mKeyguardStatusBar` 与控制侧的电池图标最终位置间上下移动。
  横滑期间隐藏通知侧的原始状态栏，避免重复显示及横向越过挖孔。
- 横滑期间将壁纸镜像高度及模糊背景位置同步固定在通知侧完整展开尺寸，
  避免从控制中心开始时仍保留一像素高度或偏移到屏幕外。
- `expand(false)` / `expandPanel(false)` 原本延后至布局回调完成。
  横滑期间完成其展开高度并终止垂直渐显，松手后用原生收起路径关闭另一面板。

## 开关与交互

`separate_shade_bidirectional_swipe` 默认关闭，位于“控制中心动画”。
仅作用于已完全展开、未锁屏的分离式面板。
通知中心左划进入控制中心，控制中心首页右划返回通知中心。
正常区间的前景位移与手指等距；两端使用连续阻尼，释放后使用带初始速度的阻尼弹簧。
背景固定于窗口，并按切换进度调整壁纸、原生 Scrim 和适用的 Surface 透明度。

控制中心首页左划、后续页面双向分页、纵向手势、双指手势、滑条、媒体横滑
及通知卡片横滑保留原有处理。
已接管的触摸只向原目标派发一次 CANCEL；动画中可重新按住拖动。
关闭开关会结束当前切换并恢复开始时的面板。
锁屏、模式变化、窗口分离或外部收起发生时释放临时状态，不强制重新展开面板。

## 验证

- `tests/separate-shade-swipe/test_reference_dex.py` 核对 111 项实际方法与字段声明。
- `test_backdrop_runtime.py` 执行 505 项实际背景实现的进度、取消恢复、
  原生重写、Surface 延后就绪及外部收起检查。
- `test_touch_targets.py` 重现内部 RecyclerView 命名，检查分页、滑条及媒体等保留目标。
- `test_notification_side_clip.py` 检查侧边收窄、浮点边界及完全裁剪。
- `test_status_bar_runtime.py` 检查上下位移端点、单套显示、原生重写和退出恢复。
- `SwipeMotionTest.java` 执行 266 项方向、首页分页衔接、边界阻尼、速度判断及弹簧连续性检查。
- 实机仍需检查双向滑动、越界回弹、取消及再次接管，明暗主题、背景压暗开关、
  纵向收起、媒体与通知横滑、关闭功能开关、旋转、锁屏和切换面板模式。
  构建、签名检查和安装不能替代上述视觉及交互验收。
