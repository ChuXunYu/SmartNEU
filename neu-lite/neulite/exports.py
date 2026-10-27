"""把考试、课表导出为 Excel 友好的 CSV（UTF-8 BOM）。"""
from __future__ import annotations

import csv
import io
import json

DAY_NAMES = {7: "周日", 1: "周一", 2: "周二", 3: "周三", 4: "周四", 5: "周五", 6: "周六"}
DAY_ORDER = {7: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 6: 6}  # 周日在前


def _yn(v) -> str:
    s = str(v or "")
    return {"1": "是", "0": "否"}.get(s, s)


def exams_csv(rows: list[dict], term_name: str = "") -> bytes:
    header = ["学期", "考试名称", "考试类型", "课程代码", "课程名称", "教学班", "任课教师",
              "考试日期", "开始时间", "结束时间", "考场", "座位号", "考试周次",
              "学院", "专业", "班级", "年级", "是否缓考", "考试状态",
              "考试时间描述", "备注", "原始数据"]
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(header)
    for r in rows:
        raw = r.get("raw") or {}
        w.writerow([
            raw.get("XNXQDM", r.get("term", "")),
            raw.get("KSDM_DISPLAY", ""),
            raw.get("KSLXDM_DISPLAY", r.get("type", "")),
            raw.get("KCH", ""),
            raw.get("KCM", r.get("course", "")),
            raw.get("JXBID", ""),
            raw.get("SKJS", ""),
            raw.get("KSRQ", ""),
            raw.get("KSSJ", ""),
            raw.get("JSSJ", ""),
            raw.get("JASMC", r.get("place", "")),
            raw.get("ZWH", r.get("seat", "")),
            raw.get("ZC", ""),
            raw.get("YXDM_DISPLAY", ""),
            raw.get("ZYDM_DISPLAY", ""),
            raw.get("BJDM_DISPLAY", ""),
            raw.get("XZNJ_DISPLAY", ""),
            _yn(raw.get("SFHK", "")),
            raw.get("KSZT", ""),
            raw.get("KSSJMS", r.get("desc", "")),
            raw.get("KSSM") or raw.get("PKSM") or "",
            json.dumps(raw, ensure_ascii=False, separators=(",", ":")),
        ])
    return ("\ufeff" + buf.getvalue()).encode("utf-8")


def timetable_csv(courses: list[dict], term_name: str = "") -> bytes:
    header = ["学期", "课程名称", "课程代码", "教学班", "教学班名称", "教师", "星期", "节次",
              "开始时间", "结束时间", "上课地点", "校区", "周次", "学分",
              "排课类型", "原始周次", "备注"]
    kind_name = {"arrangedList": "正常上课", "practiceList": "集中实践", "notArrangeList": "未排课"}
    rows = []
    for c in courses:
        day = int(c.get("day") or 0)
        arranged = bool(c.get("arranged")) and day in DAY_NAMES
        len_text = ""
        if c.get("begin_section") and c.get("end_section"):
            len_text = f"第{c['begin_section']}-{c['end_section']}节"
        rows.append({
            "sort": (DAY_ORDER.get(day, 99), c.get("begin") or "99:99", c.get("course") or ""),
            "vals": [
                term_name,
                c.get("course", ""),
                c.get("code", ""),
                c.get("teach_class", ""),
                c.get("teach_class_name", ""),
                c.get("teacher", ""),
                DAY_NAMES.get(day, "") if arranged else "",
                len_text if arranged else "",
                c.get("begin", "") if arranged else "",
                c.get("end", "") if arranged else "",
                c.get("place", ""),
                c.get("campus", ""),
                c.get("weeks_text", ""),
                c.get("credit", ""),
                kind_name.get(c.get("kind", ""), "正常上课" if arranged else "未排课"),
                c.get("weeks_text", ""),
                c.get("cell_detail", ""),
            ],
        })
    rows.sort(key=lambda r: r["sort"])
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(header)
    for r in rows:
        w.writerow(r["vals"])
    return ("\ufeff" + buf.getvalue()).encode("utf-8")
