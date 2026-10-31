# 贡献指南

感谢关注 日在NEU。这是一个个人维护的校园工具，欢迎 Issue 和 Pull Request。

## 开始之前

- 先阅读 [README](README.md)、[架构说明](docs/ARCHITECTURE.md) 和 [开发指南](docs/DEVELOPMENT.md)；
- 搜索现有 Issue，避免重复；
- 不要在 Issue、PR、截图或日志中公开学号、密码、TGT、Cookie、SMTP 专用密码、乐跑 token 等敏感信息。

## 报告问题

使用仓库的 Bug 模板，附：

- App 版本 / 提交号；
- 机型与 Android 版本；
- 复现步骤；
- 期望结果与实际结果；
- 必要的错误提示或日志片段（先脱敏）。

接口失效类问题请说明发生时间、页面和错误提示，不要附完整请求凭据。

## 提交 Pull Request

1. Fork 仓库，从 `main` 创建分支；
2. 保持改动聚焦，不要混入无关格式化或重排；
3. 修改 Web UI 时同步两个目录：

   ```powershell
   Copy-Item neu-lite\web\app.js,neu-lite\web\index.html,neu-lite\web\style.css neu-lite-android\assets -Force
   ```

4. 修改 Java 后至少构建一次：

   ```powershell
   cd neu-lite-android
   pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
   ```

5. 修改 Python 后运行自检：

   ```powershell
   cd neu-lite
   python run.py selftest
   ```

6. 不要提交 APK、exe、`state.json`、keystore、token、构建缓存或个人截图；
7. PR 描述写清楚动机、改动范围、验证方式和风险。

## 代码规范

| 语言 | 缩进 | 说明 |
| --- | --- | --- |
| Java | 4 空格 | 保持现有命名与异常处理风格 |
| JavaScript / CSS | 2 空格 | 不引入打包器 |
| Python | 4 空格 | 优先标准库 |
| Markdown | 2 空格 | 中文与英文之间保留空格 |

提交信息建议使用：

```text
feat: 新功能
fix: 修复
docs: 文档
build: 构建/依赖
chore: 清理
release: 版本发布
```

## 安全与合规

- 不提交任何真实账号、密码、Cookie、token、keystore；
- 不添加代跑、伪造轨迹、绕过风控、窃取他人凭据的功能；
- 不把学校接口的私有凭据硬编码进仓库；
- 发现安全问题请按 [SECURITY.md](SECURITY.md) 私下报告。

## 发布

维护者发布前：

1. 更新 `neu-lite-android/AndroidManifest.xml` 的 `versionName` / `versionCode`；
2. 在 `CHANGELOG.md` 增加同版本小节；
3. 提交后保证工作区干净；
4. 执行：

   ```powershell
   pwsh .\scripts\release.ps1
   ```

脚本会构建 APK、创建标签、推送并上传 Release 资产。

## 许可

提交代码即表示同意以 [MIT License](LICENSE) 授权本项目。
