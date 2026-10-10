# 安装器

包名：`dev.rikumi.installer`。独立 APK，不依赖 Flyme Mod 或 LSPosed。

没有桌面入口，使用 Android 默认应用图标。从文件管理器打开 APK 时选择“安装器”，或通过分享菜单选择“安装器”。首次使用请求 Shizuku 授权；需先安装并启动 Shizuku。

APK 通过文件描述符传给 Shizuku UserService，以 shell/root 身份调用系统 `pm install`。只有系统返回成功后才显示安装完成。支持单 APK 安装及覆盖更新，不支持 APKM/APKS 分包归档。

构建：`./gradlew :installer:assembleDebug`。
