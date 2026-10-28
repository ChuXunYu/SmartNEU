"""e码通（校园虚拟卡/通行码）接口。"""
from __future__ import annotations

from urllib.parse import quote

from . import webvpn

ECODE = "https://ecode.neu.edu.cn/ecode"
SSO = ECODE + "/api/sso/login?redirect_to=/ecode"


def _attr(payload: dict) -> dict:
    data = payload.get("data") or []
    if not data:
        return {}
    return (data[0] or {}).get("attributes") or {}


def _base(sess) -> str:
    s = sess.s
    r = s.get(SSO, timeout=20, allow_redirects=True)
    if "ecode.neu.edu.cn/ecode" in r.url:
        return ECODE
    if not webvpn.login(sess):
        raise RuntimeError("e码通登录失败（校园网/WebVPN 均不可用）")
    base = webvpn.proxy_url("ecode.neu.edu.cn")
    t = webvpn.ticket(sess, SSO)
    if not t:
        raise RuntimeError("e码通登录失败（WebVPN 票据）")
    sess.raw_get(base + "/ecode/api/sso/login?redirect_to=/ecode&ticket=" + quote(t, safe=""),
                 allow_redirects=True, timeout=30)
    return base


def fetch(sess) -> dict:
    """需要有效的 CAS TGT；校园网直连，校外自动 WebVPN。"""
    base = _base(sess)
    s = sess.s
    out = {}
    try:
        user = _attr(s.get(base + "/api/user-info", timeout=12).json())
        out.update({
            "name": user.get("userName", ""),
            "student_id": user.get("userCode", ""),
            "department": user.get("unitName", ""),
        })
    except Exception:
        pass
    qr = _attr(s.get(base + "/api/qr-code", timeout=12).json())
    if qr:
        out["qr"] = {
            "code": qr.get("qrCode", ""),
            "created_at": int(qr.get("createTime") or 0),
            "expires_at": int(qr.get("qrInvalidTime") or 0),
        }
    try:
        card = _attr(s.get(base + "/api/ecard-customer", timeout=12).json())
        out["valid_until"] = card.get("noUseDate", "")
    except Exception:
        pass
    try:
        pr = _attr(s.get(base + "/api/user-priority", timeout=12).json())
        rules = (pr.get("ruleset") or {}).get("data") or {}
        if rules:
            out["gate"] = {
                "name": rules.get("name", ""),
                "color": rules.get("color", ""),
                "result": str(rules.get("result", "")),
            }
    except Exception:
        pass
    return out
