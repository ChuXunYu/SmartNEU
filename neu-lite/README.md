# 日在NEU · Python / Web

本目录是项目的 Python 桌面端与 Web UI，供本地运行、调试和页面开发使用。Android 端复用同一套 `web/` 页面和接口实现思路。

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

# 登录学校账号（会提示输入学号/密码，必要时输入短信验证码）
python run.py login

# 启动正式服务
python run.py serve --open
```

默认监听 `127.0.0.1:8765`。

## 功能

- 课表、考试、成绩、网费、校园卡、邮箱；
- 本地缓存优先渲染；
- 状态备份与 CSV 导出；
- 与 Android 端共用的 Web UI。

## 目录

```text
neu-lite/
├─ neulite/        # 校园服务客户端与本地服务端
├─ web/            # 页面、样式、二维码库
├─ docs/           # 截图
├─ run.py          # 命令行入口
└─ requirements.txt
```

## 数据与安全

- 登录状态只保存在本机；
- `state.json` 已在 `.gitignore` 中排除，不要提交或外传；
- 导出的状态备份默认加密；
- 不要把自己的凭据、Cookie、token 贴到 Issue 或截图中。

详细安全说明见仓库根目录的 [SECURITY.md](../SECURITY.md)。

## 许可

代码以 [MIT License](../LICENSE) 开源；图标和第三方组件说明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
