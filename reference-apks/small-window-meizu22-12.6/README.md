# 小窗参考文件

设备：MEIZU 22；系统：Flyme 12.6.0.0A。2026-10-11 经 adb 只读提取。

| 文件 | SHA-256 | DEX 数量 |
| --- | --- | --- |
| `com.android.systemui.apk` | `069e1977149da5d1d311b0100876ddea108e4558a803b3438bf84ba029fad856` | 5 |
| `com.flyme.systemuitools.apk` | `b7d039922e3f02527f375868eea411811aa9037e79e2c4ef7c7c85a08f6760f1` | 3 |
| `com.meizu.flyme.launcher.apk` | `f222140e8809537138b0e911dc921c0ba6ba28749375ad857f8bb1b1e99786f2` | 17 |
| `ext.jar` | `bc1e9384928039c28a8ea76cb070683518d51368c856f265bf310cdda736f8b2` | 1 |
| `framework-flyme.jar` | `6fd512cffe9b338a61d865f1453a241c1a0056c18713dcf9b4f5ed3b0e87f5d6` | 1 |
| `framework.jar` | `6fba2480a58d1dab161b818e511ef0e55c3379325303de7fd82df7e3f1b177a8` | 6 |
| `service-flyme.jar` | `6bdd20d92ea56ca299a0226627c10471f8f321a66be1bf1a2fa6a195a6407c22` | 1 |
| `services.jar` | `a8996d2036ed2d1f8ee6e36e71c5753bcec77f93ad60bcadf4d9fd241241d670` | 3 |

SystemUITools：13.1.2（13001002）；SystemUI：16.26.06.25_base（16260625）；桌面：13.0.0（13000000）。

来源：`/system/framework/{services,ext,framework}.jar`、`/apex/com.flyme.runtime/javalib/{framework-flyme,service-flyme}.jar`、`/system/priv-app/SystemUITools/SystemUITools.apk`、`/system_ext/priv-app/{SystemUI/SystemUI,FlymeLauncher/FlymeLauncher}.apk`。
