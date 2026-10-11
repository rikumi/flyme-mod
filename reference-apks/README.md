# Flyme 参考 APK

均于 2026-10-02 从 MEIZU 21 Pro 的 Flyme 12.6.0.0A 设备只读提取。构建指纹：

`meizu/meizu_21Pro_CN/meizu21Pro:16/BQ2A.251016.001-BP2A.250605.031.A3/1763701752:user/release-keys`

Android 16 / API 36，安全补丁 2025-12-05。文件名对应包名；SHA-256 用于确认文件完整性。

| 包名 | 设备路径 | versionCode | SHA-256 |
| --- | --- | ---: | --- |
| `com.meizu.flyme.launcher` | `/system_ext/priv-app/FlymeLauncher/FlymeLauncher.apk` | 13000000 | `ed16f2ab35b6c896b599ac45e09ede1df2bec27120eda106328837400b7f9d3d` |
| `com.android.systemui` | `/system_ext/priv-app/SystemUI/SystemUI.apk` | 16260625 | `962ab715e87b098f9e4fe6e646c87601a531839d30c1b8996cc798e9c8ec87f6` |
| `com.android.settings` | `/system_ext/priv-app/Settings/Settings.apk` | 12001000 | `73cfb5953c5520cce0a554902517057569a86b59edd42ff455de4e7a97ba8090` |
| `com.meizu.safe` | `/system/app/MzSecurity/MzSecurity.apk` | 12001048 | `25b2b2a3a0e1bc6e661e105435e51622d58980a6e3920122a88e4e32fae667e6` |
| `com.meizu.privacy` | `/system/priv-app/PrivacyController/PrivacyController.apk` | 8 | `240cde4882af1a95399b7226a322e728745968393224a6efb6eb12df661aa33c` |
| `com.android.phone` | `/system/priv-app/TeleService/TeleService.apk` | 36 | `5cca0fa67f03f9c6bfcd3932c11181ae9ee9909471f5c91cb46480b6cc7c8d4d` |
| `com.meizu.callsetting` | `/system/priv-app/MzCallSetting/MzCallSetting.apk` | 12000011 | `246d99019f31316f51e65cfb8c2dd688f1122b1cf84f12642a9403f8b187b9a5` |
| `com.meizu.connectivitysettings` | `/system_ext/priv-app/ConnectivitySettings/ConnectivitySettings.apk` | 12015000 | `5ffc2981ae1a9e20be27e2f8e43741a19e02c79d10b1cd9ab7e5781f83ff1423` |

这些 APK 是本地开发参考，不会被打入 Flyme Mod APK。

## 当前设备的应用商店与天气

2026-10-06 从同一 MEIZU 21 Pro 只读提取。当前实现以这些版本为主要参考，
下方公开下载的旧版本保留用于兼容验证。

| 文件 | 设备路径 | versionCode | SHA-256 |
| --- | --- | ---: | --- |
| `com.meizu.mstore-12.5.1.apk` | `/system/app/AppCenter/AppCenter.apk` | 12205001 | `d0999b4398b39095ccd2f35391f6b71754a83886b7127a62563e19ea3e9d15bb` |
| `com.meizu.flyme.weather-54.3.60.apk` | `/system/MzApp/Weather/Weather.apk` | 54003060 | `26d694f7852f2ad2e40b50acebd18b091f452fb7d24184bbf7e36e0801f0e6d7` |

LSPosed 日志确认此前模块已加载，但旧版映射失配：天气的 `g4.b` 不再是功能枚举，
读取 `NEWS_LITE` 抛出异常；商店的 `nc.d` 不再是热词控制器，读取字段 `k` 抛出异常，
并使后续设置读取 hook 未安装。现在先安装设置读取，并分别捕获广告、内容、搜索及页面
功能解析失败，避免单项失配中断其他功能。

天气 54.3.60 使用 `WeatherFeatureManager#f(WeatherFeature)`，另外包含详情页的
`DETAIL_AD` 和 `DETAIL_NEWS`，分别跟随广告与推荐开关。广告回调为 `z1.b`，
空配置 Observable 为 `rx.d#l(Object)`，列表入口为 `multitype.f#k(List)`。
原生广告模型统一继承 `h3.b`，提交列表前按类型移除；首页片段为
`com.hy.weather.mz.modules.home.j`，推荐文章及尾部模型为 `c4.a`、`s4.a$a`，
资讯请求回调为 `r8.a`。新版已移除旧 `BaseHfActivity$c`；详情页广告沿用
`AdHelper#m` 的无广告回调隐藏容器。

商店 12.5.1 的启动广告判断为 `rd.j#b()`；热词控制器为 `ge.e`，字段 `k` 持有
首页 Activity，`c(boolean)` 提供提示，`f()` 启动轮播。应用页工厂为
`com.meizu.cloud.app.utils.g#c(Context, jd.b)`，页配置 `jd.b` 的字段 `g` 为
`jd.a`，页面编号字段为 `page_id`。导航与内容区块的数据结构保持不变。

