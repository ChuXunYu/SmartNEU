package com.neulite.app;

import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** JS -> Java 的同步桥。方法在 WebView 的 JavaBridge 后台线程执行，可做网络请求。 */
public class Bridge {
    private final MainActivity act;
    private final Api api;

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

    @JavascriptInterface
    public String call(String action, String payload) {
        JSONObject p;
        try { p = new JSONObject(payload == null || payload.isEmpty() ? "{}" : payload); }
        catch (Exception e) { p = new JSONObject(); }
        try {
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
                case "sms":
                    if (p.optBoolean("send")) return api.sendSms().toString();
                    return api.verifySms(p.optString("code"), p.optBoolean("trust")).toString();
                case "logout":
                    api.logout();
                    return ok();
                case "overview":
                    return api.overview(p.optBoolean("probe")).toString();
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
                case "jwxt": {
                    return api.jwxtStatus().toString();
                }
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

    @JavascriptInterface
    public String exportState() {
        return act.exportState();
    }

    @JavascriptInterface
    public String exportCsv(String kind) {
        return act.exportCsv(kind);
    }

    @JavascriptInterface
    public void importState() {
        act.requestImport();
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
