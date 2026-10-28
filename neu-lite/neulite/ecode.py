"""e码通（校园虚拟卡/通行码）接口。"""
from __future__ import annotations

ECODE = "https://ecode.neu.edu.cn/ecode"
SSO = ECODE + "/api/sso/login?redirect_to=/ecode"


def _attr(payload: dict) -> dict:
    data = payload.get("data") or []
    if not data:
        return {}
    return (data[0] or {}).get("attributes") or {}


def fetch(sess) -> dict:
    """需要有效的 CAS TGT；返回余额由调用方补充。"""
    s = sess.s
    r = s.get(SSO, timeout=20, allow_redirects=True)
    if "ecode.neu.edu.cn/ecode" not in r.url:
        raise RuntimeError("e码通登录失败")
    out = {}
    try:
        user = _attr(s.get(ECODE + "/api/user-info", timeout=12).json())
        out.update({
            "name": user.get("userName", ""),
            "student_id": user.get("userCode", ""),
            "department": user.get("unitName", ""),
        })
    except Exception:
        pass
    qr = _attr(s.get(ECODE + "/api/qr-code", timeout=12).json())
    if qr:
        out["qr"] = {
            "code": qr.get("qrCode", ""),
            "created_at": int(qr.get("createTime") or 0),
            "expires_at": int(qr.get("qrInvalidTime") or 0),
        }
    try:
        card = _attr(s.get(ECODE + "/api/ecard-customer", timeout=12).json())
        out["valid_until"] = card.get("noUseDate", "")
    except Exception:
        pass
    try:
        pr = _attr(s.get(ECODE + "/api/user-priority", timeout=12).json())
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
