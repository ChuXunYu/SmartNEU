"""校园邮箱（Coremail）列表接口。"""
from __future__ import annotations

import email.header
import urllib.parse
import xml.etree.ElementTree as ET

MAIL_SSO = "https://mails.neu.edu.cn/coremail/cmcu_addon/dbdxsso.jsp"
MAIL_API = "https://mails.neu.edu.cn/coremail/s"


def _text(node, name: str) -> str:
    for ch in node:
        if ch.get("name") == name:
            return ch.text or ""
    return ""


def _decode(value: str) -> str:
    try:
        parts = email.header.decode_header(value or "")
        out = []
        for raw, charset in parts:
            if isinstance(raw, bytes):
                out.append(raw.decode(charset or "utf-8", "replace"))
            else:
                out.append(raw)
        return "".join(out)
    except Exception:
        return value or ""


def fetch(sess, limit: int = 30) -> dict:
    s = sess.s
    r = s.get(MAIL_SSO, timeout=20, allow_redirects=True)
    sid = urllib.parse.parse_qs(urllib.parse.urlparse(r.url).query).get("sid", [""])[0]
    if not sid:
        raise RuntimeError("邮箱登录失败")
    rr = s.get(MAIL_API, timeout=20, headers={"Referer": r.url}, params={
        "sid": sid, "func": "mbox:listMessages", "mbox": "Inbox",
        "limit": str(limit), "order": "date", "desc": "1",
    })
    rr.raise_for_status()
    root = ET.fromstring(rr.text)
    items = []
    for obj in root.findall(".//array[@name='var']/object"):
        flags = {}
        for ch in obj:
            if ch.tag == "object" and ch.get("name") == "flags":
                flags = {x.get("name"): (x.text or "") for x in ch}
        items.append({
            "id": _text(obj, "id"),
            "from": _decode(_text(obj, "from")),
            "subject": _decode(_text(obj, "subject")),
            "date": _text(obj, "sentDate"),
            "size": _text(obj, "size"),
            "unread": flags.get("read") == "false",
            "attached": flags.get("attached") == "true",
        })
    unread = sum(1 for x in items if x["unread"])
    return {"status": "ok", "items": items, "unread": unread, "web_url": r.url}
