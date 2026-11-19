# 日在NEU

> 东北大学校园信息聚合客户端：课表、考试、成绩、网费、校园卡、邮箱与校园乐跑入口。

<p align="center">
  <img src="icon.jpg" width="140" alt="日在NEU 图标">
</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-3ddc84.svg">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.11%2B-3776ab.svg">
  <img alt="Version" src="https://img.shields.io/badge/version-0.4.10-c0392b.svg">
</p>

**日在NEU** 是一个非官方、个人维护的东北大学校园信息客户端。它把常用的校园服务收敛到一个界面里：课表、考试、成绩、网费、校园卡、邮箱，以及可选的校园乐跑入口。Android 端是原生壳 + WebView，桌面端是 Python 本地服务 + 同一套 Web UI。

> 本项目与东北大学官方无关。仅用于访问**自己的**校园账号数据。请遵守学校网络与信息系统使用规则，不要用于代跑、伪造轨迹、绕过风控或获取他人数据。

## 功能

| 模块 | 说明 |
| --- | --- |
| 课表 | 周视图二维网格、左右滑动切换周、回到今天、导出明文 CSV |
| 考试 | 按日期合并、近期提醒、过往折叠、导出 CSV |
| 成绩 | 成绩、绩点、排名；不显示分制标签 |
| 网费 | 余额、流量、阶梯计费说明、充值入口 |
| 校园卡 | 余额、e码通二维码、自动刷新、充值入口 |
| 邮箱 | 未读/已读、正文缓存、WebView 写信、原生 SMTP 发信与附件 |
| 校园乐跑 | 可选 Tab，打开官方 H5；门户自动绑定、token/sign 自动续签 |
| 界面 | 底部 Tab 可单独开关；缓存优先渲染，无空白闪烁 |

### 设计取向

- **本地优先**：先渲染缓存，再后台刷新。
- **有界存储**：单一缓存键覆盖；导出每类只保留最新一份；WebView 缓存超过阈值自动清理。
- **凭据最小化**：不保存校园密码明文；SMTP 专用密码和乐跑链接使用 Android Keystore。
- **不越界**：不模拟跑步轨迹，不绕过平台风控，不调用未公开的写接口。

## 界面

| 课表 | 校园乐跑 |
| --- | --- |
| ![课表](neu-lite/docs/screenshot-schedule.png) | ![校园乐跑](neu-lite/docs/screenshot-run-tab.png) |

| 邮箱 | 网费 |
| --- | --- |
| ![邮箱](neu-lite/docs/screenshot-mail.png) | ![网费](neu-lite/docs/screenshot-net.png) |

## 下载与安装

