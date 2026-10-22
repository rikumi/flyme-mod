# 系统字体替换

入口为“系统字体”，三个选项独立保存，不参与一键启用：

| 类别 | 默认值 | 选项 |
| --- | --- | --- |
| 中文字体 | 默认 | 默认、思源黑体、自定义 |
| 西文字体 | 默认 | 默认、Inter、Manrope、自定义 |
| 等宽字体 | Maple Mono | Maple Mono、JetBrains Mono、Fira Code、Cascadia Code、自定义 |

与 coloros-mod 共用字体解析、匹配及内置资源。自定义支持 TTC、TTF、OTF，每项最多 100 MiB；再次选择“自定义”可重新导入。取消和解析失败保留原选择。等宽字体按 cmap/hmtx 检查 ASCII 字符的实际 advance，TTC 中只选等宽成员。字重、斜体、可变字重范围和 TTC 地区匹配均保留。

字体导入模块的设备加密存储，随后通过 libxposed service 发布不可变文件及专用远程配置；不依赖原始文档 URI。保存需要服务连接成功。首次安装后打开模块完成同步，再由用户重启设备或 Zygote，并重启已有应用。

## Flyme 框架依据

依据 `reference-apks/theme-meizu22/framework.jar`（本地 MEIZU 22 归档）核对：

- `SystemFonts.createFontFamily(List, String, int, int, boolean, Map)`：替换中文无衬线回退族，保留语言、variant 和默认回退属性。
- `SystemFonts.buildSystemFallback(FontConfig, ArrayMap)`：仅替换无衬线、等宽族首项，保留后续回退链。
- `FontFamily.Builder.build(String, int, boolean, boolean, int)`：构建带原始元数据的族。
- `Typeface.defaultFromStyle`、`Paint.setTypefaceWithoutWarning` 经过 `FlymeFontsHelper` 处理主题字体。选择中文或西文字体时跳过主题字体整体覆盖；仅使用默认中文、西文时保留主题字体，但保护等宽族及派生字形，避免其被主题比例字体替换。

字体映射序列化依赖可重新打开的文件路径，故在系统的 `/data/fonts/files/flyme-mod` 缓存按内容哈希命名的字体。沿用父目录字体标签和应用可读权限；只在父目录的 `File.listFiles()` 结果中排除此模块目录，以免原生字体更新机制将它当作无效更新。不会修改系统分区或替换原生字体文件。

## 原生字体路径

与 coloros-mod 一致，通过 LSPosed Native Hook 在已注入进程中重定向字体配置及虚拟字体文件。保留 emoji、衬线及其他语言回退。推荐作用域包含 Chrome、WebView；使用 WebView 的其他宿主需自行加入作用域。

独立隔离渲染进程是否得到注入取决于 LSPosed；网页下载字体、应用自带字体及直接系统调用不保证覆盖。主进程初始化成功不能证明网页字体已替换。没有采用挂载方案。

## 验证和限制

- JDK 21：`python3 scripts/check-system-fonts.py`，160 项字体元数据、TTC、XML 和原生路径检查。
- JDK 21：`:app:assembleDebug`，四种 ABI 原生库，固定证书签名检查及 `git diff --check`。
- 当前仅连接 OPPO PKT110，没有连接 Flyme 设备，因此没有安装或重启设备。Flyme 上的实际显示、Direct Boot、缓存权限、主题字体交互及独立网页渲染仍待实机验证。
