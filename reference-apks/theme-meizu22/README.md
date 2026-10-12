# MEIZU 22 主题色参考应用

从已连接设备只读提取，应用枚举使用 `dumpsys package --all-components packages`，包括不在桌面显示入口的 Activity。仅由框架补出的 `android.app.AppDetailsActivity` 不计作应用界面。SystemUI 单独纳入。

筛选包括 `com.meizu.*`、`com.flyme.*` 应用，以及与系统设置、桌面、相机签名一致的固件应用；排除 Qualcomm/QTI 厂商后台与诊断包。提取所有安装的 APK split，逐个验证签名，并记录 SHA-256。`android` 的 framework-res APK 用作共享主题资源参考，不作为单独的应用进程注入目标。

- `theme-targets.json`：设备构建指纹、应用版本、界面数量、APK 校验值及实际主题颜色资源名。
- 原始 APK、framework.jar 和完整资源导出保存在本目录中，不加入 Git。
- `ThemeColorTargets.java` 与模块推荐作用域由参考清单生成。推荐范围限于有桌面入口的应用，以及 SystemUI、系统设置、安装器、权限管理、选择器等共享界面组件；后台目标不强制推荐。

重新提取和生成：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 python3 scripts/extract-theme-apps.py \
  --serial <adb-device> --output reference-apks/theme-meizu22 \
  --build-tools /Users/rikumi/Library/Android/sdk/build-tools/36.0.0
