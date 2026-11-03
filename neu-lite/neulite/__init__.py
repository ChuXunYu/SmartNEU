"""neu-lite：智慧东大轻量客户端。

模块划分：
  crypto    RSA 信封（与 App 一致）
  session   HTTP 会话 + 统一信封解析
  auth      登录 / 短信验证 / 会话校验 / 登出
  portal    门户 API（课表、数据卡片、应用列表）
  jwxt      新教务系统（jwxt.neu.edu.cn，金智 jwapp）
  netfee    网费 / 校园网流量
  server    本地 Web 服务 + 静态页面

版本号与 Android APK 保持一致，见仓库根目录 CHANGELOG.md。
"""

__version__ = "0.4.5"
