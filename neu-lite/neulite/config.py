"""端点与常量。所有路径都来自 APK 逆向结果（见 ../../REPORT.md）。"""
from __future__ import annotations

import os
import sys

BASE = "https://personal.neu.edu.cn"
PORTAL = BASE + "/portal"

# app_login
COMMIT = PORTAL + "/manage/common/app_login/commit"
CHECK = PORTAL + "/manage/common/app_login/check"
SMS_SEND = PORTAL + "/manage/common/app_login/smsSend"
SMS_VERIFY = PORTAL + "/manage/common/app_login/verifyCommit"
LOGOUT = PORTAL + "/manage/common/app_login/logout"

# 门户数据
GET_LAYOUT = PORTAL + "/appsetting/frontend/default/getLayout"
APPS_LIST = PORTAL + "/plugins/frontend/default/appsList"
APP_MARKET = PORTAL + "/appmarket/frontend/homepage/appsList"
APP_DETAIL = PORTAL + "/appmarket/frontend/default/appGetApp"
ITEMS_APP = PORTAL + "/personal/frontend/data/items_app"
DETAIL_APP = PORTAL + "/personal/frontend/data/detail_app"
INFO_APP = PORTAL + "/personal/frontend/data/info_app"
APP_WEEK = PORTAL + "/schedule/frontend/default/appWeek"
APP_CALENDAR = PORTAL + "/schedule/frontend/calendar/appCalendar"
APP_DAYLIST = PORTAL + "/schedule/frontend/default/appdaylist"

# 新教务（金智 jwapp）
JWXT_BASE = os.environ.get("NEU_JWXT_BASE", "https://jwxt.neu.edu.cn")
JWXT_HOME = JWXT_BASE + "/jwapp/sys/homeapp/index.do"
JWXT_MENU = JWXT_BASE + "/jwapp/sys/homeapp/api/home/getMenuList.do"

# 统一身份认证
PASS = "https://pass.neu.edu.cn"
CAS_LOGIN = PASS + "/tpass/login"

# 可一键免登打开的校内系统（CAS service）
SSO_SERVICES = {
    "jwxt": JWXT_HOME,
    "ehall": "https://ehall.neu.edu.cn/db_portal",
    "pay": "https://pay.neu.edu.cn/",
}
ALLOWED_SSO_SUFFIX = ".neu.edu.cn"

UA = "ZhilinEai ZhilinNeuApp/3.1.8 25102RKBEC Android 16"

COMMON_HEADERS = {
    "User-Agent": UA,
    "Accept-Language": "zh-Hans-CN;q=1, en-CN;q=0.9",
    "Content-Type": "application/x-www-form-urlencoded",
    "from-eai": "1",
    "x-requested-with": "XMLHttpRequest",
}

# 网费的候选关键字（用于从门户数据卡片/应用列表里识别）
NET_KEYWORDS = ("网费", "流量", "上网", "校园网", "网络余额", "网络")
EXAM_KEYWORDS = ("考试", "考务")
GRADE_KEYWORDS = ("成绩", "排名", "绩点")

FROZEN = bool(getattr(sys, "frozen", False))
APP_DIR = (os.path.dirname(sys.executable) if FROZEN
           else os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WEB_DIR = os.path.join(getattr(sys, "_MEIPASS", APP_DIR), "web")
STATE_FILE = os.environ.get("NEU_STATE", os.path.join(APP_DIR, "state.json"))