- 最新 APK：[Releases](https://git.aizhangz.shop/aizhangz/SmartNEU/releases)
- 镜像仓库：<https://github.com/ChuXunYu/SmartNEU>
- 包名：`com.neulite.app`
- 最低系统：Android 7.0（API 24）
- 目标 SDK：34

```bash
adb install -r 日在NEU.apk
```

从文件管理器安装时需允许“安装未知来源应用”。升级必须使用同一签名；当前为项目自签名证书。

## 快速开始（桌面端）

```powershell
cd neu-lite
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# 演示数据，无需登录
python run.py serve --demo --open

# 正式使用：先登录，再启动本地 Web
python run.py login
python run.py serve --open
```

默认地址：<http://127.0.0.1:8765>

## 构建 Android APK

构建不依赖 Gradle / Android Studio，直接调用 Android SDK build-tools。

### 依赖

- JDK 11 或更高
- Android SDK：`build-tools;36.0.0`、`platforms;android-36`
- PowerShell 7（`pwsh`）
- 7-Zip

### 构建

```powershell
cd neu-lite-android
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

产物：

```text
neu-lite-android/日在NEU.apk
```

`build.ps1` 顶部的 `$sdk` 是作者本机路径。构建前请改成自己的 Android SDK 目录，或设置 `ANDROID_HOME` 后自行调整脚本读取方式。

APK 不进入源码仓库，只作为 Release 资产分发。

## 发布

发布脚本会构建 APK、创建 `v<version>` 标签、推送 `main` 与标签、在 Forgejo 创建 Release 并上传 APK 和 SHA-256。

```powershell
pwsh .\scripts\release.ps1
```

发布前必须：

1. 更新 `neu-lite-android/AndroidManifest.xml` 的 `versionName` / `versionCode`；
2. 在 `CHANGELOG.md` 增加同版本小节；
3. 提交后保证工作区干净。

需要环境变量 `FORGEJO_TOKEN`，或事先让 Git Credential Manager 保存 Forgejo 凭据。

## 目录结构

```text
SmartNEU/
├─ neu-lite/                    # Python 桌面端 + Web UI
│  ├─ neulite/                  # 校园服务客户端与本地 HTTP 服务
│  ├─ web/                      # 前端页面（含 diff.js，与 Android assets 保持同步）
│  ├─ docs/                     # 截图
│  ├─ run.py                    # CLI 入口
│  └─ requirements.txt
├─ neu-lite-android/            # Android 原生壳 + WebView UI
│  ├─ src/com/neulite/app/      # Java：Bridge、Api、SMTP、Keystore、乐跑绑定
│  ├─ assets/                   # 打包进 APK 的 Web UI
│  ├─ res/                      # 图标、主题、网络安全配置
│  ├─ test/                     # 桌面协议自测
│  ├─ build.ps1                 # 无 Gradle 的 APK 构建脚本
│  └─ README.md
├─ scripts/release.ps1          # 发布脚本
├─ docs/                        # 架构、开发、故障排查文档
├─ .github/ .gitea/             # Issue / PR 模板
├─ icon.jpg                     # 应用图标源图
├─ CHANGELOG.md
├─ CONTRIBUTING.md
├─ SECURITY.md
├─ CODE_OF_CONDUCT.md
├─ THIRD_PARTY_NOTICES.md
└─ LICENSE
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 整体架构、模块边界、数据流 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 本地开发、构建、调试、Web UI 同步 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 登录、WebVPN、乐跑、构建常见问题 |
| [docs/PRIVACY.md](docs/PRIVACY.md) | 数据存储位置、保留周期与清除方式 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献流程与代码规范 |
| [SECURITY.md](SECURITY.md) | 漏洞报告与凭据处理 |
| [CHANGELOG.md](CHANGELOG.md) | 版本记录 |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | 第三方组件与素材授权 |

## 数据与安全

- 登录状态、Cookie、乐跑链接只保存在本机应用私有目录。
- Android `allowBackup=false`，不参与系统云备份。
- SMTP 专用密码、校园乐跑链接使用 Android Keystore AES-GCM 加密。
- 课表导出为**明文 CSV**，请自行妥善保管。
- 仓库不包含任何账号、密码、TGT、Cookie 或 token。
- 详细说明见 [SECURITY.md](SECURITY.md) 与 [docs/PRIVACY.md](docs/PRIVACY.md)。

## 已知限制

- 校园乐跑是官方 H5 入口，不模拟轨迹、不代跑；需要先通过学校门户绑定一次。
- 原生 SMTP 发信不会写入服务器「已发送」文件夹。
- 邮箱附件最多 3 个、合计 12 MB。
- 桌面端出于安全考虑不保存校园密码，`quick_login` 会要求重新输入。
- 第三方页面或接口升级后可能需要适配；本项目不保证长期可用。

## 参与贡献

欢迎 Issue 和 Pull Request。提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，不要在 Issue 中粘贴任何凭据、Cookie、token 或未打码截图。

## 许可

代码以 [MIT License](LICENSE) 开源。

- 应用图标 `icon.jpg` 不包含在 MIT 许可内，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；
- 第三方组件授权见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 说明

项目名来自《School Days》中文译名《日在校园》的改写，仅作私人使用，不代表学校官方立场。
