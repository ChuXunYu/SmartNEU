"""新教务系统（jwxt.neu.edu.cn，金智 jwapp）适配。

已实测可用的数据接口（2026-10-27，真实账号）：
  * 当前学期/教学周：/jwapp/sys/homeapp/api/home/currentUser.do
  * 课表：        POST /jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do  {XNXQDM}
  * 考试安排：    POST /jwapp/sys/wdkwapp/api/wdks/queryMyExamArrangeMent.do
  * 成绩：        POST /jwapp/sys/cjzhcxapp/modules/wdcj/cxwdcj.do
  * 绩点/排名：   POST /jwapp/sys/cjzhcxapp/api/wdcj/queryPjxfjd.do
"""
from __future__ import annotations

import datetime as dt
import json
import re
import time

from . import config, state as state_mod, webvpn
from .session import ApiSession, SessionExpired

JW = config.JWXT_BASE + "/jwapp/sys"
CURRENT_USER = JW + "/homeapp/api/home/currentUser.do"
TIMETABLE = JW + "/kbapp/api/wdkbcx/getMyScheduleDetail.do"
EXAMS = JW + "/wdkwapp/api/wdks/queryMyExamArrangeMent.do"
GRADES = JW + "/cjzhcxapp/modules/wdcj/cxwdcj.do"
GPA_RANK = JW + "/cjzhcxapp/api/wdcj/queryPjxfjd.do"

WS_RE = re.compile(r"(\d+)\s*-\s*(\d+)\s*周|(\d+)\s*周")


def parse_weeks(text: str) -> list[int]:
    """'10-14周,16-17周[理论]/...' -> [10..14, 16, 17]"""
    out = set()
    for m in WS_RE.finditer(text or ""):
        if m.group(1):
            a, b = int(m.group(1)), int(m.group(2))
            if 0 < a <= b <= 40:
                out.update(range(a, b + 1))
        elif m.group(3):
            n = int(m.group(3))
            if 0 < n <= 40:
                out.add(n)
    return sorted(out)


def parse_teacher(text: str) -> str:
    m = re.search(r"/([^/\[\]]+)\[", text or "")
    return m.group(1).strip() if m else ""


