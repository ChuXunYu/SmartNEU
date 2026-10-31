"""本地 Web 服务：JSON API + 静态页面。"""
from __future__ import annotations

import datetime as dt
import json
import mimetypes
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import auth as auth_mod, config, ecode, exports, ipgw, mail, mock, pay, state as state_mod
from .auth import NeedSms
from .jwxt import Jwxt
from .netfee import NetFee
from .portal import Portal
from .session import ApiError, ApiSession, SessionExpired


class Ctx:
    def __init__(self, demo: bool = False):
        self.demo = demo
        self.lock = threading.RLock()
        self.st = state_mod.load()
        self.sess = ApiSession(self.st)
        self.auth = auth_mod.Auth(self.st, self.sess)
        self.portal = Portal(self.sess)
        self.jwxt = Jwxt(self.sess, self.st)
        self.net = NetFee(self.sess, self.st, self.portal)
        self._jwxt_cache = None

    def reload(self):
        self.st = state_mod.load()
        self.sess = ApiSession(self.st)
        self.auth = auth_mod.Auth(self.st, self.sess)
        self.portal = Portal(self.sess)
        self.jwxt = Jwxt(self.sess, self.st)
        self.net = NetFee(self.sess, self.st, self.portal)
        self._jwxt_cache = None

    # ---- 各功能 ----------------------------------------------------------
    def session_info(self) -> dict:
        profile = mock.overview()["profile"] if self.demo else self.st.profile
        remember = self.st.remember or {}
        return {
            "logged_in": bool(self.st.ticket) or self.demo,
            "profile": profile,
            "imei": (self.st.imei[:8] + "…") if self.st.imei else "",
            "remember": bool(remember),
            "saved_user": remember.get("username", ""),
            "password_saved": False,
            "saved_name": remember.get("name", "") or profile.get("name", ""),
            "saved_xgh": remember.get("xgh", "") or profile.get("xgh", ""),
            "updated_at": self.st.updated_at,
            "demo": self.demo,
        }

    def schedule(self, start: str | None = None) -> dict:
        if self.demo:
            return mock.overview()["schedule"]
        anchor = dt.date.fromisoformat(start) if start else None
        if self.st.ticket:
            try:
                if self.jwxt.ensure():
                    return self.jwxt.week_events(anchor)
            except Exception:
                pass
        return self.portal.schedule(anchor)

    def net_info(self) -> dict:
        if self.demo:
            return mock.overview()["net"]
        return self.net.summary()

    def ipgw_usage(self) -> dict:
        if self.demo:
            net = mock.overview()["net"]
            keys = ("usage_gb", "usage_text", "fee", "policy", "duration", "product", "via")
            return {"ok": True, **{k: net.get(k) for k in keys if net.get(k) is not None}}
        try:
            return ipgw.fetch(self.sess) or {"ok": False}
        except Exception as e:
            return {"ok": False, "error": str(e)}

    def card_info(self) -> dict:
        if self.demo:
            return mock.card_info()
        out = {"status": "ok", "name": self.st.profile.get("name", ""),
               "student_id": self.st.profile.get("xgh", ""), "unit": self.st.profile.get("depart", "")}
        try:
            cards = self.portal.data_cards()
            card = next((c for c in cards if c.get("key") == "card.balance"), None)
            if card is None:
                card = next((c for c in cards if "校园卡" in c.get("name", "")), None)
            if card:
                out["balance"] = str(card.get("value", ""))
                out["unit"] = card.get("unit") or "元"
        except Exception as e:
            out["status"] = "partial"
            out["balance_error"] = str(e)
        try:
            ec = ecode.fetch(self.sess)
            ec_name = ec.pop("name", "")
            if not out.get("name") and ec_name:
                out["name"] = ec_name
            ec.pop("unit", None)
            out.update(ec)
        except Exception as e:
            out["status"] = "partial"
            msg = str(e)
            if "登录失败" in msg:
                msg = "登录已失效，请重新登录后刷新"
            out["qr_error"] = msg
        out["updated_at"] = int(dt.datetime.now().timestamp() * 1000)
        return out

    def card_qr(self) -> dict:
        if self.demo:
            d = mock.card_info()
            return {"status": "ok", "qr": d.get("qr"), "valid_until": d.get("valid_until"),
                    "gate": d.get("gate")}
        try:
            ec = ecode.fetch(self.sess)
            return {"status": "ok", "qr": ec.get("qr"), "valid_until": ec.get("valid_until"),
                    "gate": ec.get("gate")}
        except Exception as e:
            msg = str(e)
            if "登录失败" in msg:
                msg = "登录已失效，请重新登录后刷新"
            return {"status": "error", "error": msg}

    def mail_info(self) -> dict:
        if self.demo:
            return mock.mail_info()
        try:
            out = mail.fetch(self.sess)
            try:
                cards = self.portal.data_cards()
                card = next((c for c in cards if c.get("key") == "mail.coremailStudent"), None)
                if card:
                    out["portal_unread"] = str(card.get("value", ""))
            except Exception:
                pass
            return out
        except Exception as e:
            return {"status": "partial", "items": [], "unread": 0, "error": str(e)}

    def mail_detail(self, mid: str) -> dict:
        if self.demo:
            return mock.mail_detail(mid)
        try:
            return mail.fetch_detail(self.sess, mid)
        except Exception as e:
            return {"status": "error", "error": str(e)}

    def pay_card(self, amount: float) -> dict:
        if self.demo:
            raise RuntimeError("演示模式不充值")
        return pay.create_card(self.sess, amount)

    def pay_net(self, amount: float) -> dict:
        if self.demo:
            raise RuntimeError("演示模式不充值")
        no = self.st.profile.get("xgh") or self.st.profile.get("username") or ""
        return pay.create_net(self.sess, amount, no)

    def pay_status(self, order_no: str) -> dict:
        if self.demo:
            return {"status": "unknown", "payflag": ""}
        return pay.order_status(self.sess, order_no)

    def jwxt_status(self, probe: bool = False) -> dict:
        if self.demo:
            return {"status": "ok", "term": {}, "exams": 0, "grades": 0}
        try:
            self.jwxt.invalidate()
            if not self.jwxt.ensure():
                return {"status": "need_login", "note": "教务未登录（需校园网/WebVPN）"}
            term = self.jwxt.current_term()
            grades = self.jwxt.grades()
            return {"status": "ok", "mode": "webvpn" if self.jwxt.webvpn else "direct",
                    "term": term,
                    "exams": len(self.jwxt.exams()),
                    "grades": len(grades.get("items") or []),
                    "rank": grades.get("rank") or {}}
        except SessionExpired as e:
            return {"status": "need_login", "note": str(e)}
        except Exception as e:
            return {"status": "error", "note": str(e)}

    def overview(self, probe: bool = False) -> dict:
        if self.demo:
            return mock.overview()
        self.jwxt.invalidate()
        out = {"profile": self.st.profile, "session": self.session_info()}
        try:
            out["schedule"] = self.schedule()
        except SessionExpired:
            out["schedule"] = {"error": "会话失效，请重新登录"}
        except Exception as e:
            out["schedule"] = {"error": str(e)}
        cards = []
        try:
            cards = self.portal.data_cards()
        except Exception:
            pass
        try:
            out["net"] = self.net.summary(cards)
        except Exception as e:
            out["net"] = {"status": "error", "note": str(e), "cards": [], "links": []}
        for c in cards:
            if c.get("key") == "card.balance" or "校园卡" in c.get("name", ""):
                out["card_balance"] = str(c.get("value", ""))
                out["card_unit"] = c.get("unit") or "元"
            if c.get("key") == "mail.coremailStudent" or "未读邮件" in c.get("name", ""):
                out["mail_unread"] = str(c.get("value", ""))
        j = {"status": "need_login", "note": ""}
        grade_cards = [c for c in cards if any(k in c.get("name", "") for k in config.GRADE_KEYWORDS)]
        exam_cards = [c for c in cards if any(k in c.get("name", "") for k in config.EXAM_KEYWORDS)]
        exam_items, grade_items, rank = [], [], {}
        try:
            if self.jwxt.ensure():
                exam_items = self.jwxt.exams()
                g = self.jwxt.grades()
                grade_items = g.get("items") or []
                rank = g.get("rank") or {}
                j = {"status": "ok", "note": ""}
            else:
                j = {"status": "need_login", "note": "教务未登录（需校园网/WebVPN）"}
        except SessionExpired as e:
            j = {"status": "need_login", "note": str(e)}
        except Exception as e:
            j = {"status": "error", "note": str(e)}
        deep = []
        try:
            deep = self.portal.deep_links()
        except Exception:
            pass

        def _links(keywords):
            out, seen = [], set()
            for l in deep:
                if any(k in l["name"] for k in keywords) and l["url"] not in seen:
                    seen.add(l["url"])
                    out.append(l)
            return out[:8]

        out["exams"] = {"status": j["status"], "note": j["note"],
                        "links": _links(config.EXAM_KEYWORDS), "items": exam_items, "cards": exam_cards}
        out["grades"] = {"status": j["status"], "note": j["note"],
                         "links": _links(config.GRADE_KEYWORDS), "items": grade_items,
                         "cards": grade_cards, "rank": rank}
        return out


