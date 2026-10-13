# 自动亮度偏移

依据 `reference-apks/small-window-meizu22-12.6/services.jar`，MEIZU 22 / Flyme 12.6.0.0A。

- 旧路径：`DisplayPowerController.updatePowerState()` 调用
  `AutomaticBrightnessStrategy2.getAutomaticScreenBrightness(BrightnessEvent)`。
  此方法先读取自动亮度，再应用 Flyme 临时自动亮度调整。
- 重构路径：`AutomaticBrightnessStrategy.updateBrightness(StrategyExecutionRequest)` 调用
  `getAutomaticScreenBrightness(BrightnessEvent, boolean)`；有效性检查也调用该方法。
- 两条路径分别持有 `mAutomaticBrightnessController`；通过其 `mDisplayState` 和
  `mDisplayPolicy` 排除息屏、关闭及挂起状态。
- 在策略返回值上加 `偏移 / 100`，不写入自动亮度控制器缓存，不累积偏移；
  限制到 0.0–1.0，后续仍执行系统的亮度范围、温控和省电处理。
  NaN、无穷、负数哨兵及超出正常范围的原生值保持不变。
- 通过现有远程偏好桥同步，关闭开关恢复原值，设置在下一次自动亮度应用时生效。
  不使用 SettingsProvider 查询路径，不主动触发设备亮度或模式修改。
- 设置位于控制中心 / 亮度调节设置，−30%～50%，默认 10%，步长 5%。
  一键启用包含此功能；未应用预设时开关默认关闭。

此 hook 位于系统框架作用域；升级后首次加载新代码需要系统框架重新加载模块。
不由模块主动重启。构建和宿主测试不代表实机亮度效果已经验证。
