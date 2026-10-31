# 日在NEU · Android

原生 Android 壳 + WebView UI，界面采用宣纸、墨、朱红印章与宋体风格。

## 安装

```bash
adb install -r 日在NEU.apk
```

或从 [Releases](https://git.aizhangz.shop/aizhangz/SmartNEU/releases) 下载 APK 后传到手机点击安装（允许“安装未知来源应用”）。

- 包名：`com.neulite.app`
- 版本：`0.3.3`（versionCode 13）
- minSdk 24 / targetSdk 34
- 签名：自签名 `CN=neu-lite, C=CN`；升级需使用同一签名

## 功能

- 课表、考试、成绩、网费、校园卡、邮箱；
- 校园卡 e码通二维码，10 秒刷新；
- 邮箱未读/已读分组、正文缓存、写信和附件；
- 可选的校园乐跑 H5 Tab；
- 底部 Tab 可在「更多 → 界面显示」中单独开关；
- 首页缓存优先，导出文件默认加密。

## 构建

无需 Gradle / Android Studio，直接使用 Android SDK build-tools：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

依赖：

- JDK 11+
- Android SDK：`build-tools`、`platforms/android-36`
- PowerShell 7
- 7-Zip

产物：

```text
日在NEU.apk
```

本地 APK 不提交到源码仓库；发布时使用：

```powershell
pwsh ..\scripts
elease.ps1
```

`build.ps1` 内的 SDK 路径需要按本机环境调整。

## 权限

应用会按需申请：

- 定位：校园乐跑 H5 / 校园服务；
- 相机：校园乐跑人脸相关页面；
- 文件选择：网页上传附件；
- 网络与通知：基础运行。

权限只在对应页面触发时申请，不申请后台定位和麦克风。

## 数据与安全

- 登录凭据只保存在应用私有目录，`allowBackup=false`；
- SMTP 专用密码、校园乐跑链接使用 Android Keystore 加密；
- 导出的状态备份与 CSV 默认密码加密；
- 仓库不包含账号密码、Cookie 或访问令牌；
- 不要把自己的凭据或二维码截图发到 Issue。

## 限制

- 校园乐跑是官方 H5 入口，不模拟轨迹；
- 原生 SMTP 不写入服务器「已发送」；
- 附件最多 3 个、合计 12 MB；
- 第三方页面升级后可能需要适配。

## 许可

代码以仓库根目录 [MIT License](../LICENSE) 开源；图标和第三方组件见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
