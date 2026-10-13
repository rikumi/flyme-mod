# 锁屏编辑预览字间距

参考与连接设备均为 `com.flyme.systemuieditor` 3.5.45（30005045）。
`EditorClockTextView` 的 `layout_clock` 将小时、分钟拆为独立 TextView，
用水平 LinearLayout 组合；纵向样式隐藏冒号并改变 clock_root_view 的方向。
`PerspectiveDigitClockView` 继承 TextView，测量后通过文本 Layout 绘制。

远程偏好桥同步 `LOCK_CLOCK_SPACING_ENABLED` 与 `LOCK_CLOCK_SPACING`，
单位与实际锁屏一致：整数值乘 0.01em。普通预览在父布局测量前调整每个
水平行中后续可见控件的起始边距，增量为字间距乘字号；纵向布局保留原始
行间距。透视预览在测量前设置 letterSpacing，并拦截原生设置覆盖。
关闭开关恢复原始边距和文本字间距，设置变化请求重新测量。

17 项宿主检查覆盖横排、竖排、冒号隐藏、重复测量、字号变化、关闭恢复、
透视文本、非时钟文本保护及实时刷新。实机预览效果待编辑器加载新版后验证。
