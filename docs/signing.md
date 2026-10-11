# APK 签名

Debug 与 Release 均使用显式配置的固定密钥。构建类型决定调试和优化行为，不决定密钥是否持久保存。缺少签名配置时构建失败，不回退到自动生成的 debug 密钥。

## 本机构建

维护者密钥位于 `signing/module.keystore`，配置位于项目根目录 `signing.properties`，两者均被 Git 忽略。复制 `signing.properties.example` 并填写自己的密钥信息即可构建；相对 `storeFile` 路径以配置文件所在目录为基准。也可用 `ANDROID_SIGNING_PROPERTIES` 指向项目外配置文件。

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
python3 scripts/verify-apk-signature.py app/build/outputs/apk/debug/*.apk
```

第三方自行构建可以使用自己的密钥，但无法用自己的证书覆盖安装维护者签名的 APK。验证脚本检查本仓库公布的维护者证书，自己的证书不会通过该身份校验。

## GitHub Actions

本仓库的 `ANDROID_SIGNING_BUNDLE` Actions secret 保存 JSON：

```json
{
  "keystore": "密钥库文件的 Base64 内容",
  "storePassword": "密钥库密码",
  "keyAlias": "密钥别名",
  "keyPassword": "私钥密码"
}
```

每个项目独立保存自己的签名材料。工作流在临时目录恢复密钥，同时构建 Debug、Release APK，校验两个包的证书与 `signing/certificate.sha256` 一致，通过后才提交版本、打 tag 和发布；主仓库及 Xposed 模块仓库的每次 release 均上传 `app-debug.apk` 和 `app-release.apk`。完成或失败后清理临时材料。不要用 Actions cache 保存私钥，不要重新生成密钥，也不要将密钥或密码提交到仓库。

密钥和配置需另行安全备份；Actions secret 无法读取回原值。`signing/certificate.sha256` 只包含可公开的证书指纹。

## 历史版本

此前 Actions 使用新 runner 的自动 debug 密钥，各次发布证书不同。APK 中只有公开证书，不能从旧 APK 恢复私钥。与新固定证书不同的旧版本不能直接覆盖升级；卸载会删除应用数据，应先保留需要的模块设置。后续同一固定签名的版本可正常覆盖升级。
