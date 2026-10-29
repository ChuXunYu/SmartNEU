"""HTTP 会话与统一信封。"""
from __future__ import annotations

import json

import requests

from . import config, crypto


class ApiError(RuntimeError):
    def __init__(self, code, message: str = ""):
        self.code = code
        self.message = message
        super().__init__(f"e={code} m={message}")


class SessionExpired(ApiError):
    def __init__(self, message: str = "会话失效"):
        super().__init__(10013, message)


class ApiSession:
    """带 App 头、信封加解密、Cookie 持久化的 requests 会话。"""

    def __init__(self, state, timeout: int = 15):
        self.state = state
        self.timeout = timeout
        self.s = requests.Session()
        self.s.headers.update({"User-Agent": config.UA})
        self._restore_cookies()
        self._seed_portal_cookies()

    # ---- cookies ---------------------------------------------------------
    def _restore_cookies(self):
        for c in self.state.cookies or []:
            try:
                self.s.cookies.set(c["name"], c["value"], domain=c.get("domain"), path=c.get("path", "/"))
            except (KeyError, TypeError):
                continue

    def _seed_portal_cookies(self):
        if self.state.ticket:
            # CAS TGT，供 pass.neu.edu.cn 及 .neu.edu.cn 下的系统免登
            self.s.cookies.set("CASTGC", self.state.ticket, domain=".neu.edu.cn", path="/")
            self.s.cookies.set("CASTGC", self.state.ticket, domain="pass.neu.edu.cn", path="/tpass/")
        if self.state.ck_lc:
            self.s.cookies.set("CK_LC", self.state.ck_lc, domain="personal.neu.edu.cn", path="/")
        if self.state.ck_vl:
            self.s.cookies.set("CK_VL", self.state.ck_vl, domain="personal.neu.edu.cn", path="/")

    def snapshot_cookies(self):
        out = []
        for c in self.s.cookies:
            out.append({"name": c.name, "value": c.value, "domain": c.domain, "path": c.path})
        return out

    # ---- 请求 ------------------------------------------------------------
    def post(self, url: str, payload: dict | None = None, ticket: str | None = None,
             timeout: int | None = None) -> dict:
        """POST 业务 JSON（加密为 content 表单字段），返回解密后的信封。"""
        ticket = self.state.ticket if ticket is None else ticket
        body = crypto.content_field(payload or {})
        resp = self.s.post(
            url,
            data=body,
            headers=crypto.auth_headers(ticket),
            timeout=timeout or self.timeout,
        )
        resp.raise_for_status()
        return self._parse(resp)

    def _parse(self, resp) -> dict:
        try:
            env = resp.json()
        except ValueError:
            raise ApiError(-1, f"非 JSON 响应: {resp.text[:200]}")
        code = int(str(env.get("e", -1)))
        if code == 10013:
            raise SessionExpired(str(env.get("m") or "会话失效"))
        if code != 0:
            raise ApiError(code, str(env.get("m") or ""))
        d = env.get("d")
        if isinstance(d, str) and d:
            try:
                d = json.loads(crypto.decrypt(d))
            except (ValueError, UnicodeDecodeError):
                pass  # 少数接口 d 可能不是密文，原样返回
        return {"e": code, "m": env.get("m", ""), "d": d}

    def raw_get(self, url: str, **kw):
        kw.setdefault("timeout", self.timeout)
        return self.s.get(url, **kw)
