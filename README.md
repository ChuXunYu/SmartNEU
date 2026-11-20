# 日在NEU

> 课表、考试、成绩、网费、校园卡、邮箱和乐跑入口，装进一个界面。

<p align="center">
  <img src="icon.jpg" width="140" alt="日在NEU 图标">
</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-3ddc84.svg">
  <img alt="Python" src="https://img.shields.io/badge/Python-3.11%2B-3776ab.svg">
  <a href="https://github.com/ChuXunYu/SmartNEU/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/ChuXunYu/SmartNEU?color=c0392b"></a>
</p>

**日在NEU** 是一个非官方、个人维护的校园信息客户端，适配「XX大学」的教务系统、统一身份认证与校园服务。

Android 端是原生壳 + WebView，桌面端是 Python 本地服务 + 同一套 Web UI，两端共用页面、样式与交互逻辑，改一处两边同时生效。

它解决的问题很具体：教务、网费、邮箱、校园卡分散在四五个站点，手机上每次都要重新登录、等加载。这里把它们收进一个界面，并尽量做到打开就能看到东西。

> 本项目与学校官方无关，仅用于访问**自己的**校园账号数据。请遵守所在学校的网络与信息系统使用规则，不要用于代跑、伪造轨迹、绕过风控或获取他人数据。

## 最近更新

### v0.4.12

- 仓库转为公开，README 重写；
- 文档与界面文案不再出现学校全称。

### v0.4.9 – v0.4.11

- **刷新差异卡片**：已有数据时点「刷新数据」，若课表或成绩有变，弹卡片说明新增 / 删除 / 修改的课程，给出平均绩点与专业排名前后对比，并列出新出的课程成绩；首次获取不弹。
- **过期自动更新**：上次更新超过 3 天，打开软件先显示缓存，再后台自动刷新一次。
- **GPA 原始值**：平均绩点直接显示教务返回值，不再四舍五入，按长度自适应字号。
- **开机回到今天**：课表默认落在今天所在教学周，日视图默认选中今天。
- **旧版 WebView 兼容**：修复 Android 11 等 Chromium 87 以下环境下「更多」「邮箱」等弹层不显示的问题。
- **暗色模式**：周课表课程名改深色，右上角邮箱按钮改深色底。

完整记录见 [CHANGELOG.md](CHANGELOG.md)。

## 功能

| 模块 | 能力 |
| --- | --- |
| 课表 | 周视图二维网格与日视图；左右滑动切周；回到今天；同格多课纵向堆叠；导出明文 CSV |
| 考试 | 按日期合并同一场考试；本周与倒计时；「待考」默认展开、「已结束」默认收起；导出 CSV |
| 成绩 | 全部课程成绩、绩点、专业排名；GPA 使用教务原始值；按学期与类型标注 |
| 网费 | 余额、流量、阶梯计费说明；充值入口 |
| 校园卡 | 余额、e码通二维码、自动刷新；充值入口 |
| 邮箱 | 未读 / 已读、正文缓存、WebView 写信；原生 SMTP 发信与附件 |
| 校园乐跑 | 可选 Tab，打开官方 H5；门户自动绑定，token / sign 自动续签 |
| 数据刷新 | 缓存优先渲染；手动刷新有变化时给出差异卡片；超过 3 天自动更新 |
| 界面 | 底部 Tab 可单独开关；浅色 / 暗色跟随系统；单缓存键，有界存储 |

### 设计取向

- **本地优先**：先渲染缓存再刷新，启动不阻塞、不闪白屏。
- **有界存储**：单一缓存键覆盖总览；邮箱正文缓存有上限；导出每类只保留最新一份；WebView 缓存超阈值自动清理。
- **凭据最小化**：不保存校园密码明文；SMTP 专用密码与乐跑链接使用 Android Keystore 加密。
- **不越界**：不模拟跑步轨迹，不绕过平台风控，不调用未公开的写接口。

## 界面

| 课表 | 校园乐跑 |
| --- | --- |
| ![课表](neu-lite/docs/screenshot-schedule.png) | ![校园乐跑](neu-lite/docs/screenshot-run-tab.png) |

| 邮箱 | 网费 |
| --- | --- |
| ![邮箱](neu-lite/docs/screenshot-mail.png) | ![网费](neu-lite/docs/screenshot-net.png) |

## 下载与安装

