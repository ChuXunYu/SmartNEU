# 开发指南

## 环境要求

| 工具 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 11+ | 编译 Android Java 代码 |
| Android SDK Build-Tools | 36.0.0 | aapt2 / d8 / zipalign / apksigner |
| Android Platform | android-36 | `android.jar` |
| PowerShell | 7+ | 构建与发布脚本 |
| 7-Zip | 任意近期版本 | 将 classes.dex 写入 APK |
| Python | 3.11+ | 桌面端 |
| Git | 任意近期版本 | 版本控制 |

## 首次准备

```powershell
git clone https://git.aizhangz.shop/aizhangz/SmartNEU.git
cd SmartNEU
```

### 配置 Android SDK 路径

`neu-lite-android/build.ps1` 顶部：

```powershell
$sdk = 'D:\Scoop\apps\android-clt\current'
```

改成自己的 SDK 路径。需要存在：

```text
<SDK>/build-tools/36.0.0/
<SDK>/platforms/android-36/android.jar
```

### 桌面端依赖

```powershell
cd neu-lite
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## 日常开发

### 只改 Web UI

前端源文件在：

```text
neu-lite/web/
```

Android 打包使用的是副本：

```text
neu-lite-android/assets/
```

修改后必须同步：

```powershell
Copy-Item neu-lite\web\app.js,neu-lite\web\index.html,neu-lite\web\style.css neu-lite-android\assets -Force
```

`qrcode.js` 是第三方库，通常不需要改；若升级，两个目录都要更新。

### 本地预览 Web UI

```powershell
cd neu-lite
python run.py serve --demo --open
```

演示模式不访问学校接口，适合调样式和交互。

### 调试 Android

```powershell
cd neu-lite-android
pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
adb install -r 日在NEU.apk
```

查看日志：

```powershell
adb logcat -s RunBinder AndroidRuntime chromium
```

WebView 内容调试：

- 在 `MainActivity` 中临时启用 `WebView.setWebContentsDebuggingEnabled(true)`；
- 使用 Chrome `chrome://inspect` 连接；
- 提交前必须移除调试开关。

### 构建产物

```text
neu-lite-android/build/           # 中间产物，已忽略
neu-lite-android/日在NEU.apk      # 最终 APK，已忽略
```

不要提交 APK、`.idsig`、keystore 或构建缓存。

## 代码规范

### Java

- 4 空格缩进；
- 类名 PascalCase，方法/变量 camelCase；
- 新增 Bridge action 时同步更新 `docs/ARCHITECTURE.md` 的 action 表；
- 捕获异常后给用户可读信息，不把堆栈直接返回给 WebView。

### JavaScript / CSS

- 2 空格缩进；
- 不引入打包器；
- 新增 API 调用统一走 `api()` 包装；
- 不把密码、token 写入 `localStorage`。

### Python

- 4 空格缩进；
- 优先标准库；
- 新增依赖必须写入 `requirements.txt` 并更新 `THIRD_PARTY_NOTICES.md`；
- 网络请求统一走 `ApiSession`，不要直接 `requests.get` 绕过信封与 Cookie。

### 提交信息

使用简短中文或英文祈使句，建议前缀：

```text
feat:     新功能
fix:      修复
docs:     文档
build:    构建/依赖
chore:    清理
release:  版本发布
```

## 测试

### 桌面端自检

```powershell
cd neu-lite
python run.py selftest
```

覆盖 RSA 信封、考试/成绩归一化、状态文件读取。

### Android 协议自测

`neu-lite-android/test` 提供桌面 JVM 自测，需要真实账号环境变量：

```powershell
$env:STUDENT_ID = "..."
$env:STUDENT_PASSWORD = "..."
```

测试代码位于 `test/com/neulite/app/TestMain.java`，会验证登录、课表、考试、成绩与 CSV 导出。

> 不要把真实账号写入脚本、提交记录或 Issue。

### 手工回归清单

- 登录 / 短信验证 / 退出登录
- 课表周切换与导出
- 考试近期/过往
- 成绩与排名
- 网费与充值入口
- 校园卡二维码刷新
- 邮箱列表、正文、发信、附件
- 校园乐跑首次绑定与再次打开
- 底部 Tab 开关
- 清理网页资源缓存

## 发布流程

1. 修改 `neu-lite-android/AndroidManifest.xml`：

   ```xml
   android:versionCode="23"
   android:versionName="0.4.2"
   ```

2. 在 `CHANGELOG.md` 增加同版本小节；
3. 提交并确认工作区干净；
4. 执行：

   ```powershell
   pwsh .\scripts\release.ps1
   ```

发布脚本需要 `FORGEJO_TOKEN`，或已保存的 Git 凭据。

## 相关文档

- [架构说明](ARCHITECTURE.md)
- [故障排查](TROUBLESHOOTING.md)
- [隐私说明](PRIVACY.md)