class Jwxt:
    def __init__(self, sess: ApiSession, st):
        self.s = sess
        self.st = st
        self.base = config.JWXT_BASE
        self._sso_ok = False
        self.webvpn = False
        self.webvpn_base = ""
        self._term_cache = None
        self._term_at = 0.0
        self._tt_cache = None
        self._tt_term = ""
        self._tt_at = 0.0

    def invalidate(self):
        self._term_cache = None
        self._tt_cache = None

    # ---- 会话 ------------------------------------------------------------
    def ensure(self) -> bool:
        if self._sso_ok:
            return True
        if not self.st.ticket:
            return False
        if not self.webvpn:
            if self._direct_reachable():
                self._sso_ok = self.sso().get("status") == "ok"
                if self._sso_ok:
                    return True
            self.webvpn = True
        self._sso_ok = self._webvpn_sso()
        return self._sso_ok

    def _direct_reachable(self) -> bool:
        """校外直连 jwxt 会超时；用短超时探测，避免每次卡 15 秒。"""
        try:
            r = self.s.raw_get(config.JWXT_HOME, allow_redirects=True, timeout=5)
            return r.status_code > 0
        except Exception:
            return False

    def _webvpn_sso(self) -> bool:
        try:
            if not webvpn.login(self.s):
                return False
            path = "/jwapp/sys/homeapp/index.do"
            base = webvpn.open_service(self.s, "jwxt.neu.edu.cn", path, config.JWXT_BASE + path)
            if not base:
                return False
            self.s.raw_get(base + "/jwapp/sys/homeapp/home/index.html",
                           allow_redirects=True, timeout=25)
            self.webvpn_base = base
            self.base = base
            return True
        except Exception:
            return False

    def sso(self) -> dict:
        """用 CASTGC(=TGT) 走 CAS，建立 jwxt 会话。"""
        if not self.st.ticket:
            return {"status": "need_login", "note": "尚未登录"}
        if self.webvpn:
            return {"status": "ok", "mode": "webvpn"}
        try:
            r = self.s.raw_get(config.JWXT_HOME, allow_redirects=True)
        except Exception as e:
            return {"status": "error", "note": f"访问教务失败: {e}"}
        final = r.url or ""
        if "pass.neu.edu.cn" in final or "/tpass/login" in final:
            return {"status": "need_login", "note": "CAS 未放行（需校园网/WebVPN）", "final_url": final}
        if r.status_code >= 400:
            return {"status": "error", "note": f"HTTP {r.status_code}"}
        self._html = r.text
        self._sso_ok = True
        return {"status": "ok", "final_url": final}

    def sso_login_url(self, service: str) -> str | None:
        if not self.st.ticket:
            return None
        try:
            r = self.s.s.get(config.CAS_LOGIN, params={"service": service},
                             allow_redirects=False, timeout=15)
        except Exception:
            return None
        loc = r.headers.get("Location") or ""
        return loc if "ticket=" in loc else None

    # ---- 基础请求 --------------------------------------------------------
    def _post(self, url: str, data: dict | None = None) -> dict:
        if not self.ensure():
            raise SessionExpired("教务未登录")
        if self.webvpn:
            url = self.webvpn_base + url[len(config.JWXT_BASE):]
        r = self.s.s.post(url, data=data or {}, timeout=25,
                          headers={"X-Requested-With": "XMLHttpRequest",
                                   "Referer": self.base + "/jwapp/sys/homeapp/home/index.html"})
        text = r.text.strip()
        if not text.startswith("{"):
            raise RuntimeError(f"教务返回非 JSON（{r.status_code}）")
        obj = json.loads(text)
        if str(obj.get("code")) not in ("0", "200"):
            raise RuntimeError(f"教务错误 code={obj.get('code')} msg={obj.get('msg')}")
        return obj

    def current_term(self) -> dict:
        if self._term_cache is not None and time.time() - self._term_at < 300:
            return self._term_cache
        obj = self._post(CURRENT_USER)
        info = (obj.get("datas") or {}).get("welcomeInfo") or {}
        out = {"term": info.get("xnxqdm", ""), "term_name": info.get("xnxqmc", ""),
               "week": int(info.get("classWeek") or 0), "date": info.get("date", "")}
        self._term_cache = out
        self._term_at = time.time()
        return out

    # ---- 课表 ------------------------------------------------------------
    def timetable(self, term: str | None = None) -> dict:
        info = None
        if not term:
            info = self.current_term()
            term = info["term"]
        if self._tt_cache is not None and self._tt_term == term and time.time() - self._tt_at < 600:
            return self._tt_cache
        obj = self._post(TIMETABLE, {"XNXQDM": term})
        d = (obj.get("datas") or {}).get("getMyScheduleDetail") or {}
        courses = []
        for key in ("arrangedList", "practiceList", "notArrangeList"):
            for row in d.get(key) or []:
                weeks = parse_weeks(row.get("weeksAndTeachers", ""))
                courses.append({
                    "course": row.get("courseName") or "",
                    "teacher": parse_teacher(row.get("weeksAndTeachers", "")),
                    "place": row.get("placeName") or "",
                    "day": int(row.get("dayOfWeek") or 0),
                    "begin_section": row.get("beginSection"),
                    "end_section": row.get("endSection"),
                    "begin": row.get("beginTime") or "",
                    "end": row.get("endTime") or "",
                    "weeks": weeks,
                    "weeks_text": row.get("weeksAndTeachers") or "",
                    "credit": row.get("credit") or "",
                    "color": row.get("color") or "",
                    "code": row.get("courseCode") or "",
                    "teach_class": row.get("teachClassId") or "",
                    "teach_class_name": row.get("teachClassName") or "",
                    "campus": row.get("campusName") or "",
                    "tags": row.get("tags") or "",
                    "cell_detail": " / ".join(
                        x.get("text", "") for x in (row.get("cellDetail") or []) if isinstance(x, dict) and x.get("text")
                    ),
                    "arranged": key == "arrangedList",
                    "kind": key,
                })
        out = {"term": term, "term_name": (info or {}).get("term_name", ""),
               "courses": courses}
        self._tt_cache = out
        self._tt_term = term
        self._tt_at = time.time()
        return out

    # ---- 周次（周日为一周第一天） ---------------------------------------
    @staticmethod
    def _sunday_of(d: dt.date) -> dt.date:
        return d - dt.timedelta(days=(d.weekday() + 1) % 7)

    def _term_first_monday(self) -> tuple[dt.date, int]:
        """第 1 教学周的周一（教务数据里周一=1 … 周日=7）。"""
        info = self.current_term()
        week = info.get("week") or 1
        today = dt.date.today()
        this_monday = today - dt.timedelta(days=today.weekday())
        first = this_monday - dt.timedelta(days=(week - 1) * 7)
        return first, week

    def week_events(self, start: dt.date | None = None) -> dict:
        """以周日为第一天展示；每个日期按它所属的教学周取课。"""
        first_monday, cur_week = self._term_first_monday()
        today = dt.date.today()
        anchor = start or self._sunday_of(today)
        anchor = self._sunday_of(anchor)
        days = [anchor + dt.timedelta(days=i) for i in range(7)]  # 周日..周六
        data = self.timetable()
        events = []
        for idx, d in enumerate(days):
            col_day = 7 if idx == 0 else idx      # 周日=7，周一=1 … 周六=6
            monday = d - dt.timedelta(days=d.weekday())
            week_no = (monday - first_monday).days // 7 + 1
            for c in data["courses"]:
                if not c["arranged"] or c["day"] != col_day:
                    continue
                if week_no not in c["weeks"]:
                    continue
                start_s = f"{d.isoformat()}T{c['begin'] or '00:00'}:00"
                end_s = f"{d.isoformat()}T{c['end'] or '00:00'}:00"
                events.append({
                    "title": c["course"],
                    "start": start_s,
                    "end": end_s,
                    "location": c["place"],
                    "desc": c["weeks_text"],
                    "teacher": c["teacher"],
                    "sections": f"{c['begin_section']}-{c['end_section']}" if c["begin_section"] else "",
                    "color": c["color"],
                })
        events.sort(key=lambda e: (e["start"], e["title"]))
        label_week = ((days[1] - first_monday).days // 7) + 1  # 用周一所在周做标题
        return {"start": anchor.isoformat(), "end": (anchor + dt.timedelta(days=6)).isoformat(),
                "week": label_week, "current_week": cur_week, "events": events,
                "courses": data["courses"]}

    # ---- 考试 ------------------------------------------------------------
    def exams(self) -> list[dict]:
        obj = self._post(EXAMS)
        d = (obj.get("datas") or {}).get("queryMyExamArrangeMent") or {}
        out = []
        for row in d.get("arranged") or []:
            out.append({
                "course": row.get("KCM") or "",
                "time": f"{row.get('KSRQ','')} {row.get('KSSJ','')}-{row.get('JSSJ','')}".strip(),
                "place": row.get("JASMC") or "",
                "seat": str(row.get("ZWH") or ""),
                "type": row.get("KSLXDM_DISPLAY") or "",
                "term": row.get("XNXQDM") or "",
                "desc": row.get("KSSJMS") or "",
                "raw": row,
            })
        out.sort(key=lambda x: x["time"])
        return out

    # ---- 成绩与排名 ------------------------------------------------------
    def grades(self) -> dict:
        obj = self._post(GRADES)
        d = (obj.get("datas") or {}).get("cxwdcj") or {}
        items = []
        for row in d.get("rows") or []:
            items.append({
                "course": row.get("KCM") or "",
                "score": str(row.get("XSZCJ") if row.get("XSZCJ") is not None else ""),
                "credit": str(row.get("XF") if row.get("XF") is not None else ""),
                "gpa": str(row.get("JD") if row.get("JD") is not None else ""),
                "term": row.get("XNXQDM_DISPLAY") or row.get("XNXQDM") or "",
                "type": row.get("KCXZDM_DISPLAY") or "",
                "exam_type": (row.get("KSLXDM_DISPLAY") or row.get("KSFSDM_DISPLAY")
                              or row.get("KHFSDM_DISPLAY") or ""),
                "passed": row.get("SFJG_DISPLAY") or "",
            })
        rank = {}
        try:
            r = self._post(GPA_RANK)
            rd = (r.get("datas") or {}).get("queryPjxfjd") or {}
            rank = {"gpa": rd.get("ZPJXFJDNEW") or rd.get("ZPJXFJD") or "",
                    "major_rank": rd.get("ZPJXFJDPM") or ""}
        except Exception:
            pass
        return {"items": items, "rank": rank}
