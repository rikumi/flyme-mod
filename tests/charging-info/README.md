# 状态栏充电信息验证

目标为 MEIZU 22，SystemUI 16260625 / 16.26.06.25_base。

- `FlymeBatteryMeterView.apply(boolean)` 根据 `mCharging` 选择充电图标，`mBypassCharging` 则选择旁路图标；仅插入电源的 `mLastPlugged` 不能作为充电显示条件。
- `system_icons.xml` 的原生横向 LinearLayout 依次包含状态图标容器、电池及百分比。新增文字作为电池左侧兄弟视图参与原生测量；保留图标和百分比，沿用百分比字号、间距及 ClockFontHooks 的字体、字重和字间距处理。
- 设备实际节点 `/sys/class/power_supply/usb`、`wireless` 的 `voltage_now`、`current_now` 读取输入端 ADC。2026-10-12 充电中实测 USB `online=1, voltage_now=17463000, current_now=1290000`，实际单位为 µV、µA；按该快照计算应显示 `23W` 或 `18V 1.3A`。初版根据断电时 `4000/6000` 的电压及日志排版推断成 mV/mA，错误拒绝了全部真实充电读数；已纠正为原始微单位计算，并加入真实快照及微单位边界回归。断电数值不用于推断单位。
- Provider 的 `sample_micro` 提供原始微单位，新 hook 精确计算；原有 `sample` 字段保持 mV/mA 协议，供安装前已加载的旧 SystemUI hook 使用，避免仅为数据修复要求重启作用域。旧字段转换只在输入原始读数有效且在线状态一致后进行。
- `BatteryService` 广播中的 `voltage` 是电池端 mV，`max_charging_current/voltage` 是最大值；均不参与实时输入功率计算。设备电池 `power_now` 在未充电时仍出现异常大值，亦不采用。
- 读取不限制机型，优先 USB、无线输入节点，再识别实际在线的 USB/Mains/Wireless 输入端；排除 Battery、BMS、UCSI/source 协商节点，无线节点缺失不会阻止 USB 读取。root 读取命令只读且超时受限，Provider 验证调用 UID 与模式，并缓存三秒；SystemUI 在后台每五秒采样。无有效数据、驱动未提供标准微单位实时 ADC、权限被拒绝时隐藏，不猜测其它设备的单位。
- `python3 tests/charging-info/test_polling.py` 编译实际 formatter/parser，并执行生产 `read/reconcile` 回调，覆盖取整、区域格式、单位边界、断开一致性、原生状态组合、重复调度、关闭及重新开启、旧异步结果丢弃与错误清空。
- `python3 tests/charging-info/test_view_lifecycle.py` 执行完整生产 State（含 createLabel/update/render/attach/detach）及样式方法，核对实际构造的视图树：居中的垂直 LinearLayout 内仅有两个独立 TextView，上行电压、下行电流；二者为原字号的一半、同字体族和字重，均为单行，不依赖换行字符串。功率模式复用上行并隐藏下行，恢复原字号。覆盖切换、复用、重复 attach、字号及字体变化、左右/RTL 间距、关闭、非充电、隐藏、过期和 detach 清理。两行宽度由较宽一行决定，容器在电池同一父布局内参与原生测量。
- 一键开启不为新项选择模式；一键关闭清除已选择的充电信息模式。
- 充电文字物理左侧间距增加 2dp，靠右侧首个图标的一侧间距减少 2dp；从原生百分比的间距和实时 density 计算相对 margin，RTL 对换 start/end。参考资源原间距为 3dp，调整后为左侧 2dp、靠图标侧 1dp；不改变电池自身原生 margin。原间距不足 2dp 时靠图标侧限制为零，避免负间距重叠；重复绘制不累加。

最新位置调整：信息位于右侧图标组的物理最左端。LTR 下作为 `system_icons` 中 `statusIcons` 的前一兄弟项，网速文字属于其左侧相邻内容；RTL 下移到外层图标组的最后一个逻辑子项，保持物理左端。文字自身 WRAP_CONTENT、无权重，`StatusIconContainer` 的权重及其 StatusIconDisplayable 子项不改变，不把普通 TextView 插入会强制转换图标接口的原生容器。保留左侧增加 2dp、右侧第一图标靠近 2dp 的非负间距计算；模式切换、复用和布局方向改变均重新确定 leading 索引。

2026-10-12 修复后实机只读验证：用户模式仍为 `1`，SystemUI 日志确认 `StatusBar` 和 `KeyguardStatusBar` 绑定、`valid_input` 采样；普通状态栏截图已显示电池及充电标记左侧的 `10W`，百分比和其它图标正常。当前未改变用户模式或插拔电源，电压电流模式、关闭回退、拔线、明亮背景及拥挤布局仅经源码与宿主验证，仍待实机确认。没有主动重启设备或作用域。

2026-10-12 23:21 双控件修复后实机只读验收：用户原有模式为 `2`、USB 在线；截图显示上行 `9V`、下行 `0.9A`。`cmd window dump-visible-window-views` 的 StatusBar 原生层级经对应 framework `ViewHierarchyEncoder` 解码，确认垂直 LinearLayout 中有两个可见且独立的 TextView，父容器宽 41px、高 60px，gravity=17；两行 textSize 均为 19.5px，原生百分比字号为 39px，恰为一半。上行边界 left=8,right=32,top=3,bottom=30，下行为 left=0,right=41,top=30,bottom=57，证实上下独立布局、水平居中及整体垂直居中。本轮未修改用户模式、未主动重启；切回功率、关闭及拔线仍只经完整生产 State 宿主检查，未在设备上主动触发。

