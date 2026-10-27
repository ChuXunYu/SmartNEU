"""网瑞达 WebVPN（wengine）：校外访问 jwxt 等仅校内可达的系统。

URL 形式：https://webvpn.neu.edu.cn/https/<hex>/<path>
<hex> = hex(IV + AES-CFB(key, IV, host))，key = IV = "b0A58a69394ce73@"，无填充。
"""
from __future__ import annotations

import os
from urllib.parse import quote

from Crypto.Cipher import AES

from . import config

WV_BASE = os.environ.get("NEU_WEBVPN_BASE", "https://webvpn.neu.edu.cn")
WV_LOGIN = WV_BASE + "/login?cas_login=true"
WV_KEY = b"b0A58a69394ce73@"


def token(host: str) -> str:
    cipher = AES.new(WV_KEY, AES.MODE_CFB, WV_KEY, segment_size=128)
    return (WV_KEY + cipher.encrypt(host.encode("utf-8"))).hex()


def proxy_url(host: str, path: str = "") -> str:
    return f"{WV_BASE}/https/{token(host)}{path}"


def login(sess) -> bool:
    """用会话里的 CASTGC 登录 WebVPN。"""
    url = config.CAS_LOGIN + "?service=" + quote(WV_LOGIN, safe="")
    try:
        r = sess.raw_get(url, allow_redirects=True, timeout=25)
    except Exception:
        return False
    return WV_BASE in (r.url or "")


def ticket(sess, service: str) -> str | None:
    """向 CAS 申请某个 service 的 ticket（不跟随到目标站，因为目标站校外不可达）。"""
    url = config.CAS_LOGIN + "?service=" + quote(service, safe="")
    try:
        r = sess.raw_get(url, allow_redirects=False, timeout=20)
    except Exception:
        return None
    loc = r.headers.get("Location") or ""
    if "ticket=" not in loc:
        return None
    return loc.split("ticket=", 1)[1].split("&", 1)[0]


def open_service(sess, host: str, path: str, service: str) -> str | None:
    """用 ticket 在 WebVPN 内建立目标站会话，返回代理基址。"""
    t = ticket(sess, service)
    if not t:
        return None
    base = proxy_url(host)
    try:
        sess.raw_get(base + path + "?ticket=" + quote(t, safe=""), allow_redirects=True, timeout=30)
    except Exception:
        return None
    return base
