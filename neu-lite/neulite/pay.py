"""缴费平台（校园卡/网费）下单与订单查询。"""
from __future__ import annotations

PAY = "https://pay.neu.edu.cn"


def _post(s, path: str, data: dict, referer: str) -> dict:
    r = s.post(PAY + path, data=data, timeout=25, headers={"Referer": PAY + referer})
    r.raise_for_status()
    try:
        return r.json()
    except ValueError:
        raise RuntimeError("缴费平台返回非 JSON")


def login(sess) -> None:
    if getattr(sess, "_pay_ok", False):
        return
    r = sess.s.get(PAY + "/drCasLogin", timeout=25, allow_redirects=True)
    if not r.url.startswith(PAY):
        raise RuntimeError("缴费平台登录失败")
    sess._pay_ok = True


def _order(j: dict, project: str) -> dict:
    if j.get("returncode") != "SUCCESS":
        raise RuntimeError(j.get("returnmsg") or "下单失败")
    tr = j.get("payOrderTrade") or {}
    oid = int(tr.get("id") or 0)
    return {
        "status": "ok",
        "project": project,
        "order_id": oid,
        "order_no": tr.get("orderno", ""),
        "pay_order_no": j.get("payorderno", ""),
        "amount": f"{int(tr.get('orderamt') or 0) / 100:.2f}",
        "url": f"{PAY}/showPayType25{oid}.html",
    }


def create_card(sess, amount: float) -> dict:
    if not 0.01 <= amount <= 1000:
        raise RuntimeError("金额需在 0.01–1000 元之间")
    login(sess)
    j = _post(sess.s, "/scardRechargeCreateOrder", {
        "payAmt": f"{amount:.2f}", "payProjectId": "264", "factorycode": "Z006",
        "rechargeType": "1", "recharge_idserial": "",
    }, "/scardrecharge51264Z006.html")
    return _order(j, "card")


def create_net(sess, amount: float, net_no: str) -> dict:
    if not 0.01 <= amount <= 1000:
        raise RuntimeError("金额需在 0.01–1000 元之间")
    login(sess)
    b = _post(sess.s, "/queryNetAccBalance", {
        "netNo": net_no, "netType": "1", "factorycode": "N005",
    }, "/netdetails51247N005.html")
    if b.get("returncode") != "SUCCESS":
        raise RuntimeError(b.get("returnmsg") or "网费余额查询失败")
    j = _post(sess.s, "/netPayprojectCreateOrder", {
        "netNo": net_no, "netType": "1", "payAmt": f"{amount:.2f}",
        "netBalance": b.get("netaccbal", ""), "payProjectId": "247", "factorycode": "N005",
    }, "/netdetails51247N005.html")
    out = _order(j, "net")
    out["net_balance"] = b.get("netaccbal", "")
    return out


def order_status(sess, order_no: str) -> dict:
    login(sess)
    j = _post(sess.s, "/queryOrderList", {"page": "1", "rows": "50", "payflag": ""}, "/orders.html")
    for x in j.get("payOrderList") or []:
        if x.get("orderno") == order_no:
            return {"status": "ok", "payflag": str(x.get("payflag", "")),
                    "amount": f"{int(x.get('orderamt') or 0) / 100:.2f}",
                    "create_time": x.get("createtime", "")}
    return {"status": "unknown", "payflag": ""}
