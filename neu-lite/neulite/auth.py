"""登录、短信二次验证、会话校验、登出。"""
from __future__ import annotations

from . import config, state as state_mod
from .session import ApiError, ApiSession, SessionExpired


class NeedSms(RuntimeError):
    """需要短信二次验证。"""

    def __init__(self, temp_token: str):
        self.temp_token = temp_token
        super().__init__("需要短信验证码")


class Auth:
    def __init__(self, st, sess: ApiSession):
        self.st = st
        self.s = sess

    # ---- 登录 ------------------------------------------------------------
    def login(self, username: str, password: str, remember: bool = False,
              sms_code: str | None = None, trust_device: bool = False) -> dict:
        res = self.s.post(config.COMMIT, {
            "username": username,
            "password_removed": None,
            "imei": self.st.imei,
            "mobile_type": "android",
        })
        d = res.get("d") or {}
        code = str(d.get("login_code", ""))
        if code == "1":
            self._apply_login(d, username)
            if remember:
                self.st.remember = {"username": username, "password_removed": None}
            state_mod.save(self.st)
            return {"status": "ok", "profile": self.st.profile}
        if code == "3":
            self.st.temp_token = str(d.get("login_result") or "")
            state_mod.save(self.st)
            if sms_code:
                return self.verify_sms(sms_code, trust_device)
            raise NeedSms(self.st.temp_token)
        raise ApiError(code or -1, str(res.get("m") or d.get("m") or "登录失败"))

    def send_sms(self) -> dict:
        return self.s.post(config.SMS_SEND, {
            "temp_token": self.st.temp_token,
            "imei": self.st.imei,
        })

    def verify_sms(self, sms_code: str, trust_device: bool = False) -> dict:
        res = self.s.post(config.SMS_VERIFY, {
            "temp_token": self.st.temp_token,
            "imei": self.st.imei,
            "sms_code": sms_code,
            "trust_device": "1" if trust_device else "0",
        })
        d = res.get("d") or {}
        if str(d.get("login_code", "")) != "1":
            raise ApiError(str(d.get("login_code") or -1), str(res.get("m") or "验证失败"))
        self._apply_login(d, self.st.profile.get("username", ""))
        self.st.temp_token = ""
        state_mod.save(self.st)
        return {"status": "ok", "profile": self.st.profile}

    def _apply_login(self, d: dict, username: str):
        self.st.ticket = str(d.get("login_ticket") or d.get("login_result") or "")
        self.st.ck_lc = str(d.get("CK_LC") or d.get("ckLc") or "")
        self.st.ck_vl = str(d.get("CK_VL") or d.get("ckVl") or "")
        self.st.profile = {
            "username": username or self.st.profile.get("username", ""),
            "name": str(d.get("name") or ""),
            "xgh": str(d.get("xgh") or ""),
            "role": str(d.get("role") or ""),
            "role_type": str(d.get("role_type") or ""),
            "depart": str(d.get("depart") or ""),
            "avatar": str(d.get("avatar") or ""),
        }
        # 刷新 Cookie
        self.s.state = self.st
        self.s._seed_portal_cookies()

    # ---- 校验 / 续期 -----------------------------------------------------
    def check(self) -> dict:
        if not self.st.ticket:
            raise SessionExpired("本地无 ticket")
        res = self.s.post(config.CHECK, {"ticket": self.st.ticket}, ticket=self.st.ticket)
        return res.get("d") or {}

    def ensure(self) -> dict:
        """校验会话；“记住密码”时自动重登一次。"""
        try:
            return self.check()
        except SessionExpired:
            cred = self.st.remember or {}
            if cred.get("username") and cred.get("password"):
                self.login(cred["username"], cred["password"])
                return self.check()
            raise

    # ---- 登出 ------------------------------------------------------------
    def logout(self) -> None:
        try:
            self.s.post(config.LOGOUT, {})
        except (ApiError, Exception):
            pass
        self.st.ticket = ""
        self.st.ck_lc = ""
        self.st.ck_vl = ""
        self.st.temp_token = ""
        self.st.profile = {}
        self.s.s.cookies.clear()
        state_mod.save(self.st)
