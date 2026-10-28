"""校园邮箱（Coremail）列表/详情接口。"""
from __future__ import annotations

import email
import email.header
import html
import re
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


def _sso(sess) -> tuple[str, str]:
    r = sess.s.get(MAIL_SSO, timeout=20, allow_redirects=True)
    sid = urllib.parse.parse_qs(urllib.parse.urlparse(r.url).query).get("sid", [""])[0]
    if not sid:
        raise RuntimeError("邮箱登录失败")
    return sid, r.url


def _html_text(value: str) -> str:
    value = re.sub(r"(?is)<(script|style).*?</\1>", "", value)
    value = re.sub(r"(?i)<br\s*/?>", "\\n", value)
    value = re.sub(r"(?i)</p\s*>", "\\n", value)
    value = re.sub(r"<[^>]+>", "", value)
    return html.unescape(value).strip()


def fetch(sess, limit: int = 30) -> dict:
    sid, url = _sso(sess)
    rr = sess.s.get(MAIL_API, timeout=20, headers={"Referer": url}, params={
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
    return {"status": "ok", "items": items, "unread": unread, "web_url": url}


def fetch_detail(sess, mid: str) -> dict:
    sid, referer = _sso(sess)
    rr = sess.s.get(MAIL_API, timeout=25, headers={"Referer": referer}, params={
        "sid": sid, "func": "mbox:getMessageData", "mid": mid,
    })
    rr.raise_for_status()
    msg = email.message_from_bytes(rr.content)
    text_val = ""
    html_val = ""
    attachments = []
    for part in msg.walk():
        ctype = part.get_content_type()
        disp = (part.get_content_disposition() or "").lower()
        name = part.get_filename()
        if name:
            attachments.append(_decode(name))
        if disp == "attachment":
            continue
        payload = part.get_payload(decode=True) or b""
        if not payload:
            continue
        charset = part.get_content_charset() or "utf-8"
        try:
            content = payload.decode(charset, "replace")
        except LookupError:
            content = payload.decode("utf-8", "replace")
        if ctype == "text/plain" and not text_val:
            text_val = content
        elif ctype == "text/html" and not html_val:
            html_val = content
    body = text_val.strip() or _html_text(html_val)
    return {
        "status": "ok",
        "id": mid,
        "subject": _decode(msg.get("Subject", "")),
        "from": _decode(msg.get("From", "")),
        "to": _decode(msg.get("To", "")),
        "date": msg.get("Date", ""),
        "text": body[:120000],
        "attachments": attachments,
        "web_url": referer,
    }
