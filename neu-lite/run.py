#!/usr/bin/env python3
"""neu-lite 命令行入口。

  python run.py serve --demo          # 演示模式（无需账号）
  python run.py login                 # 登录（需要时自动走短信验证）
  python run.py serve                 # 启动本地 Web
  python run.py export state.json     # 导出可移植状态
  python run.py import state.json     # 新设备导入（保持同一设备指纹）
  python run.py probe                 # 探测新教务系统模块
"""
from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from neulite import config, crypto, records, state as state_mod  # noqa: E402
from neulite.auth import Auth, NeedSms  # noqa: E402
from neulite.jwxt import Jwxt  # noqa: E402
from neulite.netfee import NetFee  # noqa: E402
from neulite.portal import Portal  # noqa: E402
from neulite.server import serve  # noqa: E402
from neulite.session import ApiError, ApiSession, SessionExpired  # noqa: E402


def build():
    st = state_mod.load()
    sess = ApiSession(st)
    return st, sess, Auth(st, sess)


def cmd_login(args):
    st, sess, auth = build()
    username = args.username or input("学号: ").strip()
    password = args.password or input("密码: ").strip()
    try:
        res = auth.login(username, password, remember=args.remember)
    except NeedSms:
        print("需要短信二次验证")
        print("已请求发送验证码:", auth.send_sms())
        code = input("验证码: ").strip()
        res = auth.verify_sms(code, trust_device=args.trust_device)
    print("登录成功:", json.dumps(res.get("profile"), ensure_ascii=False))
    print("状态已保存:", config.STATE_FILE)
    print("设备指纹 imei:", st.imei)
    print("提示：把 state.json 复制到新设备即可免二次验证。")


def cmd_check(args):
    st, sess, auth = build()
    try:
        print("会话有效:", json.dumps(auth.check(), ensure_ascii=False))
    except (SessionExpired, ApiError) as e:
        print("会话失效:", e)
        return 1
    return 0


def cmd_logout(args):
    st, sess, auth = build()
    auth.logout()
    print("已登出（设备指纹保留）")


def cmd_export(args):
    st, _, _ = build()
    path = args.path
    with open(path, "wb") as f:
        f.write(state_mod.export_bytes(st))
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    print("已导出:", path)


def cmd_import(args):
    with open(args.path, "rb") as f:
        st = state_mod.import_bytes(f.read())
    state_mod.save(st)
    print("已导入:", config.STATE_FILE)
    print("设备指纹 imei:", st.imei)
    print("登录状态:", "已登录" if st.ticket else "未登录（需重新登录一次）")


def cmd_serve(args):
    serve(host=args.host, port=args.port, demo=args.demo, open_browser=args.open)


def cmd_probe(args):
    st, sess, auth = build()
    j = Jwxt(sess, st)
    try:
        if not j.ensure():
            print("教务未登录（需要校园网/WebVPN）")
            return 1
        term = j.current_term()
        exams = j.exams()
        grades = j.grades()
        tt = j.timetable()
        out = {
            "term": term,
            "counts": {"exams": len(exams), "grades": len(grades.get("items") or []),
                       "courses": len(tt.get("courses") or [])},
            "rank": grades.get("rank") or {},
            "sample": {"exam": (exams or [None])[0],
                       "grade": ((grades.get("items") or [None])[0])},
        }
        print(json.dumps(out, ensure_ascii=False, indent=2)[:4000])
        path = os.path.join(os.path.dirname(config.STATE_FILE), "probe.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, indent=2)
        print("\n已保存:", path)
        return 0
    except SessionExpired as e:
        print("会话失效:", e)
        return 1


def cmd_schedule(args):
    st, sess, _ = build()
    j = Jwxt(sess, st)
    if st.ticket and j.ensure():
        print(json.dumps(j.week_events(), ensure_ascii=False, indent=2))
    else:
        print(json.dumps(Portal(sess).schedule(), ensure_ascii=False, indent=2))


def cmd_apps(args):
    st, sess, _ = build()
    print(json.dumps(Portal(sess).apps(), ensure_ascii=False, indent=2))


def cmd_cards(args):
    st, sess, _ = build()
    print(json.dumps(Portal(sess).data_cards(), ensure_ascii=False, indent=2))


def cmd_net(args):
    st, sess, _ = build()
    print(json.dumps(NetFee(sess, st, Portal(sess)).summary(), ensure_ascii=False, indent=2))


def cmd_selftest(args):
    # 加密自检（不联网）：用私钥导出的公钥做往返，验证分块/填充实现
    pub_from_priv = crypto.PRIV.public_key()
    data = b"neu-lite" * 40
    assert crypto.decrypt(crypto.encrypt_with(pub_from_priv, data)) == data
    print("[OK] RSA 信封自检通过（117B 分块 / PKCS#1 v1.5）")
    # 记录归一化自检
    exam_demo = {"datas": {"rows": [{"kcmc": "高等数学", "kssj": "2026-11-05 09:00",
                                     "ksdd": "何世礼201", "zwh": "A-01"}]}}
    grade_demo = {"data": {"list": [{"kcmc": "高等数学", "cj": "92", "xf": "6.0"}]}}
    e, g = records.exams(exam_demo), records.grades(grade_demo)
    assert e and e[0]["course"] == "高等数学" and e[0]["place"] == "何世礼201"
    assert g and g[0]["score"] == "92"
    print("[OK] 教务记录归一化自检通过（考试/成绩）")
    st = state_mod.load()
    print("[OK] 状态文件:", config.STATE_FILE, "| imei:", st.imei, "| 登录:", bool(st.ticket))


def main():
    if sys.platform == "win32":
        try:
            os.system("chcp 65001 >nul")
            for stream in (sys.stdout, sys.stderr):
                stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    if len(sys.argv) == 1:          # 双击 exe：直接起服务并打开浏览器
        sys.argv += ["serve", "--open"]
    ap = argparse.ArgumentParser(prog="neu-lite")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("serve", help="启动本地 Web")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--demo", action="store_true", help="演示数据，无需登录")
    p.add_argument("--open", action="store_true", help="启动后自动打开浏览器")
    p.set_defaults(func=cmd_serve)

    p = sub.add_parser("login", help="登录")
    p.add_argument("--username")
    p.add_argument("--password")
    p.add_argument("--remember", action="store_true", help="保存账号密码以自动续期")
    p.add_argument("--trust-device", action="store_true")
    p.set_defaults(func=cmd_login)

    p = sub.add_parser("check", help="校验会话")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("logout", help="登出")
    p.set_defaults(func=cmd_logout)

    p = sub.add_parser("export", help="导出状态（可移植）")
    p.add_argument("path")
    p.set_defaults(func=cmd_export)

    p = sub.add_parser("import", help="导入状态")
    p.add_argument("path")
    p.set_defaults(func=cmd_import)

    p = sub.add_parser("probe", help="探测新教务系统")
    p.set_defaults(func=cmd_probe)

    p = sub.add_parser("schedule", help="打印课表 JSON")
    p.set_defaults(func=cmd_schedule)

    p = sub.add_parser("apps", help="打印门户应用列表")
    p.set_defaults(func=cmd_apps)

    p = sub.add_parser("cards", help="打印门户数据卡片")
    p.set_defaults(func=cmd_cards)

    p = sub.add_parser("net", help="打印网费/流量")
    p.set_defaults(func=cmd_net)

    p = sub.add_parser("selftest", help="离线自检")
    p.set_defaults(func=cmd_selftest)

    args = ap.parse_args()
    rc = args.func(args)
    sys.exit(rc or 0)


if __name__ == "__main__":
    main()
