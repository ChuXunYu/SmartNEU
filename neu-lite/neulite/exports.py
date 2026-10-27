"""把考试、课表导出为 Excel 友好的 CSV（UTF-8 BOM）。"""
from __future__ import annotations

import csv
import io
import json
import re

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


UNKNOWN_TEACHER = "暂未安排教师"
UNKNOWN_ROOM = "暂未安排教室"


def _is_class_group(text: str) -> bool:
    return re.search(r"[^,\s]+\(\d+\)(?:,[^,\s]+\(\d+\))*$", text) is not None


def _clean_place(course: dict) -> str:
    raw = str(course.get("place") or "").replace("*", "").strip()
    if not raw or raw == "停课":
        return UNKNOWN_ROOM
    teachers = {x for x in re.split(r"[,，、/\s]+", str(course.get("teacher") or "")) if x}
    parts = [p for p in raw.split() if p]
    parts = [p for p in parts if p not in teachers and not p.endswith("校区")]
    while len(parts) > 1 and _is_class_group(parts[-1]):
        parts.pop()
    if len(parts) == 1 and _is_class_group(parts[0]):
        parts = []
    return " ".join(parts).strip() or UNKNOWN_ROOM


def _weeks_from_numbers(numbers) -> str:
    vals = sorted({int(x) for x in (numbers or []) if str(x).isdigit()})
    if not vals:
        return ""
    ranges = []
    start = prev = vals[0]
    for n in vals[1:]:
        if n == prev + 1:
            prev = n
            continue
        ranges.append((start, prev))
        start = prev = n
    ranges.append((start, prev))
    return "、".join(f"{a}-{b}周" if b > a else f"{a}周" for a, b in ranges)


def _clean_week(course: dict) -> str:
    raw = str(course.get("weeks_text") or "")
    text = raw.split("[", 1)[0].split("/", 1)[0].strip()
    text = text.replace(",", "、").replace("，", "、").replace("(", "").replace(")", "")
    if text.startswith("第"):
        text = text[1:]
    return text or _weeks_from_numbers(course.get("weeks"))


def timetable_csv(courses: list[dict], term_name: str = "") -> bytes:
    # 与 neuwakeup 的 WakeUP 导入格式完全一致：固定七列
    header = ["课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数"]
    rows = []
    for c in courses:
        try:
            day = int(c.get("day") or 0)
            begin = int(c.get("begin_section") or 0)
            end = int(c.get("end_section") or 0)
        except (TypeError, ValueError):
            continue
        if not c.get("arranged") or not (1 <= day <= 7) or not (1 <= begin <= end <= 12):
            continue
        place = _clean_place(c)
        if place == "停课":
            continue
        course_name = str(c.get("course") or "").strip()
        if not course_name:
            continue
        rows.append([
            course_name,
            day,
            begin,
            end,
            str(c.get("teacher") or "").strip() or UNKNOWN_TEACHER,
            place,
            _clean_week(c),
        ])
    rows.sort(key=lambda r: (r[1], r[2], r[3], r[0]))
    seen, unique = set(), []
    for row in rows:
        key = tuple(row)
        if key not in seen:
            seen.add(key)
            unique.append(row)
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\r\n")
    w.writerow(header)
    w.writerows(unique)
    return ("\ufeff" + buf.getvalue()).encode("utf-8")
