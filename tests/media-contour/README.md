# 播放控制轮廓光动态可见区域

目标源码 SystemUI 16260625 / 16.26.06.25_base。21 Pro 实际只读层级：SharedNotificationContainer → NotificationStackScrollLayout → MediaContainerView → UniqueObjectHostView → MediaScrollView → LinearLayout → MediaCarouseTransitionLayout。媒体卡片与滚动容器为 1230×608；采集时 MediaContainerView 为 INVISIBLE，不能据此称为实际播放控制视觉验收。

MediaCarouselScrollHandler 的 outlineProvider 是 carouselWidth/Height/cornerRadius 圆角矩形，滚动/内容平移时启用 clipToOutline。TransitionLayout.applyCurrentState/updateBounds 直接改变边界，并在 dispatchDraw 裁剪 boundsRect。MediaContainerView.onDraw 以当前 Canvas clipBounds 和 clipHeight 构造 clipPath，直接修改父 Canvas；它不是 ExpandableOutlineView，不能只沿用通知 row 判定。通知 row 的 drawChild 使用 getCustomClipPath/getClipPath，堆栈则使用 mRoundedClipPath（含 Y translation）。

MediaContourGeometry 将上述路径、RenderNode clipBounds/outline、实际 clipChildren/非零 padding 和矩阵/scroll 映射到卡片坐标后求交；可见 bounds 用于 shader 的尺寸和渐变，实际路径用于 nativeCurve 向内距离 mask。不关闭任何原生裁剪、不扩大正文或点击区，不修改通知侧边距及非媒体卡片。路径只复制不改原生对象，复用计算对象与既有轮廓 mask 缓存；没有新增计时器或全局每帧检查。由实际媒体状态、轮播位移/滚动/圆角/尺寸、媒体容器高度和变换、堆栈裁剪更新事件重绘。

媒体轮廓使用通知轮廓光开关，关闭移除媒体 overlay，并在直接绘制时再次检查。其它磁贴仍使用原有控制中心轮廓光开关。79 项生产几何检查涵盖圆角完整求交、重定位、缩放/奇异矩阵、动态宽高、滚动、padding、Canvas clip、公开 row 路径、堆栈和媒体 clipHeight、原生路径/布局保留。GPU 光晕、动画、锁屏及 heads-up 显示仍需实机验收，未改设置或主动触发通知。

侧滑移除裁切由 MediaSwipeCornerHooks 对真实 MediaCarouselScrollHandler 的 updateClipToOutline/setContentTranslation/onMediaScrollingChanged/setCarouselBounds/onSettingsButtonUpdated 回调更新：仅在通知轮廓光和自定义通知圆角均开启时用同一个 NotificationAppearanceHooks.radius(view) 计算值替换圆角。委托原生 outlineProvider 提供 carousel 的实际矩形边界，不修改 cornerRadius 资源字段、clipToOutline 激活逻辑、侧滑位移、正文宽度和点击区。关闭任一开关或 detach 恢复原生 provider；重新 attach、更新尺寸、原生替换 provider、设置刷新均可重建。

`test_swipe_corners.py` 的 68 项生产 hook 检查覆盖原生边界委托、浮点圆角同步、双向侧滑及轮播、尺寸更新、原生 provider 替换、两个实例隔离、无效参数、detach/attach、开关即时恢复和重复刷新不额外重绘。宿主检查不替代实机侧滑视觉验收。
