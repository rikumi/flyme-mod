> 最终实现与验证（2026-10-13）：方向判定采用首次超过原生滑动阈值的采样。系统服务与 SystemUI 同步读取宽度设置，默认 32dp、最大 64dp，关闭后恢复原生宽度；越出区域时振动，回到区域内取消，松手返回不重复振动。12dp 内外统一判断。342 项实际钩子检查通过，最新 APK 已编译、验签、发送并安装。用户在扩大区域后反馈“似乎可以了”；后续边界振动、箭头和指纹反馈尚无完整实机验收。下文保留调试过程中的历史证据与中间实现，不应将其回撤分析或旧测试数量当作最终实现。最终失败记录显示大量触摸起点为 75–110px，超出原生 72px 区域，在方向判断之前即被排除。

# Flyme 侧滑返回处理流程与 12dp 边界

分析日期：2026-10-13。目标：MEIZU 22，Flyme 12.6.0.0A / Android 16，SystemUI 16.26.06.25_base（16260625）。

## 证据来源

参考文件为 `reference-apks/small-window-meizu22-12.6/` 中从目标设备提取的文件。本轮通过设备端 `sha256sum` 确认以下文件与参考文件一致：

| 文件 | SHA-256 |
| --- | --- |
| SystemUI APK | `069e1977149da5d1d311b0100876ddea108e4558a803b3438bf84ba029fad856` |
| services.jar | `a8996d2036ed2d1f8ee6e36e71c5753bcec77f93ad60bcadf4d9fd241241d670` |
| service-flyme.jar | `6bdd20d92ea56ca299a0226627c10471f8f321a66be1bf1a2fa6a195a6407c22` |

SystemUI 的 `EdgeBackGestureHandler`、`EdgeBackView` 又通过 JADX 的 `--respect-bytecode-access-modifiers` 重新提取，核对反射字段、方法签名与真实访问权限。分析没有采用其他 ROM 的手势实现。

设备端只读资源查询确认：

- `android:dimen/left_right_swip_gesture_valid_offset` 为 24dp，系统服务用它设置 `GESTURE_VALID_LEFT_OFFSET`。
- `android:dimen/config_backGestureInset` 在当前 mBack 导航 overlay 下为 24dp。
- 当前屏幕 1200×2670，覆盖密度 482dpi；SystemUI 实际左右宽度均为 72px。
- `systemui/use_back_gesture_ml_model=true`，模型阈值为 0.8。

## 完整处理流程

1. 系统输入进入 `SystemGesture.interceptMotionBeforeQueueing`，由注册的手势监听器逐个处理。侧边监听器为 `LeftRightGestureListener`，继承 `RecentGestureListener`；其顺序位于游戏助手监听器之后、底部监听器之前。
2. `RecentGestureListener.interceptMotionBeforeQueueing(DOWN)` 保存原始落点。`isInRecentPanelModeArea` 检查侧边宽度、屏幕方向、顶部和底部限制及游戏助手区域。竖屏侧边须位于屏幕高度的 1/4 以下，并避开底部导航范围；横屏有对应的底部范围限制。
3. 区域合格时调用 `GestureListenerBase.notifyGesturePreTriggerBefore`。它通过客户端调用 SystemUI 的 `EdgeBackGestureHandler.SystemUIGestureListener.onGesturePreTriggerBefore`，返回 0 才进入 `PENDING_CHECK`。SystemUI 此时检查系统禁用标志、应用阻止、游戏模式、`isWithinInsets`、`isWithinTouchRegion` 等条件。
4. 合格 DOWN 通过 `onGesturePreTrigger` 转到 `updateGestureEvent`，再经主线程的 `lambda$updateGestureEvent$18` 进入 `EdgeBackGestureHandler.onMotionEvent`。该方法再次进行区域资格检查，并将 DOWN 交给 `EdgeBackView`。
5. 系统服务收到 MOVE 时，仅在 `PENDING_CHECK` 状态调用 `checkRecentsGesture`。原生逻辑要求在 300ms 内达到位移门槛，侧滑角度小于 64°，并朝屏幕内部移动。进入 `TRIGGERED` 后才通过 `notifyGestureTriggered` 转发 MOVE 给 SystemUI；未达到门槛的 MOVE 不转发。
6. SystemUI 的 `onMotionEvent` 在达到触摸位移门槛后标记 `mThresholdCrossed`，通知预测返回动画或获取指针。Flyme 普通路径使用系统服务转发；CTS 路径还可能经 `onInputEvent` 输入。
7. `EdgeBackView.onMotionEvent(MOVE)` 调用 `handleMoveEvent` 和 `whetherTriggerBack`。原生方法根据水平累计位移、拖动门槛和反向移动量更新 `mTriggerBack`。
8. UP 时，`EdgeBackView` 根据 `mTriggerBack` 调用 `triggerBack` 或 `cancelBack`。回调保留游戏防误触、单手模式等原生处理；没有预测动画时注入 KEYCODE_BACK 的 DOWN/UP，有预测动画时调用 `BackAnimation.setTriggerBack(true)`。
9. `BackAnimationController` 在手势结束时根据 tracker 的 `getTriggerBack()` 调用 `invokeOrCancelBack`，最终执行 `IOnBackInvokedCallback.onBackInvoked()` 或取消回调。没有按触点宽度再执行的机器学习分类。

