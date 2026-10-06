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