最新 leading APK 已构建、验签并安装到两台在线设备。MEIZU 22 的安装后只读截图仍是旧作用域中的电池旁布局，尚无新的 leading placement 日志；未主动重启作用域，不能把本次 leading 位置称为已实机验收。

2026-10-12 MEIZU 21 Pro 只读检查：USB_PD 输入端在线，voltage_now=8226000、current_now=1891000；旧机型白名单会直接过滤该设备，现已取消。UCSI source 节点的 39000000 不参与显示。机型无白名单不等于所有固件均已实机验证。

最新样式覆盖此前半字号要求：电压电流两行使用原字号 65%，保留字体族与字重并额外加粗，两行等分原生状态栏高度且各自垂直/水平居中，靠右侧首个图标的额外 margin 为零，左侧仍为 2dp。功率模式清除加粗、恢复原字号及原行高布局。新版样式尚未实机验收。

兼容性构件已安装到 MEIZU 21 Pro 和 MEIZU 22；21 Pro 安装后的只读截图 /private/tmp/flyme-21pro-charging-compatible.png 显示右侧图标组最左端的双排 9V/1.9A，右侧为 Wi-Fi、充电标记、电池和百分比。该截图确认有效显示与 leading 位置，不是后续 65% 字号加粗版本的视觉验收。后续充电样式及歌词避让合并构件已验签、上传并安装到当时唯一在线的 21 Pro；没有重启或修改设置。

最新行高要求覆盖 65% 与等分高度方案：两个独立 TextView 使用状态栏原字号一半，lineSpacingMultiplier=1、lineSpacingExtra=1dp，保留原字体族/字重并额外加粗。按原生 WRAP_CONTENT 字体行高测量、weight=0，不设置固定高度；整个双排组居中。目标 framework 的 BoringLayout.init 使用 descent-ascent，单行不增加 spacingAdd；StaticLayout 默认排除末行额外间距，因此仅下排设置 1dp topMargin 实现真实跨控件间距，不额外叠加两份间距。功率模式恢复原字号、extra=0、下排 margin=0 和非额外加粗。

最终单排要求覆盖此前全部双排样式：电压电流以 `5V 3A` 在上行 TextView 单行显示，下行为空且 GONE；原生百分比字号和状态栏字体族/字重，无额外加粗、半字号、固定高度、权重分配或行间距，下行 margin 清零。物理左右各 2dp，RTL 对换；功率模式样式和间距保持不变。该最新版视觉尚未实机验收。

2026-10-13 最终双排要求覆盖之前单排、加粗及 1dp 行距：两个独立 TextView，上行电压、下行电流，字号约为原状态栏 55%，通过原生 px→sp 逆转换与 TextView SP 设置保留当前屏幕密度及非线性字体缩放。Typeface.create 保留所选字体族与斜体，明确权重 600，不使用 BOLD(700) 或 fake bold；单倍行高、extra=0、topMargin=0、WRAP_CONTENT 且 weight=0。生产 getLineHeight 与实际父高度比较，仅容纳不足时略微缩小，两行居中，父高度恢复后回到 55%。功率模式恢复完整字号与字体偏好，间距及充电态条件保持。只读设备层级原字号/父高度：22 为 39px/60px，21 Pro 为 42px/65px；新版实际样式仍需作用域加载后确认。

交付：86 项生产视图宿主检查、diff check、JDK21 build、固定证书验签通过；build/handoff/flyme-mod-charging-stacked-weight600.apk 已上传并成功安装到两台在线实体设备（21 Pro、22，22 的第三 endpoint 为重复连接）。安装后只读证据：22 无外部电源、status=3；21 Pro 充电中，但视图树仍是旧作用域的 42px 单排（下行 GONE），未加载此次双排 hook。未主动重启，不能将新版 600 权重、双排行高或窄空间效果称为实机验收通过。

最新位置覆盖 leading 组首要求：标签作为原生 system_icons 中电池 view 的相邻 sibling，保持物理左侧（RTL 调换插入位置），整个原生电池/充电 drawable 保留。数字使用 ChargingDigits.DigitCell 的 ReplacementSpan 固定为当前字体十个数字中的最大 advance，按原 Paint 字体/字号/字重绘制且居中，不依赖 tnum 支持，不改字母和小数点。88 项实际视图检查及 40 项实际数字跨度测量/绘制检查通过，双排 55%/600/零 extra 与功率模式保留。新版位置/数字实机效果尚未确认。

2026-10-13 最新要求覆盖所有双排和强制等宽方案：两种模式均原字号单排，电压电流例 `5V 3A`；下行 GONE，原生字体族/字重、无额外加粗、单倍行高、extra=0、marginTop=0，右侧 2dp，位置仍在电池/充电图标左侧。删除 DigitCell 固定 advance，使用纯字符串；ClockFontHooks 仅对充电标签取消 tnum，不影响其它时钟的等宽偏好。83 项生产视图及 9 项实际字体选择器检查通过，新版视觉仍未实机验收。

2026-10-13 默认策略更新：无保存值时三态模式取 POWER=1，歌词避让为 true；设置 UI、Provider、远程桥、system_server 缺省值与 SystemUI 初始化/缺失列回退一致。已保存的 0、2、false 保留。一键开启选择功率值并开启避让，一键关闭仍写入明确的 0/false。默认开启不改变数据校验与无有效输入时隐藏逻辑。