`FalsingManager.isFalseTouch(16)` 虽在获取指针和确认返回时被调用，但这些位置没有使用其返回值，因而不能据此解释 12dp 边界拒绝。

## 12dp 的直接源码证据

`EdgeBackGestureHandler.updateCurrentUserResources` 明确执行：

```java
mMLEnableWidth = (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, 12f, metrics);
```

随后将该值限制在左右有效侧边宽度以内。当前密度下原生 12dp 约为 36px，24dp 侧边宽度约为 72px。

`isWithinTouchRegion` 先判定是否位于左右几何侧边区域，再对**几何区域内、12dp 以外**的触点执行以下附加条件：

```text
x >= leftInset + mMLEnableWidth
&& x < displayWidth - mMLEnableWidth - rightInset
&& mUseMLModel
&& !mMLModelIsLoading
```

条件成立时，`getBackGesturePredictionsCategory` 使用显示宽度、距侧边距离、左右侧、应用词表索引及 y 坐标调用 TensorFlow classifier。预测低于阈值时返回 0，`isWithinTouchRegion` 将原本合格的几何触点改为不合格。

该分支可在 DOWN 的预判和 SystemUI DOWN 的资格判断中拒绝触点，此时 MOVE 方向钩子尚无机会执行。但本 ROM 的 `GestureModule.providsBackGestureTfClassifierProvider()` 实际返回默认空实现：`isActive()` 恒为 false、`predict()` 恒为 -1，模型因此被原生加载逻辑释放。模型缺失、词表索引为 -1、尚未加载或预测不可用时保留几何判定。故 12dp 是附加分支的起点，不能据此证明本 ROM 的实际失败由机器学习导致；本轮对真实工厂和 provider 的核对排除了这一判断。

## 原实现存在的确定问题

### 机器学习绕过依赖被停用的宽度钩子

此前 `BackGestureWidthHooks.apply` 在“放宽侧滑返回判定”开启时将 `mMLEnableWidth` 改成左右区域宽度的最大值，令上述预测分支没有可作用的区域。它没有关闭 `mUseMLModel`，模型状态仍可开启。

`55fc8b2` 为暂时停用宽度功能移除了整个 `BackGestureWidthHooks` 注册。于是宽度控制与机器学习绕过一起失效，而 `BackGestureSmartHooks` 原本只处理 MOVE 和视觉确认，没有独立绕过 DOWN 的预测分类。这是当前源码中确凿的依赖错误，需独立修正；但当前 ROM 工厂提供空模型，因此它不是本轮失败手势的直接原因。

同时，`onInputEvent`、`lambda$updateGestureEvent$18` 和监听器预判等调用点的反优化也放在宽度钩子里；停用它后，方向钩子缺少自己的完整调用点反优化。是否在某个实际 ART 编译状态下被内联绕过，仍须运行时日志证明。

### 系统服务与 SystemUI 判断的不是同一个首帧

原生 Pending MOVE 不转发，SystemUI 看到的首个 MOVE 已可能是后续帧。原实现又在 SystemUI 和 `EdgeBackView` 中重新计算角度，因此“真实首帧合格、后续轨迹变陡”的手势可能被后两层否决。

本轮旧进程日志中的同一次手势记录如下：

```text
03:38:02.097 system_server: dx=-3.5      dy=1.0996094 accepted=true
03:38:02.110 SystemUI:     dx=-12.599609 dy=4.9003906 accepted=true
```

该记录证明两层日志对应的位移不同；“后续角度不合格时会重复拒绝”进一步由原始转发流程和真实钩子回归证明。

## 失败动作的直接证据

用户加载第一版修正后再次反馈无效。新进程日志证实 `mUseMLModel=false`、原生宽度 72px、原生 `mMLEnableWidth=36px`；多个 x=61.8–71.5px（约 20.5–23.7dp）的触点，SystemUI 区域判定、系统服务区域判定及两层首帧判定均为 true，MOVE 确实到达 `EdgeBackView`。所以这批失败发生在确认状态，而非区域拒绝。