def json_bytes(obj) -> bytes:
    return json.dumps(obj, ensure_ascii=False).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    ctx: Ctx = None
    server_version = "neu-lite/0.1"

    def log_message(self, fmt, *args):
        pass

    # ---- helpers ---------------------------------------------------------
    def _send(self, code: int, body: bytes, ctype: str = "application/json; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, obj, code: int = 200):
        self._send(code, json_bytes(obj))

    def _file(self, body: bytes, filename: str, ctype: str):
        from urllib.parse import quote
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Disposition",
                         "attachment; filename=\"%s\"; filename*=UTF-8''%s" % (filename.encode("ascii", "ignore").decode() or "export.csv", quote(filename)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {}

    @staticmethod
    def _sso_allowed(target: str | None) -> bool:
        if not target:
            return False
        try:
            u = urlparse(target)
        except ValueError:
            return False
        host = (u.hostname or "").lower()
        return u.scheme == "https" and (host == "neu.edu.cn" or host.endswith(config.ALLOWED_SSO_SUFFIX))

    def _static(self, path: str):
        rel = path.lstrip("/") or "index.html"
        full = os.path.abspath(os.path.join(config.WEB_DIR, rel))
        if not full.startswith(os.path.abspath(config.WEB_DIR)) or not os.path.isfile(full):
            return self._send(404, b"not found", "text/plain; charset=utf-8")
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        with open(full, "rb") as f:
            self._send(200, f.read(), ctype + ("; charset=utf-8" if ctype.startswith("text/") else ""))

    # ---- routes ----------------------------------------------------------
    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        try:
            with self.ctx.lock:
                if p == "/api/session":
                    return self._json(self.ctx.session_info())
                if p == "/api/overview":
                    return self._json(self.ctx.overview(probe=q.get("probe") == "1"))
                if p == "/api/schedule":
                    return self._json(self.ctx.schedule(q.get("start")))
                if p == "/api/net":
                    return self._json(self.ctx.net_info())
                if p == "/api/ipgw":
                    return self._json(self.ctx.ipgw_usage())
                if p == "/api/card":
                    return self._json(self.ctx.card_info())
                if p == "/api/card/qr":
                    return self._json(self.ctx.card_qr())
                if p == "/api/mail":
                    return self._json(self.ctx.mail_info())
                if p == "/api/mail/detail":
                    return self._json(self.ctx.mail_detail(q.get("id", "")))
                if p == "/api/pay/order":
                    return self._json(self.ctx.pay_status(q.get("order_no", "")))
                if p == "/api/jwxt":
                    return self._json(self.ctx.jwxt_status(probe=q.get("probe") == "1"))
                if p == "/api/export":
                    body = state_mod.export_bytes(self.ctx.st)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Disposition", 'attachment; filename="neu-lite-state.json"')
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if p == "/api/mock":
                    return self._json(mock.overview())
                if p == "/api/export/exams.csv":
                    if self.ctx.demo:
                        return self._json({"error": "演示模式不导出"}, 400)
                    term = self.ctx.jwxt.current_term().get("term_name", "")
                    body = exports.exams_csv(self.ctx.jwxt.exams(), term)
                    return self._file(body, "考试安排.csv", "text/csv; charset=utf-8")
                if p == "/api/export/timetable.csv":
                    if self.ctx.demo:
                        return self._json({"error": "演示模式不导出"}, 400)
                    tt = self.ctx.jwxt.timetable()
                    body = exports.timetable_csv(tt.get("courses") or [], tt.get("term_name", ""))
                    profile = self.ctx.st.profile or {}
                    base = (str(profile.get("name") or "") + str(profile.get("xgh") or "")).strip() or "课表"
                    return self._file(body, base + ".csv", "text/csv; charset=utf-8")
                if p == "/sso":
                    target = q.get("url") or config.SSO_SERVICES.get(q.get("target", ""))
                    if not self._sso_allowed(target):
                        return self._json({"error": "不允许的跳转目标"}, 400)
                    url = None
                    if target and "jwxt.neu.edu.cn" in target and self.ctx.jwxt.ensure() \
                            and self.ctx.jwxt.webvpn:
                        url = self.ctx.jwxt.webvpn_base + "/jwapp/sys/homeapp/index.do"
                    if not url:
                        url = self.ctx.jwxt.sso_login_url(target)
                    if not url:
                        return self._json({"error": "无法获取免登地址（未登录或 TGT 失效）"}, 400)
                    self.send_response(302)
                    self.send_header("Location", url)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if p == "/api/sso":
                    target = q.get("url") or config.SSO_SERVICES.get(q.get("target", ""))
                    if not self._sso_allowed(target):
                        return self._json({"error": "不允许的跳转目标"}, 400)
                    return self._json({"url": self.ctx.jwxt.sso_login_url(target)})
                if p.startswith("/api/"):
                    return self._json({"error": "not found"}, 404)
                return self._static(p)
        except Exception as e:  # noqa: BLE001
            return self._json({"error": str(e)}, 500)

    def do_POST(self):
        u = urlparse(self.path)
        p = u.path
        body = self._read_json()
        try:
            with self.ctx.lock:
                if p == "/api/login":
                    if self.ctx.demo:
                        return self._json({"status": "ok", "demo": True})
                    try:
                        if body.get("quick"):
                            r = self.ctx.auth.quick_login()
                        else:
                            r = self.ctx.auth.login(body.get("username", ""), body.get("password", ""),
                                                    remember=bool(body.get("remember")))
                        return self._json(r)
                    except NeedSms:
                        return self._json({"status": "sms", "note": "需要短信验证码"})
                if p == "/api/sms":
                    if self.ctx.demo:
                        return self._json({"status": "ok", "demo": True})
                    if body.get("send"):
                        return self._json(self.ctx.auth.send_sms())
                    return self._json(self.ctx.auth.verify_sms(body.get("code", ""), bool(body.get("trust"))))
                if p == "/api/logout":
                    if not self.ctx.demo:
                        self.ctx.auth.logout()
                        self.ctx.reload()
                    return self._json({"status": "ok"})
                if p == "/api/pay/card":
                    try:
                        return self._json(self.ctx.pay_card(float(body.get("amount") or 0)))
                    except Exception as e:
                        return self._json({"error": str(e)}, 400)
                if p == "/api/pay/net":
                    try:
                        return self._json(self.ctx.pay_net(float(body.get("amount") or 0)))
                    except Exception as e:
                        return self._json({"error": str(e)}, 400)
                if p == "/api/import":
                    st = state_mod.import_bytes(json_bytes(body).encode())
                    state_mod.save(st)
                    self.ctx.reload()
                    return self._json({"status": "ok", "profile": st.profile})
                if p == "/api/net/url":
                    url = str(body.get("url") or "")
                    self.ctx.st.endpoints.setdefault("netfee", {})["url"] = url
                    state_mod.save(self.ctx.st)
                    return self._json({"status": "ok"})
                if p == "/api/check":
                    try:
                        self.ctx.auth.check()
                        return self._json({"status": "ok"})
                    except (SessionExpired, ApiError) as e:
                        return self._json({"status": "expired", "error": str(e)})
                return self._json({"error": "not found"}, 404)
        except ApiError as e:
            return self._json({"error": str(e), "code": e.code}, 400)
        except Exception as e:  # noqa: BLE001
            return self._json({"error": str(e)}, 500)


def serve(host: str = "127.0.0.1", port: int = 8765, demo: bool = False, open_browser: bool = False):
    Handler.ctx = Ctx(demo=demo)
    httpd = ThreadingHTTPServer((host, port), Handler)
    url = f"http://{host}:{port}/"
    print(f"neu-lite 已启动: {url}" + ("  (演示模式)" if demo else ""))
    if open_browser:
        import threading
        import webbrowser
        threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    print("按 Ctrl+C 停止")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
