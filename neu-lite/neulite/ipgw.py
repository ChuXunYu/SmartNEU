"""IP 网关（ipgw.neu.edu.cn:8800，深澜 srun）自助服务：读取本月已用流量/消费额/余额。

校内可直连；校外经 WebVPN 的 8800 端口代理（/https-8800/<token>/...）。
"""
from __future__ import annotations

import os
import re
from urllib.parse import quote

from . import config, webvpn

IPGW = os.environ.get("NEU_IPGW_BASE", "http://ipgw.neu.edu.cn:8800")
IPGW_HOME = IPGW + "/home"
IPGW_SSO = IPGW + "/sso/neusoft/index"


def _parse(html: str) -> dict:
    sec = re.search(r"产品信息.*?<tbody>(.*?)</tbody>", html, re.S)
    seg = sec.group(1) if sec else html

    def cell(seq: int) -> str:
        m = re.search(r'data-col-seq="%d"[^>]*>(.*?)</td>' % seq, seg, re.S)
        if not m:
            return ""
        return re.sub(r"<[^>]+>", "", m.group(1)).strip()

    usage_text = cell(3)
    fee_text = cell(6)
    bal_text = cell(7)
    policy = cell(2)
    product = cell(1)
    duration = cell(4)

    m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*([KMGT])", usage_text.upper())
    usage = float(m.group(1)) if m else None
    unit = m.group(2) if m else "G"
    usage_gb = None
    if usage is not None:
        usage_gb = usage * {"K": 1 / 1024 / 1024, "M": 1 / 1024, "G": 1, "T": 1024}[unit]

    def num(s):
        m = re.search(r"([0-9]+(?:\.[0-9]+)?)", s or "")
        return float(m.group(1)) if m else None

    return {
        "usage_gb": usage_gb,
        "usage_text": usage_text,
        "fee": num(fee_text),
        "balance": num(bal_text),
        "policy": policy,
        "product": product,
        "duration": duration,
        "ok": usage_gb is not None,
    }


def fetch(sess) -> dict | None:
    """优先直连，失败再走 WebVPN。sess 是 ApiSession。"""
    if not sess.state.ticket:
        return None
    # 1) 校内直连：CAS -> ipgw /home
    try:
        r = sess.raw_get("https://pass.neu.edu.cn/tpass/login",
                         params={"service": IPGW_SSO}, allow_redirects=True, timeout=20)
        if "ipgw.neu.edu.cn" in (r.url or "") and "已用流量" in r.text:
            out = _parse(r.text)
            out["via"] = "direct"
            return out
    except Exception:
        pass
    # 2) 校外：WebVPN 8800 端口代理 + CAS ticket
    try:
        if not webvpn.login(sess):
            return None
        token = webvpn.token("ipgw.neu.edu.cn")
        base = f"{webvpn.WV_BASE}/https-8800/{token}"
        t = webvpn.ticket(sess, IPGW_SSO)
        if not t:
            return None
        r = sess.raw_get(base + "/sso/neusoft/index?ticket=" + quote(t, safe=""),
                         allow_redirects=True, timeout=30)
        if "已用流量" not in r.text:
            r = sess.raw_get(base + "/home", allow_redirects=True, timeout=25)
        if "已用流量" in r.text:
            out = _parse(r.text)
            out["via"] = "webvpn"
            return out
    except Exception:
        return None
    return None