04:02:24–25 的动作起点为 `(61.799805, 1730.4004)`。已捕获 MOVE 包含 `(350.5996, 1843.9004)`，末个 MOVE 为 `(101.0, 1768.9004)`：径向距离由约 310.302px 减少到 54.945px，连续回拉约 255.358px。原生 `mMinDeltaForSwitch=32dp`，当前密度下为 96.4px。`whetherTriggerBack` 在累计负位移超过该门槛时执行 `mTriggerBack = mTotalTouchDelta > 0`，于是清除返回标志。随后实机日志出现 `action_up_cancelBack` 和 Shell 的 `mTriggerBack == false`，与源码状态转移一致。

第一版钩子只把水平位移换成径向位移，仍调用原生确认方法，故仍会被后续大幅回拉取消；这未满足用户要求的首帧资格锁定。当前修正版直接锁定确认：首帧合格并达到原生 16dp 位移门槛后，保持确认至抬手，后续回拉不重新否决；显式 CANCEL 仍正常取消，关闭功能仍走原生确认方法。

证据证明了这批实际失败的取消环节，并不能支持“任意 12dp 外触点都被拒绝”的概括；此前 x=71.5px 的一次动作还出现成功 `triggerBack` 的日志。

## 本次修正

- 方向钩子独立拦截 `updateMLModelState`，通过原生停用分支释放 classifier、清空词表，恢复临时借用的 `mIsGestureHandlingEnabled` 状态。
- 独立拦截 `getBackGesturePredictionsCategory`：开启功能时返回原生“无预测”值 -1。即使模型仍处于初始化或有异步加载任务，也不会执行推理或否决几何触点。
- 开关变化时在宿主 UI executor 刷新原生模型状态；关闭功能恢复系统配置的模型行为。
- 方向钩子自行反优化模型配置回调、区域判定及各事件入口，不依赖已停用的宽度钩子。
- 系统服务以实际 Pending MOVE 中最早的非零样本锁定 `abs(dy) <= 5 * abs(dx)`，包括批量事件中的历史样本；零位移样本不消耗首帧。
- 对系统服务转发的侧滑序列，SystemUI 使用系统服务已接受的结论；直接输入路径仍自行锁定首帧。SystemUI 与 `EdgeBackView` 共享同一结论，后续转向不重新按角度拒绝。
- 保留原生有效区域、应用排除区、上下范围、屏幕状态、最小位移、300ms Pending 超时及显式取消；返回确认使用径向位移。合格首帧达到原生 16dp 确认门槛后保持返回标志，不再用累计回拉重新否决。关闭功能恢复原生回拉取消。
- 返回区域宽度、手势滑动条宽度和 mBack 波纹适配钩子继续保持停用。

1:5 的边界包含在内；半角为 `atan(5)` 约 78.69°，两侧合计约 157.38°。纯纵向首帧被拒绝；系统服务仍保留朝内部移动及反向取消的原生方向语义。

## 验证结果与限制

- 真实四个实现类编入宿主回归夹具，201 项检查通过，覆盖有效模型返回拒绝时的分支复现（该模型夹具不是本 ROM 的空实现）、左右侧 12–24dp 区域、正负 1:5 边界、边界外拒绝、历史样本、真实首帧与后续转向、确认后回拉仍返回、显式 CANCEL、直接输入、开关恢复、区域排除、超时、最小位移、多指取消及关闭时的原生回拉取消。
- 夹具按参考源码保留上述状态转移和相关方法体，但位移门槛用测试值，省略 Android 渲染、Binder、Shell 动画和 ART 编译；这些检查不能替代实机验收。
- 独立首帧数学策略检查通过；`git diff --check` 通过；JDK 21 Debug 构建成功，APK 签名验证通过。
- 第一版 APK 已发送、安装且经用户重载。实际日志确认两层新钩子注册、机器学习关闭和 12–24dp 区域准入，随后用户反馈未解决；该失败已由以上确认状态证据解释。
- 第二版 `flyme-mod-first-frame-confirmation.apk` 已构建、签名验证并发送；其确认锁定与关闭恢复仍待实机验证。
- 初次读取的旧宿主进程显示 `mUseMLModel=true`，但 `mMLEnableWidth=72`，与左右边缘宽度相等；该进程仍有旧宽度钩子的绕过效果。该快照不能证明它当时在 12–24dp 区域执行了拒绝预测。
- 03:55:22 的原生日志还出现右边缘约 16dp 落点 `x=1151.7002` 的 DOWN，客户端预判返回 0。因此不能声称所有当前进程的 12dp 外触点都已被机器学习拒绝；用户所述失败手势仍须落点、方向、取消和最终返回日志一一对应。

本轮结论：12dp 附加预测分支存在，但本 ROM 的实际模型为空实现，不能将它认定为失败原因。已用加载后的真实日志定位这批失败为视图累计回拉清除确认标志，并完成首帧跨层语义、模型独立停用和确认锁定修正；第二版修正的最终实机效果仍待验证。
