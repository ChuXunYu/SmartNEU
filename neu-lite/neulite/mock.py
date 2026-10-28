"""演示数据：--demo 时无需账号即可查看界面。"""
from __future__ import annotations

import datetime as dt


def overview() -> dict:
    today = dt.date.today()
    monday = today - dt.timedelta(days=(today.weekday() + 1) % 7)  # 周日为一周第一天
    days = []
    for i in range(7):
        d = monday + dt.timedelta(days=i)
        items = []
        if i in (1, 2, 3, 4, 5):
            items.append({"title": "高等数学 A", "start": f"{d}T08:00:00", "end": f"{d}T09:40:00",
                          "location": "何世礼教学馆 201", "desc": "1-16周", "all_day": False})
            if i in (2, 4):
                items.append({"title": "大学物理实验", "start": f"{d}T14:00:00", "end": f"{d}T16:00:00",
                              "location": "实验楼 B302", "desc": "单周", "all_day": False})
        days.append({"date": d.isoformat(), "weekday": i, "items": items})
    return {
        "demo": True,
        "profile": {"username": "20230001", "name": "示例同学", "xgh": "20230001",
                    "role": "本科生", "role_type": "2002", "depart": "计算机科学与工程学院"},
        "session": {"logged_in": True, "imei": "demo-imei", "checked": True},
        "schedule": {"start": monday.isoformat(), "end": (monday + dt.timedelta(days=6)).isoformat(),
                     "week": 9, "current_week": 9,
                     "days": days, "events": sum((d["items"] for d in days), [])},
        "exams": {"status": "ok", "note": "演示数据",
                  "items": [
                      {"course": "高等数学 A", "time": "2026-11-05 09:00-11:00", "place": "何世礼 201", "seat": "A-023"},
                      {"course": "大学物理", "time": "2026-11-08 14:00-16:00", "place": "逸夫楼 105", "seat": "B-114"},
                  ]},
        "grades": {"status": "ok", "note": "演示数据", "rank": {"gpa": 3.72, "rank": "12/186", "major_rank": "8/57"},
                   "items": [
                       {"course": "高等数学 A", "score": "92", "credit": "6.0", "gpa": "4.2"},
                       {"course": "大学物理", "score": "88", "credit": "5.0", "gpa": "3.8"},
                       {"course": "程序设计基础", "score": "95", "credit": "3.0", "gpa": "4.5"},
                   ]},
        "net": {"status": "ok", "note": "演示数据",
                "cards": [
                    {"name": "网费余额", "value": "23.50", "unit": "元"},
                    {"name": "本月已用流量", "value": "12.3", "unit": "GB"},
                ],
                "usage_gb": 12.3, "usage_text": "12.300G", "fee": 2.3,
                "policy": "波谷免费/阶段1：下行0～10G，免费/阶段2：下行10～25G，1G/元",
                "duration": "120时", "product": "学生账户", "via": "mock",
                "links": []},
        "card_balance": "23.50", "card_unit": "元", "mail_unread": "2",
    }


def card_info() -> dict:
    import time
    now = int(time.time() * 1000)
    return {
        "status": "ok", "name": "示例同学", "student_id": "20230001",
        "balance": "23.50", "unit": "元", "department": "计算机科学与工程学院",
        "qr": {"code": "NEU000000000000000000", "created_at": now, "expires_at": now + 10000},
        "valid_until": "2028-07-01",
        "gate": {"name": "默认允许进校", "color": "#006633", "result": "4"},
        "updated_at": now,
    }


def mail_info() -> dict:
    return {
        "status": "ok", "unread": 2, "web_url": "", "self": "demo@mails.neu.edu.cn",
        "items": [
            {"id": "demo1", "from": "教务处", "subject": "关于期中考试安排的通知",
             "date": "2026-10-27 09:00:00", "size": "12000", "unread": True, "attached": True},
            {"id": "demo2", "from": "图书馆", "subject": "图书借阅到期提醒",
             "date": "2026-10-26 15:30:00", "size": "8000", "unread": True, "attached": False},
            {"id": "demo3", "from": "软件学院", "subject": "本周年级大会",
             "date": "2026-10-25 10:00:00", "size": "6000", "unread": False, "attached": False},
        ],
    }


def mail_detail(mid: str) -> dict:
    return {"status": "ok", "id": mid, "subject": "关于期中考试安排的通知",
            "from": "教务处", "to": "20230001@stu.neu.edu.cn",
            "date": "2026-10-27 09:00:00",
            "text": "各位同学：\n\n本学期期中考试安排已发布，请登录教务系统查看考场与座位号。\n\n教务处",
            "attachments": ["考试安排.pdf"], "web_url": ""}