- 最新 APK：[GitHub Releases](https://github.com/ChuXunYu/SmartNEU/releases/latest)
- 主仓库：<https://github.com/ChuXunYu/SmartNEU>
- 镜像仓库：<https://git.aizhangz.shop/aizhangz/SmartNEU>

当前版本：

```text
包名      com.neulite.app
版本      0.4.12（versionCode 33）
最低系统  Android 7.0（API 24）
目标 SDK  34
体积      约 145 KB
```

APK 只包含 Java 与 Web 资源，没有原生库，所有 CPU 架构通用。Release 资产命名为 `RizaiNEU-v<版本>.apk`。

```bash
adb install -r RizaiNEU-v0.4.12.apk
```

从文件管理器安装需允许「安装未知来源应用」。升级必须使用同一签名，当前为项目自签名证书，换签名需先卸载。

## 桌面端

Python 本地服务 + 浏览器，适合在电脑上查看或导出。

```powershell
cd neu-lite
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt

# 演示数据，无需登录
python run.py serve --demo --open

# 正式使用：先登录一次，再启动本地 Web
python run.py login
python run.py serve --open
```

默认地址 <http://127.0.0.1:8765>，服务只监听本机。需要 Python 3.11+。

## 构建 Android APK

不依赖 Gradle 与 Android Studio，直接用 Android SDK build-tools：`aapt2` 编译资源 → `javac` → `d8` → 打包 `classes.dex` → `zipalign` → `apksigner`。

依赖：

- JDK 11 或更高
- Android SDK：`build-tools;36.0.0`、`platforms;android-36`
- PowerShell 7（`pwsh`）
- 7-Zip

```powershell
cd neu-lite-android
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

产物：

```text
neu-lite-android/日在NEU.apk
```

脚本优先读取 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，否则回退到脚本内写死的作者本机路径，构建前按需修改。

## 测试

```powershell
# 课表周次边界（Python）
cd neu-lite
python -m unittest discover -s tests -v

# 刷新差异计算与卡片渲染（Node，无需依赖）
node tests/diff.test.js
```

## 发布

APK 不进入源码仓库，只作为 Release 资产分发。发布前：

1. 更新 `neu-lite-android/AndroidManifest.xml` 的 `versionName` / `versionCode`；
2. 在 `CHANGELOG.md` 增加同版本小节；
3. 提交并推送，保证工作区干净。

GitHub Releases（主渠道）：

```powershell
cd neu-lite-android
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
cd ..
git tag v0.4.12
git push github main --tags
Copy-Item 'neu-lite-android\日在NEU.apk' 'RizaiNEU-v0.4.12.apk'
gh release create v0.4.12 'RizaiNEU-v0.4.12.apk' `
  --repo ChuXunYu/SmartNEU `
  --title "日在NEU v0.4.12" `
  --notes-file CHANGELOG.md
```

Forgejo 镜像走 `scripts/release.ps1`，需要环境变量 `FORGEJO_TOKEN`。

## 目录结构

```text
SmartNEU/
├─ neu-lite/                    # Python 桌面端 + Web UI
│  ├─ neulite/                  # 校园服务客户端与本地 HTTP 服务
│  ├─ web/                      # 页面 / 样式 / diff.js（与 Android assets 保持同步）
│  ├─ tests/                    # Python 回归测试与 diff.test.js
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
├─ scripts/release.ps1          # Forgejo 发布脚本
├─ docs/                        # 架构、开发、故障排查、隐私
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

- 登录状态、Cookie、乐跑链接只保存在本机应用私有目录；
- Android `allowBackup=false`，不参与系统云备份；
- SMTP 专用密码与乐跑链接使用 Android Keystore AES-GCM 加密；
- 课表、考试导出为**明文 CSV**，请自行保管；
- 仓库不包含任何账号、密码、TGT、Cookie 或 token。

详见 [SECURITY.md](SECURITY.md) 与 [docs/PRIVACY.md](docs/PRIVACY.md)。

## 已知限制

- 校园乐跑是官方 H5 入口，不模拟轨迹、不代跑，需要先通过学校门户绑定一次；
- 原生 SMTP 发信不会写入服务器「已发送」文件夹；
- 邮箱附件最多 3 个、合计 12 MB；
- 桌面端不保存校园密码，`quick_login` 会要求重新输入；
- 接口来自公开页面与抓包整理，学校系统升级后可能需要适配，不保证长期可用；
- 未在 Android 7 / 8 老机型逐台实机验证，遇到问题欢迎提 Issue。

## 参与贡献

欢迎 Issue 和 Pull Request。提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，不要在 Issue 中粘贴任何凭据、Cookie、token 或未打码截图。

## 许可

代码以 [MIT License](LICENSE) 开源。

- 应用图标 `icon.jpg` 不包含在 MIT 许可内，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；
- 第三方组件授权见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 说明

项目名来自《School Days》中文译名《日在校园》的改写，仅作私人使用，不代表学校官方立场。
本项目为个人开发的非官方客户端，与学校及任何校园服务提供方无从属关系。
