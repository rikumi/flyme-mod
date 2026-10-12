# 首页设备头像 Banner

布局参考 `reference-apks/theme-meizu22/com.android.settings/base.apk`：账号行的头像与文字垂直居中，图文间距 12dp，两行间距 2dp，标题 17sp，副标题 14sp。独立行的基础垂直留白 16dp，ImmersiveList 上下各追加 8dp。

按用户后续要求，模块 Banner 的图文间距与左侧边距共用 `FLUIX_ROW_HPADDING`（20dp），下内边距为 12dp，上内边距为 24dp，标题字重为 Medium。行高由内容决定，避免最小行高补回已减少的下内边距。

用户指定素材：[Flyme GitHub 头像](https://avatars.githubusercontent.com/u/13059131?s=200&v=4)。`home_device_flyme_logo.xml` 组合气球主体与高光两个独立矢量层，依据图片上半部分重绘：蓝色 #167DF2、白色弧形高光；不包含下方 Flyme 文字、原图背景或额外底板。形状使用平滑曲线重绘，不是逐像素裁剪。矢量视口为 100×108，显示宽高 40×43.2dp，FIT_CENTER 保持比例，不裁切。

读取既有 `rememberModuleThemeSettings().accent`，自定义主题色启用时只给主体层设置 tint，高光层保持白色；关闭后清除 tint 恢复原蓝色。使用现有偏好监听响应颜色和开关变化，不新增开关，不依赖设置首页图标染色开关，也不使用运行时着色器。

水平边距采用普通设置项共用的 `FLUIX_ROW_HPADDING`（20dp）。图标视口左右紧贴气球轮廓，左缘与设置项内容起点一致；RTL 使用相对方向。无气球资源时使用无背景的灰色气球矢量回退。

资源仅用于模块首页 Banner，未修改设置行图标或应用图标。设备名（Settings.Global.DEVICE_NAME / Build.MODEL）与系统版本（Build.DISPLAY）读取和更新逻辑保持原样；文字占剩余宽度，单行末尾省略；行高允许随字号增长。

验证：参考图颜色与轮廓核对、矢量结构与比例核对、布局约束检查、JDK 21 增量构建及固定证书验签通过。MEIZU 21 Pro 与 MEIZU 22 已安装并检查首页截图，现有自定义主题色下气球主体与主题色一致、高光保持白色。长名称、RTL、大字体、缺失资源及关闭主题色后的设备画面未强制测试；不修改设备设置来制造这些状态。
