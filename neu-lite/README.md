# 日在NEU · Python / Web

本目录是项目的 Python 桌面端与 Web UI，供本地运行、调试和页面开发使用。Android 端复用同一套 `web/` 页面。

> 非官方个人项目。仅用于访问自己的校园账号数据，请遵守学校网络与信息系统使用规则。

## 环境

- Python 3.11+
- 依赖见 [`requirements.txt`](requirements.txt)

```powershell
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## 运行

```powershell
# 演示数据，不需要登录
python run.py serve --demo --open

# 登录学校账号（提示输入学号/密码，必要时输入短信验证码）
python run.py login

# 启动正式服务
python run.py serve --open
```

默认监听 `127.0.0.1:8765`，只绑定本机回环地址。

## CLI

| 命令 | 说明 |
| --- | --- |
| `serve` | 启动本地 Web |
| `login` | 登录（支持短信二次验证） |
| `check` | 校验当前会话 |
| `logout` | 退出登录 |
| `export <path>` | 导出可移植状态 |
| `import <path>` | 导入状态 |
| `probe` | 探测教务端点与数据 |
| `selftest` | 离线自检（RSA 信封、记录归一化、状态读取） |

## 功能

- 课表、考试、成绩、网费、校园卡、邮箱；
- 本地缓存优先渲染；
- 考试/课表 CSV 导出；
- 与 Android 端共用的 Web UI。

## 目录

```text
neu-lite/
├─ neulite/        # 校园服务客户端与本地服务端
│  ├─ crypto.py    # RSA 信封
│  ├─ session.py   # requests 会话与 Cookie
│  ├─ auth.py      # 登录 / 短信 / 登出
│  ├─ portal.py    # 门户 API
│  ├─ jwxt.py      # 新教务系统
│  ├─ netfee.py    # 网费
│  ├─ mail.py      # 邮箱
│  ├─ records.py   # 考试/成绩归一化
│  ├─ exports.py   # CSV
│  ├─ mock.py      # 演示数据
│  └─ server.py    # 本地 HTTP 服务
├─ web/            # 页面、样式、二维码库
├─ docs/           # 截图
├─ run.py          # CLI 入口
└─ requirements.txt
```

## 数据与安全

- 登录状态只保存在本机 `state.json`；
- `state.json` 已在 `.gitignore` 中排除，不要提交或外传；
- 桌面端不保存校园密码明文；
- 课表导出为明文 CSV；
- 不要把自己的凭据、Cookie、token 贴到 Issue 或截图中。

详细说明见仓库根目录的 [SECURITY.md](../SECURITY.md) 与 [隐私说明](../docs/PRIVACY.md)。

## 许可

代码以 [MIT License](../LICENSE) 开源；图标和第三方组件说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
