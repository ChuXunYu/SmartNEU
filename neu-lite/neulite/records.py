"""把教务/门户返回的任意 JSON 归一化成考试、成绩记录。

教务各校字段不同（kcmc/courseName/课程名称…），这里按“命中字段数”挑最像的列表，
再做字段映射；适配新学校时只需补充 KEY_MAP。
"""
from __future__ import annotations

import re

COURSE = ("kcmc", "courseName", "course", "course_name", "课程名称", "课程", "title")
SCORE = ("cj", "score", "成绩", "zf", "总评", "finalScore")
CREDIT = ("xf", "credit", "学分", "credits")
TIME = ("kssj", "examTime", "考试时间", "时间", "startTime", "time")
PLACE = ("ksdd", "place", "地点", "考场", "教室", "location")
SEAT = ("zwh", "seat", "座位号", "座位")
TEACHER = ("jsxm", "teacher", "教师", "任课教师")
TERM = ("xq", "term", "学期", "xnxq")

EXAM_KEYS = (COURSE, TIME, PLACE)
GRADE_KEYS = (COURSE, SCORE, CREDIT)


def _lists(obj, depth=0):
    if depth > 6:
        return
    if isinstance(obj, list):
        if obj and all(isinstance(x, dict) for x in obj):
            yield obj
        for x in obj:
            yield from _lists(x, depth + 1)
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _lists(v, depth + 1)


def _hit(d: dict, keys) -> int:
    return sum(1 for k in keys if k in d)


def _best(obj, groups) -> list[dict]:
    keys = tuple(k for g in groups for k in g)
    best, score = [], 0
    for lst in _lists(obj):
        s = sum(_hit(x, keys) for x in lst) / max(len(lst), 1)
        if s > score:
            best, score = lst, s
    return best if score >= 2 else []


def _get(d: dict, keys, default=""):
    for k in keys:
        if k in d and d[k] not in (None, ""):
            return d[k]
    return default


def _text(v) -> str:
    return re.sub(r"\s+", " ", str(v)).strip()


def exams(obj) -> list[dict]:
    out = []
    for it in _best(obj, EXAM_KEYS):
        course = _get(it, COURSE)
        if not course:
            continue
        out.append({
            "course": _text(course),
            "time": _text(_get(it, TIME)),
            "place": _text(_get(it, PLACE)),
            "seat": _text(_get(it, SEAT)),
        })
    return out


def grades(obj) -> list[dict]:
    out = []
    for it in _best(obj, GRADE_KEYS):
        course = _get(it, COURSE)
        if not course:
            continue
        out.append({
            "course": _text(course),
            "score": _text(_get(it, SCORE)),
            "credit": _text(_get(it, CREDIT)),
            "teacher": _text(_get(it, TEACHER)),
            "term": _text(_get(it, TERM)),
        })
    return out