已核对实际 APK DEX 中的 35 个 hook/反射方法签名和相关字段，并对新旧版本执行
天气开关组合及商店 128 种开关组合逻辑测试。构建通过不代表实机界面效果已验证。

## 从公开站点下载的参考版本

以下样本于 2026-10-05 下载，均通过 APK 签名校验，签名证书为魅族 m9，
证书 SHA-256 为 `94153299d67ab1b37a39ed5fe17e893df87084fb129077be51863bcbd9132336`。
这些版本早于当前设备版本，下述混淆类名仅对应这些旧版样本。

| 文件 | versionCode | 来源 | SHA-256 |
| --- | ---: | --- | --- |
| `com.meizu.mstore-11.1.5.apk` | 11001005 | [骑士助手](https://www.qt6.com/XiaZai/185214.html) | `dbb293db377ab28be812e8db37a1ea0b4f8df29db8e2b7396d60939bb5d8a959` |
| `com.meizu.flyme.weather-52.0.13.apk` | 52000013 | [APKMirror](https://www.apkmirror.com/apk/meizu/weather-16/weather-16-52-0-13-release/weather-52-0-13-android-apk-download/) | `3c120830f53e91f782cf88f9cf9ae0f296aefc9f8aba09ff3c400aa7aa7b3c9a` |

应用商店：`BaseActivity` 的重定向代理实现 `JumpFirstAdInterface`。
11.1.5 的 `hc.l#a()` 返回 false 时继续首页初始化，不进入开屏广告流程；
两个广告成功回调分别打开 `FirstAdActivity` 和 `SplashAdActivity`。
模块在已验证版本跳过广告判断，并将两个成功回调转入 `noAd()`。

应用商店布局：`ResultModel#getValue()` 返回 `MainValue` 后，在首页 presenter
读取导航列表之前过滤 `MainValue.nav`，通过 `NavItem.page_type` 的 `home`、`game`
识别精选与游戏 tab。去除精选时将 `app` 移至列表首项，并清除精选页的预加载
区块和二楼数据；导航条和页面均使用过滤后的同一列表。
`AssembleTool#assembleSingleBlockItem()` 的两个重载统一按 `BlockItem.name`
过滤“大家都在用”“魅友安利”“每日推荐”，避免留下标题或空卡片。
这些标题来自服务端，名称改变后需重新核对。更改设置后重新启动应用商店。

天气：`g4.c#f(g4.b)` 查询功能开关；`NEWS_SDK` 控制首页信息流，
`NEWS_LITE` 控制生活指数和预警页资讯。模块在 `WeatherApplication#onCreate()`
和 `Application#attach(Context)` 阶段读取设置，开启屏蔽时将上述两个功能及
`HOME_FORECAST` 推荐文章功能判断返回 false，不修改应用原有偏好。
更改开关后重新启动天气，以重新创建页面内容。

天气全局去广告独立控制 `AD`、`SHOW_SPLASH` 功能判断。
`AdHelper#m(Context, int, d3.b)` 在开启时直接调用原有 `onNoAd(1012)`，
覆盖原生开屏、详情页和 WebView 请求的广告位。
`AllAdConfig` 的 `getMzAd`、`getCpAd`、`getHomeAd` 及天气数据的
`ValueData#getAdBeanList` 返回空列表，覆盖网络与缓存的贴片数据。
资讯组件通过 `NewsFullManager#R(int)` 和新闻广告缓存 `e$d#getAds()`
移除广告插入数据，保留资讯内容本身；内容推荐开关继续独立生效。

天气广告和推荐前置过滤：`multitype.h#l(List)` 在提交列表前移除 `w4.b$b`、
`x4.a` 广告条目，以及推荐标题 `CategoryForNewsFlow`、`NewsFlowBean`、
`NewsSdkInfoFlowView`、首页推荐文章 `b5.a` 和资讯尾部占位 `o5.a$a`。
`AdHelper#j()` 不请求广告配置，返回空配置的 Observable；旧版广告 `AdView#load`
也不执行请求。`s4.i#O(ValueData)` 不加载顶部广告图；创建首页时将广告位置改为
GONE，并清零高度。华风页面的广告容器回调 `BaseHfActivity$c#a` 同样清零占位。
推荐开关还阻止 `NewsFlowView#h/i` 加载和资讯 SDK 的创建、启动、恢复事件。

天气背景压暗：仅处理 `WeatherMainActivity` 内 `WeatherBackGroundView` 的视频
及静态过渡帧。当前统一通过 GPU RenderEffect，亮度系数 0.68、饱和度 1.25、对比度
1.12，避免此前 0.48 的亮度与 1.40/1.25 的饱和度、对比度调整过强。没有黑色遮罩，
天气卡片和文字不参与滤镜。关闭开关清除视频和静态帧的 RenderEffect。

应用商店搜索热词：拦截 Retrofit 代理 `retrofit2.Retrofit$1#invoke` 中
`MainApi#getSearchHotStr()`，返回空 Observable，不请求热词；`nc.d#b(boolean)`
始终提供系统“搜索”提示，并禁用 `nc.d#d()` 轮播和 `AppMainActivity#setHotHintStr`。
应用页空白：从 `MainValue.nav` 的 `page_type=app` 获取过滤后位置和 page_id，
在 `com.bumptech.glide.f#a(Context, zb.c)` 创建首页一级应用页之前替换为空 Fragment，
同时匹配 AppMainActivity、position 与 page_id；不创建 Feed、WebView 或嵌套 tab。
保留 Activity 外层的搜索框、下载管理和导航。应用页为首项时清除预加载区块和二楼数据。
更改上述开关后重新启动应用商店。天气入口使用 miuix 的 Background 线条图标。

## 当前布局与背景切换修正

2026-10-06 的 UIAutomator 与 Activity dump 显示应用页为 `Home_应用`，
外层页面编号 9865，内部应用 Feed 编号 9869，仍含分类标签、推荐列表及刷新容器。
缓存首页绕过 `ResultModel#getValue()`；现在从 `AppMainActivity#setupViewPager`
接收的实际导航列表记录应用页位置与编号，覆盖缓存和网络路径，不写死服务器页面编号。

详情推荐在 `AssembleTool#assembleAppDetailRecommend(RecommendValue, boolean)`
装配前过滤 `RecommendValue.blocks`。两个独立开关分别移除标题包含“同机型”且
包含“喜爱”或“喜欢”的区块，以及 `type=special` 的所在专题区块，避免只隐藏标题。

应用评测开关移除同一推荐列表中 `type=h5_ext` 的区块；原生装配方法使用此类型生成
“应用评测”标题和 `AppDetailH5ExtItemData`，在装配前统一移除，避免留下标题或占位。
三个详情开关的全部 8 种组合及原有商店功能回归测试通过。

天气此前视频使用独立 Surface/HWC 颜色矩阵，聚焦与失焦效果不一致；SurfaceFlinger
记录显示矩阵存在而图层仍为 DEVICE 合成。叠加 ColorLayer 后虽能压暗，但用户反馈
聚焦时偏灰黑、失焦时过曝，因此已删除独立矩阵和黑色图层方案。

当前在原 SurfaceView 后、过渡遮罩前插入 TextureView，拦截匹配原 SurfaceHolder 的
`MediaPlayer#setDisplay`，将输出改为 TextureView 的 Surface。保留原 SurfaceView
及其 SurfaceHolder 回调管理播放器生命周期，同步 `SurfaceView#setAlpha` 到纹理，
隐藏无用的原 SurfaceControl，并跳过该 SurfaceView 的 draw/dispatchDraw 开孔。
TextureView 与静态帧共用同一个 RenderEffect，聚焦与失焦不改变滤镜或参数。
纹理未就绪时暂不输出视频，以保留静态过渡帧；窗口分离时断开播放器输出并释放 Surface。
首次关闭开关时不创建纹理；已切换的页面关闭开关后清除滤镜，继续使用无滤镜纹理。

新增播放器输出、释放、SurfaceView 渐变、更新和绘制 hook，以及天气播放器字段 `c`，
均与当前天气 APK 和只读提取的 `/system/framework/framework.jar` 核对。输出就绪前后、
渐变顺序、其它 Surface 不受影响、动态关闭和窗口分离释放的模拟测试通过。
构建和接口核对通过，焦点状态下的实际显示效果尚待实机验证。

商店 12.5.1 搜索推荐开关：`SearchFragment#s()` 是空查询搜索首页的独立入口，
原来在此读取推荐缓存或请求 `SearchApi#getHotSearch()`。开启时先调用 presenter
`w#G()` 取消未完成加载，再使用原生 `SearchHistoryItemData(List)` 渲染 `w.U` 中的
本地历史，并以仅含历史的列表替换缓存 `w.k`，保留搜索页状态 `w.S=1`。
空列表由原生 `AssembleTool#assembleFeedBlock(List)` 创建，避免依赖已被 R8 移除的
`qb.d` 构造器。输入联想和正常搜索结果不经过此 hook。实际 DEX 方法、字段核对及
空历史、有历史、取消请求、替换缓存和关闭开关测试通过，实机效果尚待验证。

## 当前设备应用安装器

2026-10-06 从同一 MEIZU 21 Pro 只读提取：
`com.android.packageinstaller`，版本 16 / versionCode 36，minSdk/targetSdk 36；
路径 `/system/priv-app/PackageInstaller/PackageInstaller.apk`；
SHA-256 `984b2c2ef55a2cd8b2af5e42e3248cab2a627c1fa299a67aab059beee83db1de`。

`FlymePackageInstallerActivity#startInstallScan()` 原本在进入首页时自动启动病毒扫描和商店查询。
开关开启时仅显示第一屏的继续按钮，不启动扫描/查询。点击继续（
`lambda$updateSecondaryButton$9(View)`）或 `doContinueInstall()` 直接调用原生
`doInstallFlyme()`，跳过备案/未知应用和风险二次确认。
保留原有来源安装权限、用户限制、未成年人验证与 APK 解析兼容性判断。

原 `InstallingAsyncTask` 不会在 `dismissOrDestroy()` 中取消，写入完成后仍提交
PackageInstaller 会话。模块在安装启动至 `onPostExecute(Session)` 返回之间
延后 `clearCachedApk()` 及源 APK 删除；提交后补做清理，避免立即关闭界面时
删除仍在读取的安装文件。双击继续仅启动一次，不改变开关关闭时的安装流程。

## 锁屏小组件启动显示

SystemUI 16260625 的 `layout_lock_screen_plugin.xml` 包含全部候选组件且未设置初始隐藏。
`com.flyme.keyguard.plugin.PluginLayout#onFinishInflate()` 及其父类先缓存组件引用；
`LockscreenPluginViewBinder` 随后异步收集 `LockscreenPluginViewModel.pluginList`，调用
`PluginLayout#updatePluginLayout(List)` 移除全部节点并重新添加选中组件。
“修复启动时锁屏小组件异常显示”在原生加载完成后先移除初始候选节点，保持对象引用，
由原生数据绑定恢复选中内容，不修改锁屏组件偏好，不设置长期隐藏或固定延迟。
功能位于桌面设置，实际作用域为 SystemUI；重新创建布局时同样生效。
已通过 7 项初始化及原生恢复状态检查、APK 构建，尚待设备启动/重启场景验证。

## 相机与图库参考实现

保留滤镜设置基于同版相机 `com.meizu.media.camera.filter.h` 的实际 DEX：
`F(int)` / `J(int)` 分别选择经典/自定义滤镜，字段 `b` 为当前滤镜、`c` 为临时恢复滤镜，
`G(List)` / `K(List)` 接收异步滤镜列表，`C(boolean)` 在预览开始时恢复并检查滤镜文件。
模块单独保存用户选择（含 `Mznone`），不把 `A(boolean, boolean)` 的生命周期或模式重置写回。
首次预览及异步列表到达后沿用 `C(true)` 恢复；暂停期间不触发异步恢复。
开关通过现有 LSPosed 相机远程偏好桥接，关闭时遵循原生逻辑。
恢复后调用 `MzUIController#setFilterBtnPressed(boolean)`，按管理器 `s()` 的实际状态
同步按钮图标及 `mIsFilterOn`；`MzCommonUI#inflateDelay()` 后再同步，覆盖控件延迟加载。
已通过真实 hook 类的 22 项状态分支检查与 APK 构建，尚未验证设备滤镜预览及成片。

2026-10-11 从已连接设备只读提取：

| 包 | 版本 | versionCode | 设备路径 | SHA-256 |
| --- | --- | --- | --- | --- |
| `com.meizu.media.camera` | 12.7.2 | 12007002 | `/system/app/Camera/Camera.apk` | `1ccc1f9176760433f5865f0cb3aeedca37a4ef93aa185b80c0caa191d86cd6de` |
| `com.meizu.media.gallery` | 12.7.3 | 1200070003 | `/system/priv-app/FlymeGallery/FlymeGallery.apk` | `00e727fb7ffbe24f03fd12eaa0a1e2b4c7fd77db59ee70593926dfa7e94652a6` |

旋转：`CameraActivity#getMzGalleryIntent(Uri)` 传递 `Rotation`。
`PhotoPagerFragment#onActivityCreated(Bundle)` 根据该值设置横屏或反向横屏；
`K9()` 在来自相机时延迟 500ms 强制 `SCREEN_ORIENTATION_SENSOR`。
仅将 `Rotation` 归零不能阻止后续强制旋转，因此增加图库作用域。
相机仍使用原生 `CAMERA_VIEW` / `ExternalPhotoActivity`，只将锁定旋转时的 `Rotation`
归零，不替换入口、不修改图片 URI、相册范围、SecureCamera 或缩略图过渡参数。
图库 hook 仅匹配 `ExternalPhotoActivity` 且 Intent 为 `CAMERA_VIEW` 的旋转请求：
开关开启且系统锁定旋转时，改为 `SCREEN_ORIENTATION_USER`，覆盖首次横屏请求及
500ms 后的 SENSOR 请求；普通图库页面、开关关闭和系统自动旋转开启时保留原逻辑。

相机因包可见性限制无法解析模块设置 Provider，偏好改由 libxposed 远程读取。
相机与图库共用旋转开关，模块应用启动时同步已有偏好，之后同步变更。
更新后需要打开一次模块设置页，并在 LSPosed 中勾选相机和图库作用域。

取消增色处理：AI 场景滤镜及手动色调曲线两个方案均未达到用户要求，功能、设置项
和对应 hook 已移除；不再改变取景、拍摄请求、AI 场景或厂商色彩处理。
源码与构建核对不能替代相机普通入口、锁屏安全入口及旋转锁定的实机验证。

## 深色应用管理

设备包 `com.meizu.flyme.sdkstage` 5.0.01 / 50000001，路径
`/system/priv-app/SDKStage/SDKStage.apk`，SHA-256
`216176cc90e59b6819a764cd3969b1b6e1255f59a1de020fe8fcad3d35ef08e0`。
系统设置 `FlymeDarkModeSettingsController#getResultPayload()` 通过
`com.meizu.flyme.sdkstage.nightmode.intent.NIGHTMODE_SETTINGS` 进入此包，
列表页为 `activity.AppManagerActivity`，因此 SDKStage 单独声明作用域。

`common.d#g(ApplicationInfo,boolean)` 默认过滤系统/更新过的系统应用、特定 privateFlags、
没有桌面入口的应用，随后调用 `common.a#d` 过滤已支持深色主题或游戏名单中的应用。
“深色应用管理支持选择特殊应用”开启时，仅为 Shell、DocumentsUI，以及具有桌面
入口的系统或魅族/Flyme 第一方应用绕过此过滤；后台服务仍走原逻辑。
Shell 的 `AppInfo#setTitle(CharSequence)` 显示为 LSPosed，保留真实包名及原生图标，
名称拼音排序仍由原方法生成，不改变其他页面或系统全局应用名称。

开关改变时仅清除 `common.d#c` 列表缓存，下次读列表时重新生成。
Context 字段原始名为 `b`、List 字段为 `c`；JADX 为避免名称冲突显示为
`f8500b/f8501c`，这些显示名不能用于反射。设备日志已确认先前使用显示名导致
`NoSuchFieldException`，已按关闭重命名的原始 DEX 名称及类型修正。
原生 `common.a#t/s/j` 及 `flyme_night_mode_black_list` 写入保持不变，
不重置用户逐应用的偏好。已核对 `e()`、`b(String)` 两个过滤/名称绑定调用者，
并对它们 deoptimize，避免内联跳过 hook。
新开关存于 LSPosed 远程偏好并参与一键启用，设置页位于“相机与杂项 / 系统杂项”。
构建和源码核对不能替代 SDKStage 作用域启用后的实际列表与开关效果验证。

## 单应用图标与名称编辑

以此前设备提取的 `FlymeLauncher.apk`（versionCode 13000000）为目标。
长按应用时，`PopupContainerWithArrow#populateAndShowRowsFlyme` 创建
`SystemShortcutContainer`；在其 `setShortcuts(LayoutInflater,List,boolean)` 中
追加同一 `item_system_shortcut` 原生布局，后续定位和间距仍由 Flyme 处理。
仅匹配 `ItemInfoWithIcon` 且 `itemType == 0` 的应用，排除文件夹、组件和深层快捷方式。

参考 ColorOS 桌面 `com.oplus.uxicon.ui.ui.UxEditPanelFragment`、
`UxChangeIconPanelFragment` 的预览、选择和保存流程，并移植
`UxIconPackLoader` / `com.oplus.uxicon.ui.util.d` 的目录读取顺序：
`theme_iconpack`、`icon_pack`，然后资源或 assets 中的 `appfilter.xml`。
补充 `drawable.xml` 中没有应用映射的可选图标。COUI/Oplus 框架控件以 Flyme
进程可用的 Android 控件替换；图标包选择改为全屏网格，增加小字名称和搜索。
图片按可见项异步加载，使用容量受限的位图缓存及图片任务队列。

编辑数据独立保存在桌面私有偏好 `flymemod_icon_edits`，键包含应用组件和用户。
`BubbleTextView#applyLabel(ItemInfo)` 和 `ItemInfoWithIcon#newIcon(Context,int)`
对模型副本应用名称或位图，再交回原生方法；不改写原始模型、缓存、favorites
或桌面行列数。保留原生折行、图标形状、按压效果和工作资料角标。
保存后通过 `LauncherModel#onAppIconChanged(String,UserHandle)` 刷新对应应用；
关闭开关恢复原生渲染，保留独立编辑记录，重新开启可继续使用。
提供恢复默认操作，图标包资源缺失时回退原生图标。

已核对原始 DEX 中 `ItemInfoWithIcon#clone()` 名称，未使用 JADX 展示的
`mo3999clone` 别名。目录与渲染副本的模拟检查共 36 项通过；设备未连接，
长按菜单、全屏选择和桌面实际刷新仍需实机验证。

### Flyme 系统主题图标选择

设备当前主题应用为 `com.meizu.customizecenter` 12.5.0（versionCode
122050000）。`ThemeIconPackLoader` 的已安装主题索引位于
`/data/customizecenter/icon_to_launcher`。当前设备的
`/data/customizecenter/preview_icon` 为空；主题接收器的预览逻辑也只复制
`appList` 中的图标，因此选择器读取 `/sdcard/Customize/Themes`、
`/system/customizecenter/theme/mtpks` 和 `/custom/meizu/theme/mtpks` 中
对应主题的完整 MTPK `icons` 模块，不发送主题应用或预览广播。
桌面具有已授予的 `MANAGE_EXTERNAL_STORAGE` 权限。

解码沿用主题应用 `bf.i`、`gf.a` 的格式，只将解码后的图标 ZIP 缓存在
桌面私有缓存目录；源文件大小或修改时间改变时重新生成缓存。
按 `FlymeThemeHelper#getRawPreviewIcon` 的规则处理包名 PNG、夜间版本及
`_fg`/`_bg` 自适应图标，使用 Flyme 三参数 `AdaptiveIconDrawable` 构造器
保持原生图标轮廓。名称显示包名，匹配被编辑应用的图标单独放在首行。
已用设备安装的 LifeOS100 主题验证解码和目录读取，得到 42 个包名图标；
目录去重、分层变体、搜索和路径检查通过，选择器实际界面仍需安装后验证。

主题没有匹配应用图标时，首行显示可保存的“合成图标”，以独立标识
`flyme-synth:<应用包名>` 持久化。读取应用原始图标，按所选主题的
`filter_config.xml`、`icon_mask.png`、`icon_background.png`、
`icon_border.png` 合成，调用 Flyme 原生位图遮罩和图层方法。
`IconFilter` 使用独立实例，不修改全局滤镜单例；无模板时沿用原生
系统遮罩与自适应图标轮廓。匹配优先、合成标识、首行筛选、搜索和
原始目录隔离检查通过；合成图标的实际显示仍需设备验证。

图标选择器增加“套用当前主题/图标包遮罩”，按桌面当前正在使用的样式
处理已选图标，复选状态与独立编辑数据一起保存，旧数据默认不套用。
网格、编辑面板和桌面渲染共用处理路径。主题使用原生
`makeFlymeStyleIcon(Resources,Bitmap,ApplicationInfo)` 滤镜/遮罩/叠层流程；
图标包使用独立 `AppIconPackItem` 实例的 `changeIcon(Context,Bitmap,int)`，
不调用自动匹配应用图标的 `getIconForPackage`。仅有遮罩或前景叠层而没有
背景图的图标包补充透明底层，以避免原生方法提前返回。
已合成图标保留第一次合成结果，并在其上再次套用当前样式。
原生模型副本与新遮罩标志的渲染回归检查共 23 项通过；设备实际显示待验证。

普通图标包也保留 `appfilter.xml` 中的组件映射，并将匹配当前启动组件的
图标单独置顶；没有精确组件匹配时使用同包名映射。解析支持
`ComponentInfo{包名/类名}`、相对活动类名、同一 drawable 的多个映射，
以及原生组件名转 drawable 名的查找规则。匹配区域按行数调整高度，
搜索时保留分区。图标包目录与匹配回归检查共 22 项通过。

## 应用导航栏强制沉浸

由原微信专用功能扩展为可选择多个应用，保留原开关存储键与启用状态，
首次初始化应用列表为微信。设置采用开关及下方应用列表入口；复用隐藏
应用的图标列表、添加页和移除菜单，候选仅列出当前用户已安装的非系统应用。
包名集合通过远程偏好同步到 SystemUI，显式空列表保持为空；列表变更
取消旧采样请求并恢复底衬，随后按新的前台应用匹配重新采样。
以下原始微信验证结论仍适用于采样管线，新增多应用交互尚需实机验证。

以 `com.android.systemui`（versionCode 16260625）的
`navigationbar.views.NavigationBarView#onAttachedToWindow/onDetachedFromWindow/onDraw`
和 `NavigationBarTransitions#applyDarkIntensity/reapplyDarkIntensity` 为依据。
仅注入 SystemUI，不增加微信作用域、不依赖微信私有类；通过前台任务包名
`com.tencent.mm` 覆盖主界面及小程序活动。系统 APK 已声明
`REAL_GET_TASKS`、`READ_FRAME_BUFFER` 权限。

设备 framework 的 `IWindowManager#captureDisplay(int,CaptureArgs,ScreenCaptureListener)`
支持裁剪区域，使用导航视图上方第二行的 1 像素高完整横条，计算 RGB
算术平均值。后台线程单次异步采样，显示期间约每 50ms 更新；纯色在导航
视图 `onDraw` 中绘制，并向上补齐 1 像素底衬。原生 `NavigationBarFrame`
窗口高度及其各旋转参数增加 1 像素，顶部内边距同步补偿；保留
`providedInsets`、按钮位置和触摸处理，并适配按钮明暗。关闭开关、
离开微信、进入锁屏或导航视图分离时恢复原生绘制；关闭开关停止轮询。
横屏侧边导航暂不处理。构建通过，微信及小程序实际效果仍需设备验证。

前台任务检查与截图请求采用不同工作线程，任务检查不会被未完成的截图
阻塞。两类请求均有 750ms 超时；超时移除底衬并恢复窗口尺寸和图标明暗。
离开微信、关闭开关、窗口分离后递增请求代次，迟到回调不得重新显示底衬，
也不得清除较新请求的等待状态。

## 修改小窗数量上限

使用 `small-window-meizu22-12.6/` 中从 MEIZU 22 / Flyme 12.6.0.0A
提取的 `service-flyme.jar` 与 `services.jar` 验证。`FlymeWindowControllerBase`
的 `moveUnderWindowTaskToBack` 调用 `needPositionTaskAtBottom`，将其它符合条件的
窗口任务通过 `positionWindowTaskAtBottom` 移入后台并恢复全屏。
Mini / FreeForm / Welt 控制器分别重写该判据；Mini 另有私有
`moveUnderTaskToBack` 清理路径。普通 `WindowProcessController` 的
`MAX_NUM_PERCEPTIBLE_FREEFORM` 影响进程可感知标志，不是小窗数量限制，未修改。

新设置位于“导航与手势 / 多任务切换”，默认关闭，开启后默认数量为 3，
范围 1–6。计数按当前显示区域内的窗口任务层级从上到下进行，预留新任务的
名额，最近任务保留到指定上限；桌面以下的任务不视为当前小窗。
另覆盖 Mini 的 `shouldToBack` 周期自动关闭判据，保留已收起的悬浮小窗。
`onTaskMoveToFront`、`postSwitchTasksByNewFrontTask` 和 `onTaskStartPausing`
恢复原生最小化处理，切换窗口时不会让旧窗口继续保持展开。
在 `FlymeFeatureFactory.isWindowContainerWillInvisible` 中，保留数量以内、
同一显示区域的不同小窗根任务不互相触发完全遮挡判定；同一应用内部的
子任务、全屏任务、显式延后关闭的任务仍沿用系统判据。
`TaskFragment.getVisibility` 会根据原始布局边界预填遮挡结果，
`FlymeWindowManagerInjectImpl` 在结果为 true 时跳过上述工厂，因此同时
在该注入入口修正 `Inject.ResultOne.arg1`，覆盖小窗 Surface 缩放后仍保留
全屏布局边界的情况；反优化 `TaskFragment.getVisibility` 避免旧内联结果。
在 Mini / FreeForm 的 `onTaskMoveToFront`、窗口模式变化后的
`updateWindowModeInfo`，以及不改变窗口模式的两个展开入口中，遍历同一
显示区域的其它展开窗口（模式 11 / 1035），调用原生
`moveTasksToOtherWindowInner(task, 12, 0, true, true)` 收起为 Pin 窗口。
先保存任务快照并用重入保护避免模式切换递归；直接调用而非逐个排队，
因为原生排队函数会删除上一条待处理消息。保留已悬浮和贴边的窗口，
继续使用原生缩小动画及 Pin 输入控件；返回全屏应用不触发该遍历。
只调整自动移入后台的判据和私有清理路径，不拦截主动关闭、返回全屏或
其它显式窗口操作。ThreadLocal 清理上下文在正常返回与异常时均恢复；
禁用开关恢复原生判据。相关调用方法反优化以避免旧编译结果内联。

仅在 `onSystemServerStarting` 中安装，现代 Xposed API 的系统框架作用域声明为
`system`；`android` 是独立系统包，不用于 system_server 注入。
首次加载需要勾选系统框架并重启系统；通过远程偏好同步开关与数量，后续
调整无需重启。未将 system_server 加入“重启作用域”的进程终止列表。
实际 hook 的 41 项宿主模拟检查通过（数量、混合模式、显示区域、禁用、
主动操作、桌面边界、异常恢复、周期关闭、自动最小化、并存可见性、焦点切换、
预填遮挡结果绕过工厂的路径）。设备日志确认
system_server 成功注册原版 hook；本次恢复展开窗口交接处理并核对对应
入口签名，构建通过，修改后的实际窗口交互仍需验证。

悬浮未展开窗口的输入由 `FlymePinWindowControllerImpl.onInputEvent` 处理，
原生 `FlymeWindowManagerInjectImpl.injectInputMonitorAcceptWindow` 只将共享
`pip_window_input_consumer` 的触摸范围裁剪到第一个（最上层）Pin 任务 Surface。
`SmallWindowInputHooks` 将该范围改为当前可见、非隐藏、子类型为 0 的 Pin
窗口区域并集，不包含窗口之间的空隙；移除单 Surface 裁剪，并按输入 Surface
坐标换算区域。DOWN 时按窗口层级命中具体任务并置顶，再调用原生手势处理，
保留拖动、顶部关闭、贴边、点击展开的系统行为；MOVE 不重新选择窗口。
单窗口、禁用或没有原生输入消费者的场景沿用原生范围。
实际输入 hook 的 16 项宿主模拟检查通过，覆盖区域并集、坐标换算、空隙
透传、重叠窗口顺序、手势目标选择及禁用恢复。设备日志显示首版输入 hook
因 `getWindowSubtype` 误用 Task 参数而注册失败，现已按真实声明改为
WindowContainer 并同步修正模拟签名；修正版交互尚未实机验收。

## 横屏游戏窗口宽度与小窗比例

`GameWidthHooks` 使用当前 Meizu 22 / Flyme 12.6 框架的
`ActivityRecord.resolveOverrideConfiguration` 修改所选应用的横屏全屏视口，
同时处理原生 size-compat、挖孔 Insets 和 letterbox 内边界。
居中模式在主应用 WindowState 内容 Surface 上设置 8dp 圆角；
19.5:9 拉伸通过 ActivityRecord Surface 的横向矩阵铺满屏幕，不裁切圆角。
非所选应用、竖屏和动画 leash 场景不覆盖系统布局或动画矩阵。

小窗使用独立的 `FlymeFloatWindowInfo.calculateAspectRatio`。
`GameWidthSmallWindowHooks` 在 Mini、FreeForm、Pin、Welt 的
`createWindowInfo(Task,int,int,int)` 中限定当前应用和横屏方向，
将同一适配方式的比例送入原生构造过程；默认模式以物理长边扣除
两侧挖孔宽度后除以短边，19.5:9 拉伸在小窗中采用 19.5:9。
自由小窗可能直接复制上一窗口而不重新计算比例，因此仅在比例不匹配时
调用原生 MiniWindowInfo 构造器，保留中心、缩放和窗口状态。
不直接修改 final 比例字段。上下文使用 ThreadLocal 并在异常路径恢复。

实际全屏 hook 的 29 项宿主检查、小窗 hook 的 14 项宿主检查通过；
小窗检查覆盖竖屏屏幕中的横屏窗口、适配方式、其它应用、自由小窗
复制旧比例及异常后的上下文恢复。构建与签名验证通过，设备离线，
实际窗口显示和切换仍需验证。两个应用选择器仅列出非系统应用。

横屏游戏启动时，固定旋转变换的 DisplayInfo 可能先于实际屏幕成为横屏。
适配判据先检查 DisplayContent 当前显示区域，仍处于竖屏时保持原生旋转
与窗口配置；实际横屏后才使用目标比例，避免在竖屏中提前生成横屏窗口。
宿主检查覆盖该过渡与完成旋转后的适配；实际设备显示仍需验证。

## 桌面隐藏应用入口

MEIZU 22 的桌面 APK 中 `PopupContainerWithArrow.populateAndShowRowsFlyme`
及 `SystemShortcutContainer.setShortcuts(LayoutInflater,List,boolean)` 构建应用
长按菜单。新增隐藏按钮复用 `item_system_shortcut`，使用 Flyme 控件资源
`mc_ic_password_invisible`（APK 中包含该 PNG），随原生标签颜色染色。
设置写入通过 SettingsProvider 的专用 call 完成，仅允许模块与当前用户
桌面 UID 调用，并检查“允许隐藏应用”开关；保存到现有隐藏应用集合后
通知原有桌面观察器刷新模型。不开启该开关时不添加菜单项。

双指张开使用 `WorkspacePinchController$MultiTouchObjectCanvasClass`
的 `setPositionAndScale(Object,PositionAndScale,PointInfo)`，遵循系统原有
log(1.25) 缩放判据，保留原生触摸拦截资格检查；限制为两个手指、
NORMAL 状态、没有展开文件夹且桌面内容未处于动画中。
不修改 FolderPinchController 或编辑状态的手势。每次触摸序列最多打开
一次模块隐藏应用页，MainActivity 支持明确的页面入口与复用实例。

隐藏应用主列表采用每页 4 行×3 列，点击打开、长按查看应用信息或取消
隐藏；新增应用候选页和其它功能复用的选择器保持原列表布局。
构建通过，设备未连接，菜单、手势与分页交互尚待实机验证。

随后设备日志确认手势首版注册失败：`StatefulContainer<STATE_TYPE extends
BaseState<STATE_TYPE>>.isInState(STATE_TYPE)` 在运行时参数为 BaseState，
而非 LauncherState；现按该签名查找。文件夹和内容动画状态方法以
MzLauncher 为声明入口，避免在 Launcher 基类上查找不存在的方法。
隐藏列表去除分组副标题，分页区域限制高度并在内容区垂直居中。
