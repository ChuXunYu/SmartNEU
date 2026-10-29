# 贡献指南

感谢你关注 日在NEU。这个项目是个人维护的校园工具，欢迎提交 Issue 和 Pull Request。

## 提交 Issue 前

- 先搜索现有 Issue，避免重复；
- 不要贴出学号、密码、TGT、Cookie、SMTP 专用密码、校园乐跑 token 等敏感信息；
- 界面问题请附截图，并说明机型、Android 版本和 App 版本；
- 接口失效请说明发生时间、页面和错误提示，不要包含完整请求凭据。

## Pull Request

1. Fork 仓库并从 `main` 建分支；
2. 保持改动聚焦，不要混入无关格式化；
3. 修改 Web UI 时同步更新 `neu-lite/web/` 与 `neu-lite-android/assets/`；
4. 修改 Java 代码后至少执行一次 APK 构建：

   ```powershell
   cd neu-lite-android
   pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
   ```

5. 不要在提交中包含 APK、exe、`state.json`、keystore、token 或个人截图；
6. PR 描述写清楚动机、改动和验证方式。

## 发布

发布前：

1. 更新 `neu-lite-android/AndroidManifest.xml` 的 `versionName` / `versionCode`；
2. 在 `CHANGELOG.md` 增加同版本小节；
3. 提交后工作区必须干净。

然后执行：

```powershell
pwsh .\scriptselease.ps1
```

脚本会：

- 构建 APK；
- 创建 `v<version>` 标签；
- 推送 `main` 和标签；
- 在 Forgejo 创建 Release；
- 上传 `日在NEU-v<version>.apk` 和 SHA-256。

APK 不提交到源码仓库，只作为 Release 资产分发。

## 代码风格

- Java：4 空格缩进，保持现有命名；
- JavaScript / CSS：2 空格缩进；
- Python：4 空格缩进，优先标准库；
- 中文文案保持简洁，避免夸张和误导。

## 安全

发现凭据泄露、鉴权绕过、任意文件写入等问题，请不要公开 Issue，按 [SECURITY.md](SECURITY.md) 的方式联系维护者。

## 许可

提交代码即表示同意以 [MIT License](LICENSE) 授权本项目。
