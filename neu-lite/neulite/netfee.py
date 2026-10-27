"""网费 / 校园网流量。

来源优先级：
  1. 门户“数据卡片”（personal/frontend/data，detail_app）——学校若配置了网费/流量卡片，
     登录后可直接拿到 value+unit；
  2. 门户应用列表里形如“网费/校园网/自助服务”的链接；
  3. state.endpoints.netfee.url 指定的自助服务页（可选，抓取 HTML 数字）。
"""
from __future__ import annotations

import json
import re

from . import config, ipgw, state as state_mod
from .portal import Portal
from .session import ApiSession

MONEY_RE = re.compile(r"(?:余额|剩余|账户余额)[^\d\-]{0,6}(-?\d+(?:\.\d+)?)\s*(元|块)?")
TRAFFIC_RE = re.compile(r"(?:流量|已用|使用量|剩余流量)[^\d]{0,6}(\d+(?:\.\d+)?)\s*(TB|GB|MB|G|M|KB)", re.I)


class NetFee:
    def __init__(self, sess: ApiSession, st, portal: Portal):
        self.s = sess
        self.st = st
        self.portal = portal

    def cards(self, all_cards: list[dict] | None = None) -> list[dict]:
        out = []
        for c in (all_cards if all_cards is not None else self.portal.data_cards()):
            name = c.get("name", "")
            if any(k in name for k in config.NET_KEYWORDS):
                out.append(c)
        return out

    def links(self) -> list[dict]:
        out, seen = [], set()
        for a in self.portal.apps() + self.portal.deep_links():
            text = (a.get("name") or "") + " " + (a.get("category") or "")
            if any(k in text for k in config.NET_KEYWORDS) and a.get("url") not in seen:
                seen.add(a.get("url"))
                out.append(a)
        return out

    def from_url(self, url: str) -> dict:
        r = self.s.raw_get(url)
        if "json" in (r.headers.get("Content-Type") or "").lower():
            try:
                return {"json": r.json()}
            except ValueError:
                pass
        text = re.sub(r"<[^>]+>", " ", r.text)
        text = re.sub(r"\s+", " ", text)
        money = [{"value": float(m.group(1)), "unit": m.group(2) or "元"}
                 for m in MONEY_RE.finditer(text)]
        traffic = [{"value": float(m.group(1)), "unit": m.group(2).upper()}
                   for m in TRAFFIC_RE.finditer(text)]
        return {"money": money[:3], "traffic": traffic[:3], "title": (re.search(r"<title>(.*?)</title>", r.text) or [None, ""])[1]}

    def summary(self, all_cards: list[dict] | None = None) -> dict:
        cards = self.cards(all_cards)
        links = self.links()
        result = {"status": "ok" if cards else ("partial" if links else "unavailable"),
                  "cards": cards, "links": links[:10], "note": ""}
        if not cards and links:
            result["note"] = "门户未返回网费数据卡片，可从下方链接进入自助服务"
        if not cards and not links:
            result["note"] = "未在门户发现网费入口，可在 state.json 的 endpoints.netfee.url 手动指定"
        url = ((self.st.endpoints or {}).get("netfee") or {}).get("url")
        if url:
            try:
                result["scraped"] = self.from_url(url)
                result["status"] = "ok"
            except Exception as e:
                result["scrape_error"] = str(e)
        return result
