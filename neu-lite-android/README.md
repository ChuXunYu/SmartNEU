# 日在NEU · Android

原生 Android 壳 + WebView UI，界面采用宣纸、墨、朱红印章与宋体风格。

## 安装

```bash
adb install -r 日在NEU.apk
```

或从 [Releases](https://git.aizhangz.shop/aizhangz/SmartNEU/releases) 下载 APK 后传到手机安装（需允许“安装未知来源应用”）。

| 项 | 值 |
| --- | --- |
| 包名 | `com.neulite.app` |
| 版本 | `0.4.1`（versionCode 22） |
| 最低系统 | Android 7.0（API 24） |
| 目标 SDK | 34 |
| 签名 | 自签名 `CN=neu-lite, C=CN` |

升级必须使用同一签名。

## 功能

- 课表、考试、成绩、网费、校园卡、邮箱；
- 校园卡 e码通二维码，自动刷新；
- 邮箱未读/已读分组、正文缓存、写信和附件；
- 可选的校园乐跑 H5 Tab；
- 底部 Tab 可在「更多 → 界面显示」中单独开关；
- 缓存优先渲染；导出文件每类只保留最新一份。

## 构建

无需 Gradle / Android Studio，直接使用 Android SDK build-tools：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

依赖：

- JDK 11+
- Android SDK：`build-tools;36.0.0`、`platforms;android-36`
- PowerShell 7
- 7-Zip

产物：

```text
日在NEU.apk
```

`build.ps1` 顶部的 `$sdk` 需要改成自己的 Android SDK 路径。

APK 不提交到源码仓库，发布时使用：

```powershell
pwsh ..\scripts\release.ps1
```

## 权限

按需申请：

| 权限 | 用途 |
| --- | --- |
| 网络 / 网络状态 | 基础运行 |
| 定位 | 校园乐跑 H5 / 校园服务 |
| 相机 | 乐跑人脸相关页面 |
| 文件选择 | 网页上传附件 |
| 通知 | 后台任务提示 |

不申请后台定位、麦克风、通讯录、短信读取。

## 数据与安全

- 登录凭据只保存在应用私有目录，`allowBackup=false`；
- SMTP 专用密码、校园乐跑链接使用 Android Keystore AES-GCM；
- 课表导出为明文 CSV，请自行保管；
- 仓库不包含账号密码、Cookie 或访问令牌；
- 不要把自己的凭据或二维码截图发到 Issue。

## 代码结构

```text
src/com/neulite/app/
├─ MainActivity.java    单 Activity + WebView
├─ Bridge.java          JS ↔ Java 桥
├─ Api.java             学校接口与会话
├─ RunBinder.java       乐跑自动绑定
├─ RunUrl.java          乐跑 token/sign 续签
├─ SecretStore.java     Keystore 加密存储
├─ CryptoBox.java       导出文件加解密
├─ PaymentActivity.java 支付 WebView
└─ SmtpClient.java      原生 SMTP
```

详细说明见 [架构文档](../docs/ARCHITECTURE.md)。

## 限制

- 校园乐跑是官方 H5 入口，不模拟轨迹；
- 原生 SMTP 不写入服务器「已发送」；
- 附件最多 3 个、合计 12 MB；
- 第三方页面升级后可能需要适配。

## 许可

代码以仓库根目录 [MIT License](../LICENSE) 开源；图标和第三方组件见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
