"""门户 API：课表、数据卡片、应用列表。"""
from __future__ import annotations

import datetime as dt

from . import config
from .session import ApiError, ApiSession


def _lists(obj, depth=0):
    """递归产出 obj 中所有“字典列表”。"""
    if depth > 5:
        return
    if isinstance(obj, list):
        if obj and all(isinstance(x, dict) for x in obj):
            yield obj
        for x in obj:
            yield from _lists(x, depth + 1)
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _lists(v, depth + 1)


def _first_list(d, keys=("data", "list", "items", "rows", "events", "apps")):
    if isinstance(d, list):
        return d
    if isinstance(d, dict):
        for k in keys:
            v = d.get(k)
            if isinstance(v, list):
                return v
            if isinstance(v, dict):
                for kk in keys:
                    vv = v.get(kk)
                    if isinstance(vv, list):
                        return vv
    return None


class Portal:
    def __init__(self, sess: ApiSession):
        self.s = sess

    # ---- 通用 ------------------------------------------------------------
    def _post_try(self, url, payloads):
        last = None
        for p in payloads:
            try:
                return self.s.post(url, p)
            except ApiError as e:
                last = e
        if last:
            raise last
        return {}

    # ---- 布局 / 应用 -----------------------------------------------------
    def layout(self) -> dict:
        return self._post_try(config.GET_LAYOUT, [{}, {"type": "1"}]).get("d") or {}

    def apps(self) -> list[dict]:
        out, seen = [], set()
        for url in (config.APPS_LIST, config.APP_MARKET):
            try:
                d = self._post_try(url, [{}, {"type": "all"}, {"type": "1"}]).get("d")
            except ApiError:
                continue
            for lst in _lists(d):
                for it in lst:
                    name = str(it.get("name") or it.get("appName") or it.get("title") or "")
                    link = str(it.get("url") or it.get("appUrl") or it.get("link") or "")
                    if not name and not link:
                        continue
                    key = (name, link)
                    if key in seen:
                        continue
                    seen.add(key)
                    out.append({"name": name, "url": link,
                                "icon": it.get("icon") or it.get("logo") or "",
                                "category": it.get("categoryName") or it.get("type") or ""})
        return out

    # ---- 数据卡片（网费/流量等） -----------------------------------------
    def data_cards(self) -> list[dict]:
        items = []
        try:
            d = self._post_try(config.ITEMS_APP, [{}, {"type": "1", "page": "1"}]).get("d")
        except ApiError:
            d = None
        for lst in _lists(d):
            for it in lst:
                if "id" in it and ("name" in it or "title" in it):
                    items.append({
                        "id": str(it.get("id")),
                        "name": str(it.get("name") or it.get("title") or ""),
                        "value": it.get("value") or it.get("data") or "",
                        "unit": it.get("unit") or "",
                    })
        # 去重
        uniq, seen = [], set()
        for it in items:
            if it["id"] in seen:
                continue
            seen.add(it["id"])
            uniq.append(it)
        for it in uniq:
            if it["value"] in ("", None):
                try:
                    det = self.s.post(config.DETAIL_APP, {"id": it["id"]}).get("d") or {}
                    data = det.get("data") if isinstance(det, dict) else None
                    if isinstance(data, dict):
                        it["value"] = data.get("value", "")
                        it["unit"] = data.get("unit", it["unit"])
                except ApiError:
                    pass
        return uniq

    # ---- 深链（从布局里挖出 name+url） -----------------------------------
    def deep_links(self) -> list[dict]:
        out, seen = [], set()

        def walk(obj, depth=0):
            if depth > 6:
                return
            if isinstance(obj, list):
                for x in obj:
                    walk(x, depth + 1)
            elif isinstance(obj, dict):
                url = obj.get("url") or obj.get("link") or obj.get("appUrl") or obj.get("jumpUrl")
                name = obj.get("name") or obj.get("title") or obj.get("appName") or obj.get("menuName")
                if isinstance(url, str) and url.startswith("http") and name:
                    if url not in seen:
                        seen.add(url)
                        out.append({"name": str(name), "url": url})
                for v in obj.values():
                    if isinstance(v, (list, dict)):
                        walk(v, depth + 1)

        try:
            walk(self.layout())
        except Exception:
            pass
        return out

    # ---- 课表 ------------------------------------------------------------
    @staticmethod
    def week_range(anchor: dt.date | None = None) -> tuple[dt.date, dt.date]:
        anchor = anchor or dt.date.today()
        monday = anchor - dt.timedelta(days=anchor.weekday())
        return monday, monday + dt.timedelta(days=6)

    def schedule(self, start: dt.date | None = None, end: dt.date | None = None) -> dict:
        s, e = self.week_range(start)
        if start:
            s = start
        if end:
            e = end
        res = self.s.post(config.APP_WEEK, {
            "calendar_id": "0",
            "start": s.isoformat(),
            "end": e.isoformat(),
            "other": "0",
        })
        raw = res.get("d")
        events = []
        for lst in _lists(raw):
            for it in lst:
                if not isinstance(it, dict):
                    continue
                title = it.get("title") or it.get("className") or it.get("con") or it.get("code")
                st = it.get("start") or it.get("start_time")
                if not title and not st:
                    continue
                events.append({
                    "title": str(title or ""),
                    "start": str(st or ""),
                    "end": str(it.get("end") or ""),
                    "location": str(it.get("location") or ""),
                    "all_day": bool(it.get("allDay")),
                    "desc": str(it.get("weekdesc") or it.get("weekType") or ""),
                    "color": it.get("color") or "",
                })
        # 去重（同一事件可能在多层结构里重复）
        seen, uniq = set(), []
        for ev in events:
            k = (ev["title"], ev["start"], ev["end"], ev["location"])
            if k in seen:
                continue
            seen.add(k)
            uniq.append(ev)
        return {
            "start": s.isoformat(),
            "end": e.isoformat(),
            "events": uniq,
            "raw_empty": not bool(uniq),
        }
