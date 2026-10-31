# 架构说明

本文描述 日在NEU 的整体结构、模块边界和关键数据流，帮助新贡献者快速定位代码。

## 总览

项目由两个可独立运行的部分组成：

```text
┌──────────────────────────────┐
│  neu-lite/                   │
│  Python 本地 HTTP 服务        │
│  + Web UI（桌面浏览器）       │
└──────────────┬───────────────┘
               │ 同一套 web/
┌──────────────▼───────────────┐
│  neu-lite-android/           │
│  Android 原生壳（Java）       │
│  + WebView 加载 assets/      │
└──────────────────────────────┘
```

两端共享同一套前端页面（`neu-lite/web` 与 `neu-lite-android/assets` 内容必须一致），但后端实现不同：

- 桌面端：Python 直接请求学校接口，页面通过 `fetch("/api/...")` 访问本地服务。
- Android 端：Java 实现同一套接口，页面通过 `window.NEU.call()` 走原生桥。

前端通过运行时判断使用哪条通道：

```js
const native = !!window.NEU;
```

## Android 端

### 进程与组件

```text
MainActivity           单 Activity，承载 WebView
├─ Bridge              JS ↔ Java 的同步/异步桥
├─ Api                 学校接口、会话、WebVPN、教务、邮件
├─ RunBinder           隐藏 WebView 自动绑定校园乐跑
├─ RunUrl              乐跑 token/sign 续签
├─ SecretStore         Android Keystore AES-GCM
├─ CryptoBox           导出文件加解密
├─ PaymentActivity     校园卡/网费支付 WebView
└─ SmtpClient          原生 SMTP 发信
```

### 启动流程

1. `MainActivity.onCreate()` 创建 `Api`，加载本地状态。
2. 创建 `WebView`，加载 `file:///android_asset/index.html`。
3. 页面初始化后调用 `session` 获取登录态。
4. 已登录则渲染缓存并后台刷新；未登录则显示登录页。

### JS ↔ Java 桥

`Bridge.java` 暴露一个统一入口：

```js
window.NEU.call(action, jsonString)   // 同步
window.NEU.callAsync(id, action, jsonString) // 异步
window.NEU.poll(id)                   // 轮询异步结果
```

常用 action：

| action | 说明 |
| --- | --- |
| `session` | 登录态与用户信息 |
| `login` / `quickLogin` / `sms` / `logout` | 登录相关 |
| `overview` | 首页聚合数据 |
| `schedule` | 课表 |
| `card` / `cardQr` | 校园卡与二维码 |
| `mail` / `mailDetail` / `sendMail` | 邮箱 |
| `net` / `ipgw` | 网费与流量 |
| `jwxt` | 教务状态 |
| `autoBindRun` / `runBindStatus` | 校园乐跑绑定 |
| `openWeb` / `openPay` | 打开外部页面 |

新增 action 时必须同时处理：

- 参数校验
- 异常转换（不要把 Java 堆栈直接抛给页面）
- 敏感字段脱敏

### 会话与 WebVPN

- 登录使用学校门户的加密信封协议，实现见 `Api.java` 与 `neu-lite/neulite/crypto.py`。
- 会话失效时 `Api` 会尝试单飞重登，避免并发刷新互踢 TGT。
- 校外访问时，部分服务走 WebVPN：
  - 先 `webvpnLogin()`
  - 再用 `webvpnToken(host)` 拼接 `https://webvpn.neu.edu.cn/https/<token>/...`
- 校园乐跑绑定优先直连，失败后自动切 WebVPN，详见 `RunBinder.java`。

### 校园乐跑

```text
点击「打开校园乐跑」
  → 有已保存链接：RunUrl.refresh() 续签 nonce/timestamp/sign → 打开
  → 无链接：RunBinder 直连门户 → 应用中心 → 乐跑 H5 → 保存 token/sign
  → 直连失败：自动 WebVPN 重试
```

`RunBinder` 使用一个不可见的 WebView 完成跳转，不模拟跑步行为。

### 存储

| 数据 | 位置 | 保护 |
| --- | --- | --- |
| 登录态 / Cookie | `SharedPreferences("neu")` | 应用私有 |
| SMTP 专用密码 | `SharedPreferences("neu-lite-secure")` | Android Keystore AES-GCM |
| 乐跑链接 | 同上 | Android Keystore AES-GCM |
| 导出 CSV | 应用外部文件目录 | 明文，用户自行保管 |
| WebView 缓存 | 系统 WebView 目录 | 超过阈值自动清理 |

## 桌面端

### 模块

```text
neu-lite/neulite/
├─ crypto.py     RSA 信封（与 App 一致）
├─ session.py    requests 会话、Cookie、统一信封解析
├─ auth.py       登录 / 短信 / 校验 / 登出
├─ portal.py     门户 API（课表、数据卡片、应用列表）
├─ jwxt.py       新教务系统（jwxt.neu.edu.cn）
├─ netfee.py     网费与流量
├─ mail.py       邮箱
├─ ecode.py      e码通
├─ pay.py        缴费
├─ ipgw.py       校园网网关
├─ records.py    考试/成绩归一化
├─ exports.py    CSV 导出
├─ mock.py       演示数据
├─ state.py      本地状态
└─ server.py     本地 HTTP 服务与静态文件
```

### 请求链路

```text
浏览器
  → http://127.0.0.1:8765/api/...
  → server.py
  → Auth / Portal / Jwxt / NetFee / Mail
  → session.py（加密信封）
  → 学校接口
```

### 本地状态

`state.json` 保存 ticket、Cookie、设备标识和展示用账号信息：

- 不保存校园密码明文；
- 文件权限在支持的平台上设为 `0600`；
- 已在 `.gitignore` 中排除。

## 前端

```text
neu-lite/web/
├─ index.html   单页结构、弹层、Tab
├─ app.js       状态管理、API 调用、渲染
├─ style.css    宣纸/墨/朱红视觉
└─ qrcode.js    二维码生成（第三方）
```

约定：

- 不引入构建工具，直接使用原生 ES 模块外的普通脚本。
- 所有网络请求都通过 `api()` 包装，Android/桌面端自动分流。
- 缓存优先：先渲染本地缓存，再后台刷新。
- 不把凭据写入 `localStorage`。

## 版本与发布

- 版本号唯一来源：`neu-lite-android/AndroidManifest.xml`。
- 每次发布同步更新 `CHANGELOG.md`。
- 发布由 `scripts/release.ps1` 完成：构建 → 打标签 → 推送 → 创建 Release → 上传 APK。

## 相关文档

- [开发指南](DEVELOPMENT.md)
- [故障排查](TROUBLESHOOTING.md)
- [隐私说明](PRIVACY.md)
- [贡献指南](../CONTRIBUTING.md)