python3 scripts/theme-app-audit.py reference-apks/theme-meizu22/inventory.json
python3 scripts/generate-theme-targets.py reference-apks/theme-meizu22/inventory.json
```

颜色清单从 APK 内蓝、绿、红、黄、橙、紫、青色及主题色、主色、强调色、选中态等资源及其颜色引用提取，并保留浅色、深色和透明度变体。运行时按“包名＋资源名”限定 `Resources`、`TypedArray` 和 `ColorStateList` 的替换，保留灰、白、黑等中性色状态，不再全局修改 Paint 或按任意 RGB 匹配。

具名内置界面图标另使用像素滤镜，将彩色部分染为所选颜色并保留透明边缘和白色细节；头像、缩略图、预览图、引导图等资源排除。相机明确从注入目标、推荐作用域和资源清单中排除，其他相机功能的原有注入不受影响。

调色编辑器使用连续 HSV 与透明度滑条。修改颜色或关闭功能后，应重启目标应用刷新已缓存控件。硬编码且没有关联主题资源或图标名称的绘制颜色不在此方案的处理范围内。

每个应用的主色依据 Manifest 声明的主题、真实 style 父级及 colorAccent/colorPrimary 引用核对，保存于 `theme-audit.json`。普通颜色替换同时检查资源名与当前应用唯一的主色族，包含该应用使用的 android 框架颜色；红、黄、蓝、绿、橙之外的颜色保留。无充分主题证据的应用不替换普通控件颜色。图标染色不受单主色限制，但桌面通知角标资源排除。

纯黑背景功能与主题色开关独立，仅替换 `fd_sys_color_surface_default` 对应的 #0B0B0C（night）与 #232323（night-v888）全局背景色。具名全局背景资源与 View.drawBackground 两层处理；分组 surface_container_lowest、surface_bright 及其 #1C1C1E/#2D2D2D/#17171A/#202020 色值不作纯黑替换，保留原有层级。仅在当前 Resources 的 UI_MODE_NIGHT_YES 下生效，并保留 alpha；BitmapDrawable 照片背景不作像素修改。

安装器按实际安装、卸载活动的 FlymeDialogActivity/FlymePackageInstallerLitePopupTheme 绿色主题判定主色，而非 AOSP 通用 application 对话框主题。具名开关、按钮和 loading 颜色状态资源纳入主题资源表；存在延迟主题属性解析的 ColorStateList 仅对已标记资源在 obtainForTheme/状态查询时继续替换。图标的 ConstantState 缓存复制及 ImageView.onDraw 路径也保持具名来源限制。

文件管理主页源码 c6.h 为回收站选择 ic_garbage/ic_garbage_empty，后者矢量路径为 #7B3BE3，明确作为小图标纳入染色，不按 empty 名称排除。ic_folder_fastfolder_all/locked 等快捷文件夹、ic_shortcut_*、*_big 文件类型图标和储存分类大图标保留原样。主页容量圆环为 y7.a 自绘渐变图标，不纳入染色。

图库 GalleryTopTableFragment 将 Theme.resolveAttribute(colorPrimary) 的颜色缓存到 tab 文字与动画图标；主题属性入口及 photo1/album1/time1、tab_name/tab2_name/tab3_name 的绘制均纳入处理。商店 mz_ac_tab_*/start_ac_tab_* 和固定/移动 tab 图标使用独立白色镂空滤镜，先从原图颜色计算透明遮罩，再将其他部分染为主题色，普通 AI 图标仍保留白色细节。

商店 activity_background_color 及 BaseFragment.onCreateView 返回的页面根容器/getRecyclerView、底部导航容器单独处理 #17171A/#202020/#1C1C1E/#2D2D2D；只修改它们的背景绘制，不修改子分组、卡片的背景，也不改变浅色模式。其他控件仍仅匹配原有全局两色。

桌面删除、卸载、移除、垃圾桶等操作图标排除染色；其他主色未确认为红色的应用，对删除/卸载/移除/警告等具名操作图标仅保留其原始红色像素，其他颜色仍按图标规则处理。主色已确认为红色的图库允许该红色替换；紫色回收站入口与多色 AI 图标不适用此保护规则。

最新范围：文件管理完全退出主题色替换，先前大小图标规则撤销；纯黑背景功能独立。时钟 AppTheme 明确声明 windowBackground=?attr/colorSurfaceBright，纳入旧样式 #17171A/#202020 全局背景处理。共享 Theme.Platform.Flyme.Light 不作为旧样式判据，以免设置等新应用被误分类；商店依据实机反馈明确视为旧样式。模块页面背景也跟随纯黑开关即时更新，分组背景不变。商店未选中 tab 不使用白色镂空滤镜，选中动画按 BottomNavigationTab.r 的真实 drawable 判断。

未选中商店 tab 的 mz_ac_tab_app 矢量 fillColor 实际为纯黑，不能直接保留原像素。专用非选中态滤镜采用 fd_sys_color_on_surface_variant_default 的浅色 #993C3C43/深色 #73EBEBF5 保证可见，保持透明轮廓，不做白色镂空。

设置蓝色控件补充直接路径：Switch.getColorFromState 的返回值按蓝色主色替换，并 deopt 实际调用方 setThumbPosition。ImageView.setImageResource/setImageDrawable 记录具名图标来源；静态图标从副本绘制到 bitmap 后仅替换蓝色像素，保留白色、灰色、红色和 alpha，不依赖 RuntimeColorFilter。动画 Drawable 保留原实现。图标 tint 状态同步映射，并 deopt 设置进程中的资源和 View/ImageView 绘制调用方以防 AOT 内联绕过 hook。增加首次开关取色与图标像素替换日志供设备验证。

商店 BigBottomNavigationTab 的图片 ID 为 icon，按 BottomNavigationTab 祖先识别，避免影响其他应用图片。非选中颜色改为不额外叠加 alpha 的浅色 #757575/深色 #B0B0B0；动画存在时以 r 和当前 drawable 是否相同判定选择态，避免残留 isSelected 导致未选中图标被镂空。纯黑模式对 VerticalTabLayout 分类侧栏及其子项仅在背景绘制阶段覆盖纯黑。安装器 update_logo/update_logo_polestar（原始蓝+白矢量）纳入主题色与白色镂空规则。Miuix 0.9.0 HsvHueSlider 输入为角度，回调为 0..1；模块回调乘 360 恢复正常拖动。

无线网络/蓝牙/热点独立入口位于 com.meizu.connectivitysettings（Settings$WifiSettingsActivity、Settings$BluetoothSettingsActivity、Settings$WifiApSettingsActivity），原设置包的部分同名入口为兼容声明。连接设置使用声明的 colorSurfaceBright 窗口背景，按该进程单独纳入旧背景处理，并复用设置开关取色/蓝色图标像素替换及 deopt 路径。相机恢复推荐作用域以启用其它相机功能，仍排除主题色注入。
