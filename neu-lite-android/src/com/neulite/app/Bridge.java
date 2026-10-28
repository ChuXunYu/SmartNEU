package com.neulite.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** JS -> Java 桥：同步方法用于本地快速读取；网络类方法走 callAsync，避免阻塞 JS/UI。 */
public class Bridge {
    private final MainActivity act;
    private final Api api;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // 邮箱列表/正文单独排队，避免 27 封正文预取阻塞总览、课表、网费等主请求
    private final ExecutorService mailWorker = Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();

    public Bridge(MainActivity act, Api api) {
        this.act = act;
        this.api = api;
    }

    private static String ok() {
        return "{\"status\":\"ok\"}";
    }

    private static String err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("error", msg == null ? "未知错误" : msg); } catch (Exception ignored) {}
        return o.toString();
    }

    private static JSONObject parse(String payload) {
        try {
            return new JSONObject(payload == null || payload.isEmpty() ? "{}" : payload);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 同步调用：只用于 session 等本地瞬时操作。 */
    @JavascriptInterface
    public String call(String action, String payload) {
        return run(action, parse(payload));
    }

    /**
     * 异步调用：立刻返回，网络请求在工作线程执行，完成后通过
     * window.__neuAsync(id, json) 回调 JS。JS 线程全程不被阻塞。
     */
    @JavascriptInterface
    public void callAsync(String action, String payload, String id) {
        final JSONObject p = parse(payload);
        final String requestId = id == null ? "" : id;
        ExecutorService target = ("mail".equals(action) || "mailDetail".equals(action)
                || "sendMail".equals(action)) ? mailWorker : worker;
        target.execute(() -> {
            String out;
            try {
                out = run(action, p);
            } catch (Throwable t) {
                out = err(t.getMessage());
            }
            if (!requestId.isEmpty()) results.put(requestId, out);
        });
    }

    /** JS 轮询取结果；立即返回，不阻塞 UI。 */
    @JavascriptInterface
    public String poll(String id) {
        if (id == null) return "";
        String v = results.remove(id);
        return v == null ? "" : v;
    }

    private String run(String action, JSONObject p) {
        try {
            return dispatch(action, p);
        } catch (Api.SessionExpired e) {
            return "{\"error\":\"会话失效，请重新登录\",\"code\":10013}";
        } catch (Api.ApiException e) {
            JSONObject o = new JSONObject();
            try {
                o.put("error", e.getMessage() == null ? "请求失败" : e.getMessage());
                o.put("code", e.code);
            } catch (Exception ignored) {}
            return o.toString();
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    private String dispatch(String action, JSONObject p) throws Exception {
        switch (action) {
            case "session":
                return api.sessionInfo().toString();
            case "login":
                try {
                    return api.login(p.optString("username"), p.optString("password"),
                            p.optBoolean("remember")).toString();
                } catch (Api.ApiException e) {
                    if (e.code == 3) {
                        JSONObject o = new JSONObject();
                        o.put("status", "sms");
                        return o.toString();
                    }
                    throw e;
                }
            case "quickLogin":
                try {
                    return api.quickLogin().toString();
                } catch (Api.ApiException e) {
                    if (e.code == 3) {
                        JSONObject o = new JSONObject();
                        o.put("status", "sms");
                        return o.toString();
                    }
                    throw e;
                }
            case "sms":
                if (p.optBoolean("send")) return api.sendSms().toString();
                return api.verifySms(p.optString("code"), p.optBoolean("trust")).toString();
            case "logout":
                api.logout();
                return ok();
            case "overview":
                return api.overview(p.optBoolean("probe")).toString();
            case "card":
                return api.cardInfo().toString();
            case "cardQr":
                return api.cardQr().toString();
            case "mail":
                return api.mailInfo().toString();
            case "mailDetail":
                return api.mailDetail(p.optString("id")).toString();
            case "sendMail": {
                String password = p.optString("password");
                if (password.isEmpty()) password = act.getMailPassword();
                if (password.isEmpty()) throw new Exception("请先填写客户端专用密码");
                if (!p.optString("password").isEmpty()) act.saveMailPassword(p.optString("password"));
                SmtpClient.send(p.optString("from"), password,
                        p.optString("to"), p.optString("subject"), p.optString("body"),
                        act.readAttachments());
                return "{\"status\":\"ok\"}";
            }
            case "pickAttachments":
                act.pickAttachments();
                return ok();
            case "attachments":
                return act.attachmentsJson();
            case "clearAttachments":
                act.clearAttachments();
                return ok();
            case "hasMailPassword":
                return "{\"saved\":" + act.hasMailPassword() + "}";
            case "saveMailPassword":
                act.saveMailPassword(p.optString("password"));
                return ok();
            case "clearMailPassword":
                act.clearMailPassword();
                return ok();
            case "payCard":
                return api.createCardOrder(p.optDouble("amount", 0)).toString();
            case "payNet":
                return api.createNetOrder(p.optDouble("amount", 0)).toString();
            case "payOrder":
                return api.payOrderStatus(p.optString("order_no")).toString();
            case "openPay":
                act.openPay(p.optString("url"), api.webViewCookies());
                return ok();
            case "openWeb":
                act.openWeb(p.optString("url"), p.optString("title", "网页"), api.webViewCookies());
                return ok();
            case "schedule": {
                String start = p.optString("start", "");
                return api.schedule(start.isEmpty() ? null : start).toString();
            }
            case "net": {
                JSONArray cards = api.cards();
                JSONArray net = new JSONArray();
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject c = cards.optJSONObject(i);
                    if (c != null && Api.containsAny(c.optString("name", ""), Api.NET_KEYS)) net.put(c);
                }
                JSONObject o = new JSONObject();
                o.put("status", net.length() > 0 ? "ok" : "unavailable");
                o.put("cards", net);
                o.put("links", Api.filterLinks(api.deepLinks(), Api.NET_KEYS));
                o.put("note", net.length() > 0 ? "" : "门户未返回网费卡片");
                return o.toString();
            }
            case "jwxt":
                return api.jwxtStatus().toString();
            case "ipgw": {
                JSONObject u = api.ipgwUsage();
                return (u == null ? new JSONObject() : u).toString();
            }
            case "sso": {
                String t = api.ssoUrl(p.optString("target"));
                JSONObject o = new JSONObject();
                o.put("url", t);
                return o.toString();
            }
            case "check":
                return api.check().toString();
            default:
                return err("未知操作: " + action);
        }
    }

    @JavascriptInterface
    public String exportState(String password) {
        return act.exportState(password);
    }

    @JavascriptInterface
    public String exportEncrypted(String kind, String password) {
        return act.exportEncrypted(kind, password);
    }

    @JavascriptInterface
    public String exportCsv(String kind) {
        return act.exportCsv(kind);
    }

    @JavascriptInterface
    public String exportPlainCsvAll() {
        return act.exportPlainCsvAll();
    }

    @JavascriptInterface
    public void importState(String password) {
        act.requestImport(password);
    }

    @JavascriptInterface
    public String openExports() {
        act.openExports();
        return "{\"status\":\"ok\"}";
    }

    @JavascriptInterface
    public String clearWebCache() {
        act.clearWebCache();
        return "{\"status\":\"ok\"}";
    }

    @JavascriptInterface
    public void openSso(String target) {
        act.openSso(target);
    }

    @JavascriptInterface
    public void openUrl(String url) {
        act.openExternal(url);
    }
}
