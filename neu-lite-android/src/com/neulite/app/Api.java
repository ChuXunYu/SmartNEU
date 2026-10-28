package com.neulite.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 登录协议、门户数据、教务适配。全部同步执行，供 JS Bridge 在后台线程调用。 */
public class Api {
    static final String BASE = "https://personal.neu.edu.cn";
    static final String PORTAL = BASE + "/portal";
    static final String COMMIT = PORTAL + "/manage/common/app_login/commit";
    static final String CHECK = PORTAL + "/manage/common/app_login/check";
    static final String SMS_SEND = PORTAL + "/manage/common/app_login/smsSend";
    static final String SMS_VERIFY = PORTAL + "/manage/common/app_login/verifyCommit";
    static final String LOGOUT = PORTAL + "/manage/common/app_login/logout";
    static final String GET_LAYOUT = PORTAL + "/appsetting/frontend/default/getLayout";
    static final String ITEMS_APP = PORTAL + "/personal/frontend/data/items_app";
    static final String DETAIL_APP = PORTAL + "/personal/frontend/data/detail_app";
    static final String APP_WEEK = PORTAL + "/schedule/frontend/default/appWeek";
    static final String JWXT = "https://jwxt.neu.edu.cn";
    static final String WV = "https://webvpn.neu.edu.cn";
    static final String WV_LOGIN = WV + "/login?cas_login=true";
    static final String WV_KEY = "b0A58a69394ce73@";
    static final String JWXT_HOME = JWXT + "/jwapp/sys/homeapp/index.do";
    static final String JW = JWXT + "/jwapp/sys";
    static final String JW_CURRENT_USER = JW + "/homeapp/api/home/currentUser.do";
    static final String JW_TIMETABLE = JW + "/kbapp/api/wdkbcx/getMyScheduleDetail.do";
    static final String JW_EXAMS = JW + "/wdkwapp/api/wdks/queryMyExamArrangeMent.do";
    static final String JW_GRADES = JW + "/cjzhcxapp/modules/wdcj/cxwdcj.do";
    static final String JW_RANK = JW + "/cjzhcxapp/api/wdcj/queryPjxfjd.do";
    static final String JWXT_MENU = JW + "/homeapp/api/home/getMenuList.do";
    static final String CAS_LOGIN = "https://pass.neu.edu.cn/tpass/login";
    static final String UA = "ZhilinEai ZhilinNeuApp/3.1.8 25102RKBEC Android 16";
    static final String[] NET_KEYS = {"网费", "流量", "上网", "校园网", "网络余额", "网络"};
    static final String[] EXAM_KEYS = {"考试", "考务"};
    static final String[] GRADE_KEYS = {"成绩", "排名", "绩点"};

    public static class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String msg) { super(msg); this.code = code; }
    }
    public static class SessionExpired extends ApiException {
        public SessionExpired() { super(10013, "会话失效"); }
    }

    private final SharedPreferences sp;
    JSONObject st;
    private final CookieManager cookies;
    private boolean jwxtOk = false;
    private boolean webvpn = false;
    private boolean webvpnAuthed = false;
    private String webvpnBase = "";
    private JSONObject ttCache, termCache;
    private long ttAt = 0, termAt = 0;
    private String ttTerm = "";
    private JSONArray linksCache;
    private long linksAt = 0;

    public Api(Context ctx) {
        sp = ctx.getSharedPreferences("neu", Context.MODE_PRIVATE);
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookies);
        load();
        syncCookies(true);
        webvpn = false; // 每个进程先尝试直连，避免带着上次的“校外模式”在校园网里绕 WebVPN
        webvpnBase = "";
    }

    // ---------------------------------------------------------------- state
    void load() {
        try {
            st = new JSONObject(sp.getString("state", "{}"));
        } catch (Exception e) {
            st = new JSONObject();
        }
        if (str(st, "imei").isEmpty()) {
            try { st.put("imei", UUID.randomUUID().toString()); } catch (Exception ignored) {}
        }
        if (!st.has("profile")) put(st, "profile", new JSONObject());
        if (!st.has("endpoints")) put(st, "endpoints", new JSONObject());
        if (!st.has("cookies")) put(st, "cookies", new JSONArray());
    }

    public void save() {
        try { st.put("updated_at", nowStr()); } catch (Exception ignored) {}
        snapshotCookies();
        sp.edit().putString("state", st.toString()).apply();
    }

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    String ticket() { return str(st, "ticket"); }
    String imei() { return str(st, "imei"); }

    private void syncCookies(boolean clear) {
        if (clear) cookies.getCookieStore().removeAll();
        String t = ticket();
        if (!t.isEmpty()) {
            addCookie("CASTGC", t, ".neu.edu.cn", "/");
            addCookie("CASTGC", t, "pass.neu.edu.cn", "/tpass/");
        }
        String lc = str(st, "ck_lc");
        String vl = str(st, "ck_vl");
        if (!lc.isEmpty()) addCookie("CK_LC", lc, "personal.neu.edu.cn", "/");
        if (!vl.isEmpty()) addCookie("CK_VL", vl, "personal.neu.edu.cn", "/");
        JSONArray arr = st.optJSONArray("cookies");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                addCookie(c.optString("name"), c.optString("value"),
                        c.optString("domain"), c.optString("path", "/"));
            }
        }
    }

    private void addCookie(String name, String value, String domain, String path) {
        if (name == null || name.isEmpty()) return;
        try {
            HttpCookie c = new HttpCookie(name, value);
            c.setDomain(domain);
            c.setPath(path == null || path.isEmpty() ? "/" : path);
            String host = domain.startsWith(".") ? domain.substring(1) : domain;
            cookies.getCookieStore().add(new URI("https://" + host), c);
        } catch (Exception ignored) {}
    }

    private void snapshotCookies() {
        JSONArray arr = new JSONArray();
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            JSONObject o = new JSONObject();
            put(o, "name", c.getName());
            put(o, "value", c.getValue());
            put(o, "domain", c.getDomain());
            put(o, "path", c.getPath());
            arr.put(o);
        }
        put(st, "cookies", arr);
    }

    // ----------------------------------------------------------------- http
    static String nowStr() {
        Calendar c = Calendar.getInstance();
        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    private static String authHeader(String ticket) throws Exception {
        long ts = System.currentTimeMillis() / 1000L;
        JSONObject inner = new JSONObject();
        inner.put("timestamp", String.valueOf(ts));
        inner.put("ticket", ticket == null ? "" : ticket);
        return Crypto.encrypt(inner.toString().getBytes(StandardCharsets.UTF_8));
    }

    JSONObject envelope(String url, JSONObject payload, String ticket) throws Exception {
        String body = "content=" + URLEncoder.encode(
                Crypto.encrypt(payload.toString().getBytes(StandardCharsets.UTF_8)), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept-Language", "zh-Hans-CN;q=1, en-CN;q=0.9");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("from-eai", "1");
        conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
        conn.setRequestProperty("authorization-str", authHeader(ticket));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        String text = readAll(conn);
        JSONObject env = new JSONObject(text);
        Object eo = env.opt("e");
        int code;
        if (eo instanceof Number) {
            code = ((Number) eo).intValue();
        } else {
            try {
                code = (int) Double.parseDouble(String.valueOf(eo));
            } catch (Exception ex) {
                code = -1;
            }
        }
        if (code == 10013) throw new SessionExpired();
        if (code != 0) throw new ApiException(code, str(env, "m"));
        JSONObject out = new JSONObject();
        out.put("e", code);
        out.put("m", str(env, "m"));
        Object d = env.opt("d");
        if (d instanceof String && !((String) d).isEmpty()) {
            try {
                out.put("d", new JSONObject(new String(Crypto.decrypt((String) d), StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                out.put("d", d);
            }
        } else if (d != null && d != JSONObject.NULL) {
            out.put("d", d);
        } else {
            out.put("d", new JSONObject());
        }
        return out;
    }

    static String readAll(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    JSONObject call(String url, JSONObject payload) throws Exception {
        String ticket = ticket();
        try {
            return envelope(url, payload, ticket);
        } catch (SessionExpired e) {
            if (relogin()) {
                return envelope(url, payload, ticket());
            }
            throw e;
        }
    }

    public JSONObject quickLogin() throws Exception {
        JSONObject rem = st.optJSONObject("remember");
        if (rem == null) throw new ApiException(-1, "本机没有保存账号密码");
        String u = str(rem, "username");
        String p = str(rem, "password");
        if (u.isEmpty() || p.isEmpty()) throw new ApiException(-1, "本机没有保存账号密码");
        return login(u, p, true);
    }

    boolean relogin() {
        JSONObject rem = st.optJSONObject("remember");
        if (rem == null) return false;
        String u = str(rem, "username");
        String p = str(rem, "password");
        if (u.isEmpty() || p.isEmpty()) return false;
        try {
            login(u, p, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------------- auth
    public JSONObject login(String user, String pass, boolean remember) throws Exception {
        JSONObject p = new JSONObject();
        p.put("username", user);
        p.put("password", pass);
        p.put("imei", imei());
        p.put("mobile_type", "android");
        JSONObject res = envelope(COMMIT, p, ticket());
        JSONObject d = res.optJSONObject("d");
        if (d == null) d = new JSONObject();
        String code = str(d, "login_code");
        if ("1".equals(code)) {
            applyLogin(d, user);
            if (remember) {
                JSONObject r = new JSONObject();
                r.put("username", user);
                r.put("password_removed", true);
                r.put("name", str(d, "name"));
                r.put("xgh", str(d, "xgh"));
                put(st, "remember", r);
            }
            save();
            JSONObject out = new JSONObject();
            out.put("status", "ok");
            out.put("profile", st.optJSONObject("profile"));
            return out;
        }
        if (!"1".equals(code) && !"3".equals(code)) {
            throw new ApiException(-1, res.optString("m", "登录失败"));
        }
        put(st, "temp_token", str(d, "login_result"));
        save();
        throw new ApiException(3, "需要短信验证码");
    }

    private void applyLogin(JSONObject d, String user) throws Exception {
        invalidateJwxtCache();
        String t = str(d, "login_ticket");
        if (t.isEmpty()) t = str(d, "login_result");
        put(st, "ticket", t);
        put(st, "ck_lc", d.optString("CK_LC", str(d, "ckLc")));
        put(st, "ck_vl", d.optString("CK_VL", str(d, "ckVl")));
        JSONObject prof = new JSONObject();
        prof.put("username", user);
        prof.put("name", str(d, "name"));
        prof.put("xgh", str(d, "xgh"));
        prof.put("role", str(d, "role"));
        prof.put("role_type", str(d, "role_type"));
        prof.put("depart", str(d, "depart"));
        prof.put("avatar", str(d, "avatar"));
        put(st, "profile", prof);
        syncCookies(false);
    }

    public JSONObject sendSms() throws Exception {
        JSONObject p = new JSONObject();
        p.put("temp_token", str(st, "temp_token"));
        p.put("imei", imei());
        return envelope(SMS_SEND, p, ticket());
    }

    public JSONObject verifySms(String code, boolean trust) throws Exception {
        JSONObject p = new JSONObject();
        p.put("temp_token", str(st, "temp_token"));
        p.put("imei", imei());
        p.put("sms_code", code);
        p.put("trust_device", trust ? "1" : "0");
        JSONObject res = envelope(SMS_VERIFY, p, ticket());
        JSONObject d = res.optJSONObject("d");
        if (d == null || !"1".equals(str(d, "login_code"))) {
            throw new ApiException(-1, res.optString("m", "验证失败"));
        }
        applyLogin(d, "");
        put(st, "temp_token", "");
        save();
        JSONObject out = new JSONObject();
        out.put("status", "ok");
        out.put("profile", st.optJSONObject("profile"));
        return out;
    }

    public JSONObject check() throws Exception {
        JSONObject p = new JSONObject();
        p.put("ticket", ticket());
        return envelope(CHECK, p, ticket());
    }

    public void logout() {
        JSONObject prof = st.optJSONObject("profile");
        JSONObject rem = st.optJSONObject("remember");
        if (rem != null && str(rem, "name").isEmpty() && prof != null) {
            try {
                rem.put("name", str(prof, "name"));
                rem.put("xgh", str(prof, "xgh"));
                put(st, "remember", rem);
            } catch (Exception ignored) {}
        }
        try {
            JSONObject p = new JSONObject();
            call(LOGOUT, p);
        } catch (Exception ignored) {}
        put(st, "ticket", "");
        put(st, "ck_lc", "");
        put(st, "ck_vl", "");
        put(st, "temp_token", "");
        put(st, "profile", new JSONObject());
        cookies.getCookieStore().removeAll();
        save();
    }

    // --------------------------------------------------------------- portal
    public JSONObject layout() throws Exception {
        return call(GET_LAYOUT, new JSONObject());
    }

    public JSONArray deepLinks() {
        if (linksCache != null && System.currentTimeMillis() - linksAt < 600000) return linksCache;
        JSONArray out = new JSONArray();
        try {
            walkLinks(layout(), out, 0);
        } catch (Exception ignored) {}
        linksCache = out;
        linksAt = System.currentTimeMillis();
        return out;
    }

    private static void walkLinks(Object obj, JSONArray out, int depth) {
        if (depth > 6 || obj == null) return;
        if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String url = first(o, "url", "link", "appUrl", "jumpUrl");
            String name = first(o, "name", "title", "appName", "menuName");
            if (url.startsWith("http") && !name.isEmpty()) {
                JSONObject it = new JSONObject();
                put(it, "name", name);
                put(it, "url", url);
                out.put(it);
            }
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                Object v = o.opt(keys.next());
                if (v instanceof JSONObject || v instanceof JSONArray) walkLinks(v, out, depth + 1);
            }
        } else if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) walkLinks(a.opt(i), out, depth + 1);
        }
    }

    static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    public JSONObject schedule(String start) throws Exception {
        if (!ticket().isEmpty() && ensureJwxt()) {
            try {
                return weekEvents(start);
            } catch (Exception ignored) {
                // 回落到门户课表
            }
        }
        Calendar cal = Calendar.getInstance();
        if (start != null && start.length() >= 10) {
            String[] p = start.split("-");
            cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        }
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun
        int offset = (dow + 5) % 7;
        cal.add(Calendar.DAY_OF_MONTH, -offset);
        Calendar end = (Calendar) cal.clone();
        end.add(Calendar.DAY_OF_MONTH, 6);
        String s = fmtDate(cal), e = fmtDate(end);
        JSONObject p = new JSONObject();
        p.put("calendar_id", "0");
        p.put("start", s);
        p.put("end", e);
        p.put("other", "0");
        JSONObject res = call(APP_WEEK, p);
        JSONArray events = new JSONArray();
        collectEvents(res.opt("d"), events, 0);
        JSONObject out = new JSONObject();
        out.put("start", s);
        out.put("end", e);
        out.put("events", events);
        return out;
    }

    static String fmtDate(Calendar c) {
        return String.format("%04d-%02d-%02d", c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static void collectEvents(Object obj, JSONArray out, int depth) {
        if (depth > 6 || obj == null) return;
        if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    JSONObject o = (JSONObject) v;
                    String title = first(o, "title", "className", "con", "code");
                    String start = first(o, "start", "start_time");
                    if (!title.isEmpty() || !start.isEmpty()) {
                        JSONObject ev = new JSONObject();
                        put(ev, "title", title);
                        put(ev, "start", start);
                        put(ev, "end", first(o, "end"));
                        put(ev, "location", first(o, "location"));
                        put(ev, "desc", first(o, "weekdesc", "weekType"));
                        out.put(ev);
                    }
                    collectEvents(v, out, depth + 1);
                } else if (v instanceof JSONArray) {
                    collectEvents(v, out, depth + 1);
                }
            }
        } else if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            Iterator<String> ks = o.keys();
            while (ks.hasNext()) {
                Object v = o.opt(ks.next());
                if (v instanceof JSONObject || v instanceof JSONArray) collectEvents(v, out, depth + 1);
            }
        }
    }

    public JSONArray cards() {
        JSONArray out = new JSONArray();
        JSONObject[] tries = new JSONObject[]{new JSONObject(), new JSONObject()};
        try { tries[1].put("type", "1"); tries[1].put("page", "1"); } catch (Exception ignored) {}
        for (JSONObject p : tries) {
            try {
                JSONObject res = call(ITEMS_APP, p);
                collectCards(res.opt("d"), out, 0);
                if (out.length() > 0) break;
            } catch (Exception ignored) {}
        }
        // 去重 + 补全 value
        JSONArray uniq = new JSONArray();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < out.length(); i++) {
            JSONObject c = out.optJSONObject(i);
            if (c == null) continue;
            String id = c.optString("id");
            if (seen.contains(id)) continue;
            seen.add(id);
            if (str(c, "value").isEmpty()) {
                try {
                    JSONObject p = new JSONObject();
                    p.put("id", id);
                    JSONObject res = call(DETAIL_APP, p);
                    JSONObject d = res.optJSONObject("d");
                    JSONObject data = d == null ? null : d.optJSONObject("data");
                    if (data != null) {
                        put(c, "value", str(data, "value"));
                        put(c, "unit", data.optString("unit", str(c, "unit")));
                    }
                } catch (Exception ignored) {}
            }
            uniq.put(c);
        }
        return uniq;
    }

    private static void collectCards(Object obj, JSONArray out, int depth) {
        if (depth > 6 || obj == null) return;
        if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    JSONObject o = (JSONObject) v;
                    String id = str(o, "id");
                    String name = first(o, "name", "title");
                    if (!id.isEmpty() && !name.isEmpty()) {
                        JSONObject c = new JSONObject();
                        put(c, "id", id);
                        put(c, "name", name);
                        put(c, "value", first(o, "value", "data"));
                        put(c, "unit", first(o, "unit"));
                        put(c, "key", first(o, "key"));
                        put(c, "url", first(o, "url"));
                        put(c, "method", first(o, "method"));
                        put(c, "class", first(o, "class"));
                        out.put(c);
                    }
                    collectCards(v, out, depth + 1);
                } else if (v instanceof JSONArray) {
                    collectCards(v, out, depth + 1);
                }
            }
        } else if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            Iterator<String> ks = o.keys();
            while (ks.hasNext()) {
                Object v = o.opt(ks.next());
                if (v instanceof JSONObject || v instanceof JSONArray) collectCards(v, out, depth + 1);
            }
        }
    }

    // ------------------------------------------------------- 校园卡 / 邮箱 / 缴费
    static final String ECODE = "https://ecode.neu.edu.cn/ecode";
    static final String MAIL_SSO = "https://mails.neu.edu.cn/coremail/cmcu_addon/dbdxsso.jsp";
    static final String MAIL_API = "https://mails.neu.edu.cn/coremail/s";
    static final String PAY = "https://pay.neu.edu.cn";
    private boolean ecodeAuthed = false;
    private String ecodeFinal = "";
    private boolean payAuthed = false;

    JSONObject httpGetJson(String url, String referer) throws Exception {
        String text = httpGetString(url, referer);
        if (!text.trim().startsWith("{")) throw new ApiException(-1, "接口返回非 JSON");
        return new JSONObject(text);
    }

    String httpGetString(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        String ck = cookieHeaderFor(new URL(url));
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        int code = c.getResponseCode();
        String text = readAll(c);
        if (code >= 400) throw new ApiException(code, "HTTP " + code);
        return text;
    }

    String httpPostForm(String url, Map<String, String> form, String referer) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), "UTF-8"));
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        String ck = cookieHeaderFor(new URL(url));
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        OutputStream os = c.getOutputStream();
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        int code = c.getResponseCode();
        String text = readAll(c);
        if (code >= 400) throw new ApiException(code, "HTTP " + code);
        return text;
    }

    private void ecodeLogin() throws Exception {
        if (ecodeAuthed) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            String[] r = httpGetFollow(ECODE + "/api/sso/login?redirect_to=/ecode", 20);
            ecodeFinal = r[0];
            if (r[0].startsWith(ECODE)) { ecodeAuthed = true; return; }
            if (attempt == 0 && relogin()) continue;
            throw new ApiException(-1, "e码通登录失败: " + r[0]);
        }
    }

    private JSONObject ecodeGet(String path) throws Exception {
        ecodeLogin();
        return httpGetJson(ECODE + path, ECODE + "/");
    }

    private static JSONObject firstAttr(JSONObject res) {
        JSONArray a = res.optJSONArray("data");
        JSONObject o = a == null ? null : a.optJSONObject(0);
        return o == null ? null : o.optJSONObject("attributes");
    }

    private JSONObject findCard(JSONArray all, String key, String namePart) {
        if (all == null) return null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject c = all.optJSONObject(i);
            if (c == null) continue;
            if (key.equals(str(c, "key")) || str(c, "name").contains(namePart)) return c;
        }
        return null;
    }

    public JSONObject cardInfo() {
        JSONObject out = new JSONObject();
        put(out, "status", "ok");
        JSONObject prof = st.optJSONObject("profile");
        if (prof == null) prof = new JSONObject();
        put(out, "name", str(prof, "name"));
        put(out, "student_id", str(prof, "xgh"));
        try {
            JSONArray all = cards();
            JSONObject card = findCard(all, "card.balance", "校园卡");
            if (card != null) {
                put(out, "balance", str(card, "value"));
                put(out, "unit", str(card, "unit"));
            }
        } catch (Exception e) {
            put(out, "status", "partial");
            put(out, "balance_error", e.getMessage());
        }
        try {
            JSONObject ui = ecodeGet("/api/user-info");
            JSONObject a = firstAttr(ui);
            if (a != null) {
                if (str(out, "name").isEmpty()) put(out, "name", str(a, "userName"));
                put(out, "student_id", str(a, "userCode"));
                put(out, "department", str(a, "unitName"));
            }
            JSONObject qr = ecodeGet("/api/qr-code");
            JSONObject qa = firstAttr(qr);
            if (qa != null) {
                JSONObject q = new JSONObject();
                put(q, "code", str(qa, "qrCode"));
                put(q, "created_at", qa.optLong("createTime", 0));
                put(q, "expires_at", qa.optLong("qrInvalidTime", 0));
                put(out, "qr", q);
            }
            JSONObject ec = ecodeGet("/api/ecard-customer");
            JSONObject ea = firstAttr(ec);
            if (ea != null) put(out, "valid_until", str(ea, "noUseDate"));
            JSONObject pr = ecodeGet("/api/user-priority");
            JSONObject pa = firstAttr(pr);
            JSONObject rules = pa == null ? null : pa.optJSONObject("ruleset");
            JSONObject rd = rules == null ? null : rules.optJSONObject("data");
            if (rd != null) {
                JSONObject gate = new JSONObject();
                put(gate, "name", str(rd, "name"));
                put(gate, "color", str(rd, "color"));
                put(gate, "result", str(rd, "result"));
                put(out, "gate", gate);
            }
        } catch (Exception e) {
            put(out, "status", "partial");
            String msg = e.getMessage();
            if (msg != null && msg.contains("登录失败")) msg = "登录已失效，请重新登录后刷新";
            put(out, "qr_error", msg);
            put(out, "ecode_final", ecodeFinal);
        }
        put(out, "updated_at", System.currentTimeMillis());
        return out;
    }

    private String queryParam(String url, String key) {
        try {
            String q = new URL(url).getQuery();
            if (q == null) return "";
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0 && kv.substring(0, i).equals(key)) return kv.substring(i + 1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    static String qpDecode(String s, String charset) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < b.length; i++) {
                if (b[i] == '=' && i + 2 < b.length) {
                    int hi = Character.digit((char) b[i + 1], 16);
                    int lo = Character.digit((char) b[i + 2], 16);
                    if (hi >= 0 && lo >= 0) { out.write((hi << 4) | lo); i += 2; continue; }
                }
                out.write(b[i] == '_' ? ' ' : b[i]);
            }
            return new String(out.toByteArray(), Charset.forName(charset));
        } catch (Exception e) { return s; }
    }

    static String decodeMime(String value) {
        if (value == null) return "";
        String text = xmlUnescape(value);
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String cs = m.group(1);
            String enc = m.group(2).toUpperCase(Locale.US);
            String body = m.group(3);
            String fixed;
            try {
                if ("B".equals(enc)) fixed = new String(Base64.decode(body, Base64.DEFAULT), Charset.forName(cs));
                else fixed = qpDecode(body, cs);
            } catch (Exception e) { fixed = body; }
            m.appendReplacement(sb, Matcher.quoteReplacement(fixed));
        }
        m.appendTail(sb);
        try { return Html.fromHtml(sb.toString()).toString(); } catch (Exception e) { return sb.toString(); }
    }

    private static String xmlField(String chunk, String tag, String name) {
        Matcher m = Pattern.compile("<" + tag + " name=\"" + Pattern.quote(name) + "\">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(chunk);
        return m.find() ? xmlUnescape(m.group(1)) : "";
    }

    private JSONArray parseMailItems(String xml) {
        JSONArray items = new JSONArray();
        Matcher vm = Pattern.compile("<array name=\"var\">(.*?)</array>", Pattern.DOTALL).matcher(xml);
        if (!vm.find()) return items;
        String var = vm.group(1);
        Matcher om = Pattern.compile("<object>(.*?)(?=<object>|</array>)", Pattern.DOTALL).matcher(var);
        while (om.find()) {
            String chunk = om.group(1);
            String id = xmlField(chunk, "string", "id");
            if (id.isEmpty()) continue;
            JSONObject it = new JSONObject();
            put(it, "id", id);
            put(it, "from", decodeMime(xmlField(chunk, "string", "from")));
            put(it, "subject", decodeMime(xmlField(chunk, "string", "subject")));
            put(it, "date", xmlField(chunk, "date", "sentDate"));
            put(it, "size", xmlField(chunk, "int", "size"));
            Matcher rm = Pattern.compile("<boolean name=\"read\">(.*?)</boolean>").matcher(chunk);
            put(it, "unread", rm.find() && "false".equals(rm.group(1)));
            Matcher am = Pattern.compile("<boolean name=\"attached\">(.*?)</boolean>").matcher(chunk);
            put(it, "attached", am.find() && "true".equals(am.group(1)));
            items.put(it);
        }
        return items;
    }

    public JSONObject mailInfo() {
        JSONObject out = new JSONObject();
        put(out, "status", "ok");
        try {
            JSONArray all = cards();
            JSONObject mail = findCard(all, "mail.coremailStudent", "未读邮件");
            if (mail != null) put(out, "portal_unread", str(mail, "value"));
        } catch (Exception ignored) {}
        try {
            String[] sso = httpGetFollow(MAIL_SSO, 20);
            if (queryParam(sso[0], "sid").isEmpty() && relogin()) {
                sso = httpGetFollow(MAIL_SSO, 20);
            }
            put(out, "mail_final", sso[0]);
            String sid = queryParam(sso[0], "sid");
            if (sid.isEmpty()) throw new ApiException(-1, "邮箱登录失败");
            String url = MAIL_API + "?sid=" + URLEncoder.encode(sid, "UTF-8")
                    + "&func=mbox:listMessages&mbox=Inbox&limit=30&order=date&desc=1";
            String xml = httpGetString(url, sso[0]);
            JSONArray items = parseMailItems(xml);
            put(out, "items", items);
            int unread = 0;
            for (int i = 0; i < items.length(); i++) {
                if (items.optJSONObject(i) != null && items.optJSONObject(i).optBoolean("unread")) unread++;
            }
            put(out, "unread", String.valueOf(unread));
            put(out, "web_url", sso[0]);
        } catch (Exception e) {
            put(out, "status", "partial");
            put(out, "error", e.getMessage());
        }
        return out;
    }

    byte[] httpGetBytes(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        String ck = cookieHeaderFor(new URL(url));
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        int code = c.getResponseCode();
        if (code >= 400) throw new ApiException(code, "HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    static String headerValue(String headers, String name) {
        for (String line : headers.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) {
                return line.substring(i + 1).trim();
            }
        }
        return "";
    }

    static String paramValue(String value, String key) {
        Matcher m = Pattern.compile(key + "=\"([^\"]+)\"|" + key + "=([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(value);
        if (!m.find()) return "";
        return m.group(1) != null ? m.group(1) : m.group(2);
    }

    static String decodeTransfer(String body, String encoding, String charset) {
        try {
            if ("base64".equalsIgnoreCase(encoding)) {
                return new String(Base64.decode(body.replaceAll("\\s", ""), Base64.DEFAULT), Charset.forName(charset));
            }
            if ("quoted-printable".equalsIgnoreCase(encoding)) {
                return qpDecode(body, charset);
            }
            return new String(body.getBytes(StandardCharsets.ISO_8859_1), Charset.forName(charset));
        } catch (Exception e) {
            return body;
        }
    }

    public JSONObject mailDetail(String mid) throws Exception {
        String[] sso = httpGetFollow(MAIL_SSO, 20);
        String sid = queryParam(sso[0], "sid");
        if (sid.isEmpty()) throw new ApiException(-1, "邮箱登录失败");
        String url = MAIL_API + "?sid=" + URLEncoder.encode(sid, "UTF-8")
                + "&func=mbox:getMessageData&mid=" + URLEncoder.encode(mid, "UTF-8");
        byte[] bytes = httpGetBytes(url, sso[0]);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        int split = raw.indexOf("\r\n\r\n");
        int sepLen = 4;
        if (split < 0) { split = raw.indexOf("\n\n"); sepLen = 2; }
        if (split < 0) throw new ApiException(-1, "邮件格式无法解析");
        String headers = raw.substring(0, split);
        String body = raw.substring(split + sepLen);
        String ctype = headerValue(headers, "Content-Type");
        String boundary = paramValue(ctype, "boundary");
        String plain = "", html = "";
        if (!boundary.isEmpty()) {
            String[] parts = body.split("--" + Pattern.quote(boundary));
            for (String part : parts) {
                int p = part.indexOf("\r\n\r\n");
                if (p < 0) continue;
                String ph = part.substring(0, p);
                String pb = part.substring(p + 4);
                String pct = headerValue(ph, "Content-Type");
                String enc = headerValue(ph, "Content-Transfer-Encoding");
                String cs = paramValue(pct, "charset");
                if (cs.isEmpty()) cs = "UTF-8";
                if (pct.toLowerCase(Locale.US).contains("text/plain") && plain.isEmpty()) {
                    plain = decodeTransfer(pb, enc, cs);
                } else if (pct.toLowerCase(Locale.US).contains("text/html") && html.isEmpty()) {
                    html = decodeTransfer(pb, enc, cs);
                }
            }
        } else {
            String enc = headerValue(headers, "Content-Transfer-Encoding");
            String cs = paramValue(ctype, "charset");
            if (cs.isEmpty()) cs = "UTF-8";
            plain = decodeTransfer(body, enc, cs);
        }
        String text = plain.trim();
        if (text.isEmpty() && !html.isEmpty()) {
            try { text = Html.fromHtml(html).toString().trim(); } catch (Exception ignored) {}
        }
        if (text.length() > 120000) text = text.substring(0, 120000);
        JSONObject out = new JSONObject();
        put(out, "status", "ok");
        put(out, "id", mid);
        put(out, "subject", decodeMime(headerValue(headers, "Subject")));
        put(out, "from", decodeMime(headerValue(headers, "From")));
        put(out, "to", decodeMime(headerValue(headers, "To")));
        put(out, "date", headerValue(headers, "Date"));
        put(out, "text", text);
        put(out, "web_url", sso[0]);
        return out;
    }

    private void payLogin() throws Exception {
        if (payAuthed) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            String[] r = httpGetFollow(PAY + "/drCasLogin", 20);
            if (r[0].startsWith(PAY)) { payAuthed = true; return; }
            if (attempt == 0 && relogin()) continue;
            throw new ApiException(-1, "缴费平台登录失败");
        }
    }

    static String money(double v) { return String.format(Locale.US, "%.2f", v); }

    private JSONObject orderResult(String text, String project) throws Exception {
        JSONObject j = new JSONObject(text);
        if (!"SUCCESS".equals(j.optString("returncode"))) {
            throw new ApiException(-1, j.optString("returnmsg", "下单失败"));
        }
        JSONObject tr = j.optJSONObject("payOrderTrade");
        if (tr == null) throw new ApiException(-1, "下单响应缺少订单");
        JSONObject out = new JSONObject();
        put(out, "status", "ok");
        put(out, "project", project);
        put(out, "order_id", tr.optLong("id", 0));
        put(out, "order_no", tr.optString("orderno"));
        put(out, "pay_order_no", j.optString("payorderno"));
        put(out, "amount", money(tr.optLong("orderamt", 0) / 100.0));
        put(out, "url", PAY + "/showPayType25" + tr.optLong("id", 0) + ".html");
        return out;
    }

    public JSONObject createCardOrder(double amount) throws Exception {
        if (amount < 0.01 || amount > 1000) throw new ApiException(-1, "金额需在 0.01–1000 元之间");
        payLogin();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("payAmt", money(amount));
        f.put("payProjectId", "264");
        f.put("factorycode", "Z006");
        f.put("rechargeType", "1");
        f.put("recharge_idserial", "");
        String text = httpPostForm(PAY + "/scardRechargeCreateOrder", f,
                PAY + "/scardrecharge51264Z006.html");
        return orderResult(text, "card");
    }

    public JSONObject createNetOrder(double amount) throws Exception {
        if (amount < 0.01 || amount > 1000) throw new ApiException(-1, "金额需在 0.01–1000 元之间");
        payLogin();
        JSONObject prof = st.optJSONObject("profile");
        if (prof == null) prof = new JSONObject();
        String netNo = str(prof, "xgh");
        if (netNo.isEmpty()) netNo = str(prof, "username");
        if (netNo.isEmpty()) throw new ApiException(-1, "缺少上网账号");
        Map<String, String> q = new LinkedHashMap<>();
        q.put("netNo", netNo);
        q.put("netType", "1");
        q.put("factorycode", "N005");
        String btxt = httpPostForm(PAY + "/queryNetAccBalance", q,
                PAY + "/netdetails51247N005.html");
        JSONObject bj = new JSONObject(btxt);
        if (!"SUCCESS".equals(bj.optString("returncode"))) {
            throw new ApiException(-1, bj.optString("returnmsg", "网费余额查询失败"));
        }
        String bal = bj.optString("netaccbal");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("netNo", netNo);
        f.put("netType", "1");
        f.put("payAmt", money(amount));
        f.put("netBalance", bal);
        f.put("payProjectId", "247");
        f.put("factorycode", "N005");
        String text = httpPostForm(PAY + "/netPayprojectCreateOrder", f,
                PAY + "/netdetails51247N005.html");
        JSONObject out = orderResult(text, "net");
        put(out, "net_balance", bal);
        return out;
    }

    public JSONObject payOrderStatus(String orderNo) throws Exception {
        payLogin();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("page", "1");
        f.put("rows", "50");
        f.put("payflag", "");
        String text = httpPostForm(PAY + "/queryOrderList", f, PAY + "/orders.html");
        JSONObject j = new JSONObject(text);
        JSONArray list = j.optJSONArray("payOrderList");
        JSONObject out = new JSONObject();
        put(out, "status", "unknown");
        put(out, "payflag", "");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null && orderNo.equals(o.optString("orderno"))) {
                    put(out, "status", "ok");
                    put(out, "payflag", o.optString("payflag"));
                    put(out, "amount", money(o.optLong("orderamt", 0) / 100.0));
                    put(out, "create_time", o.optString("createtime"));
                    break;
                }
            }
        }
        return out;
    }

    public List<String[]> webViewCookies() {
        List<String[]> out = new ArrayList<>();
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            String d = c.getDomain() == null ? "" : c.getDomain();
            if (d.contains("neu.edu.cn")) {
                out.add(new String[]{c.getName(), c.getValue(), d, c.getPath()});
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- jwxt
    public JSONObject jwxtDiscover(boolean probe) {
        JSONObject out = new JSONObject();
        JSONArray menu = new JSONArray();
        JSONArray links = new JSONArray();
        try {
            if (ticket().isEmpty()) {
                out.put("status", "need_login");
                out.put("note", "尚未登录");
                return out;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(JWXT_HOME).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", UA);
            String html = readAll(conn);
            String finalUrl = conn.getURL().toString();
            if (finalUrl.contains("pass.neu.edu.cn") || finalUrl.contains("tpass")) {
                out.put("status", "need_login");
                out.put("note", "CAS 未放行（TGT 失效或需校园网/WebVPN）");
                return out;
            }
            menu = jwxtMenu();
            links = extractLinks(html);
            out.put("status", "ok");
            out.put("menu", menu);
            out.put("links", links);
            JSONObject endpoints = st.optJSONObject("endpoints");
            if (endpoints != null) {
                JSONObject j = endpoints.optJSONObject("jwxt");
                if (j == null) j = new JSONObject();
                j.put("menu", menu);
                j.put("links", links);
                j.put("checked_at", System.currentTimeMillis() / 1000L);
                endpoints.put("jwxt", j);
                save();
            }
        } catch (Exception e) {
            put(out, "status", "error");
            put(out, "note", e.getMessage());
        }
        return out;
    }

    private JSONArray jwxtMenu() {
        JSONArray out = new JSONArray();
        String[] urls = {JWXT_MENU + "?*json=1", JWXT_MENU};
        for (String u : urls) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Accept", "application/json,*/*");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                String text = readAll(conn);
                if (!text.trim().startsWith("{") && !text.trim().startsWith("[")) continue;
                Object root = text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
                collectMenu(root, out, 0);
                if (out.length() > 0) break;
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void collectMenu(Object obj, JSONArray out, int depth) {
        if (depth > 5 || obj == null) return;
        if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            for (int i = 0; i < a.length(); i++) collectMenu(a.opt(i), out, depth + 1);
        } else if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            String app = first(o, "appId", "app_id", "id");
            String name = first(o, "appName", "name", "title");
            if (!app.isEmpty() && !name.isEmpty()) {
                JSONObject m = new JSONObject();
                put(m, "app_id", app);
                put(m, "name", name);
                put(m, "module_id", first(o, "moduleId", "module_id"));
                put(m, "url", first(o, "url"));
                out.put(m);
            }
            Iterator<String> ks = o.keys();
            while (ks.hasNext()) {
                Object v = o.opt(ks.next());
                if (v instanceof JSONObject || v instanceof JSONArray) collectMenu(v, out, depth + 1);
            }
        }
    }

    private static JSONArray extractLinks(String html) {
        JSONArray out = new JSONArray();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("href=\"(/jwapp/[^\"#]+)\"[^>]*>([^<]{1,40})<").matcher(html);
        int n = 0;
        while (m.find() && n++ < 50) {
            JSONObject o = new JSONObject();
            put(o, "url", JWXT + m.group(1));
            put(o, "name", m.group(2).trim());
            out.put(o);
        }
        return out;
    }

    /** 从 state.endpoints.jwxt.[exams_url|grades_url] 拉取并归一化。 */
    public JSONArray customRecords(String kind) {
        try {
            JSONObject j = st.optJSONObject("endpoints");
            if (j == null) return new JSONArray();
            JSONObject jw = j.optJSONObject("jwxt");
            if (jw == null) return new JSONArray();
            String url = jw.optString(kind + "_url", "");
            if (url.isEmpty()) return new JSONArray();
            if (!url.startsWith("http")) url = JWXT + url;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json,*/*");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            String text = readAll(conn);
            String t = text.trim();
            if (!t.startsWith("{") && !t.startsWith("[")) return new JSONArray();
            Object root = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
            JSONArray list = bestList(root, kind.equals("exams") ? EXAM_KEYS : GRADE_KEYS);
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String course = first(o, "kcmc", "courseName", "course", "course_name", "课程名称", "课程", "title");
                if (course.isEmpty()) continue;
                JSONObject r = new JSONObject();
                put(r, "course", course);
                if (kind.equals("exams")) {
                    put(r, "time", first(o, "kssj", "examTime", "考试时间", "时间", "startTime", "time"));
                    put(r, "place", first(o, "ksdd", "place", "地点", "考场", "教室", "location"));
                    put(r, "seat", first(o, "zwh", "seat", "座位号", "座位"));
                } else {
                    put(r, "score", first(o, "cj", "score", "成绩", "zf", "总评", "finalScore"));
                    put(r, "credit", first(o, "xf", "credit", "学分", "credits"));
                    put(r, "teacher", first(o, "jsxm", "teacher", "教师", "任课教师"));
                }
                out.put(r);
            }
            return out;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONArray bestList(Object root, String[] keys) {
        List<JSONArray> lists = new ArrayList<>();
        collectArrays(root, lists, 0);
        JSONArray best = new JSONArray();
        double bestScore = 0;
        for (JSONArray a : lists) {
            if (a.length() == 0) continue;
            double score = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                int hit = 0;
                for (String k : keys) if (o.has(k)) hit++;
                score += hit;
            }
            score /= a.length();
            if (score > bestScore) { bestScore = score; best = a; }
        }
        return bestScore >= 2 ? best : new JSONArray();
    }

    private static void collectArrays(Object obj, List<JSONArray> out, int depth) {
        if (depth > 6 || obj == null) return;
        if (obj instanceof JSONArray) {
            JSONArray a = (JSONArray) obj;
            out.add(a);
            for (int i = 0; i < a.length(); i++) collectArrays(a.opt(i), out, depth + 1);
        } else if (obj instanceof JSONObject) {
            JSONObject o = (JSONObject) obj;
            Iterator<String> ks = o.keys();
            while (ks.hasNext()) collectArrays(o.opt(ks.next()), out, depth + 1);
        }
    }

    // --------------------------------------------------------------- jwapp
    boolean ensureJwxt() {
        if (jwxtOk) return true;
        boolean ok = ensureJwxtOnce();
        if (!ok && relogin()) {
            jwxtOk = false;
            webvpn = false;
            webvpnAuthed = false;
            webvpnBase = "";
            invalidateJwxtCache();
            ok = ensureJwxtOnce();
        }
        return ok;
    }

    private boolean ensureJwxtOnce() {
        if (ticket().isEmpty()) return false;
        if (webvpn) {
            if (probeJwxtSession()) { jwxtOk = true; return true; }
            for (int i = 0; i < 2 && !jwxtOk; i++) {
                if (webvpnLogin()) jwxtOk = webvpnSso();
            }
            if (jwxtOk) return true;
            webvpn = false;
            webvpnBase = "";
            webvpnAuthed = false;
            put(st, "webvpn_mode", false);
            save();
        }

        if (directReachable()) {
            if (probeJwxtSession()) { jwxtOk = true; return true; }
            for (int i = 0; i < 2 && !jwxtOk; i++) {
                jwxtOk = "ok".equals(jwxtSso().optString("status"));
            }
            if (jwxtOk) {
                put(st, "webvpn_mode", false);
                save();
                return true;
            }
        }

        webvpn = true;
        for (int i = 0; i < 2 && !jwxtOk; i++) {
            if (webvpnLogin()) jwxtOk = webvpnSso();
        }
        return jwxtOk;
    }

    /** 校园网内直连教务；连不上就切 WebVPN（校外/移动数据）。 */
    boolean directReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(JWXT_HOME).openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            readAll(c);
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** 网瑞达 WebVPN 的 host 加密：AES-CFB，key=iv="b0A58a69394ce73@"，输出 hex(iv+cipher)。 */
    static String webvpnToken(String host) throws Exception {
        byte[] key = WV_KEY.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CFB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        byte[] ct = cipher.doFinal(host.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[key.length + ct.length];
        System.arraycopy(key, 0, out, 0, key.length);
        System.arraycopy(ct, 0, out, key.length, ct.length);
        return hex(out);
    }

    String[] httpGetFollow(String startUrl, int maxHops) throws Exception {
        String url = startUrl;
        int code = -1;
        for (int i = 0; i < maxHops; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            String ck = cookieHeaderFor(new URL(url));
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            readAll(c);
            if (code >= 300 && code < 400 && loc != null && !loc.isEmpty()) {
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            break;
        }
        return new String[]{url, String.valueOf(code)};
    }

    String httpGetLocation(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        String ck = cookieHeaderFor(new URL(url));
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        int code = c.getResponseCode();
        String loc = c.getHeaderField("Location");
        readAll(c);
        return (code >= 300 && code < 400) ? loc : null;
    }

    boolean webvpnLogin() {
        if (webvpnAuthed) return true;
        try {
            String cas = "https://pass.neu.edu.cn/tpass/login?service="
                    + URLEncoder.encode(WV_LOGIN, "UTF-8");
            String[] res = httpGetFollow(cas, 14);
            webvpnAuthed = res[0].startsWith(WV);
            return webvpnAuthed;
        } catch (Exception e) {
            return false;
        }
    }

    String webvpnTicket() {
        return webvpnTicket(JWXT + "/jwapp/sys/homeapp/index.do");
    }

    String webvpnTicket(String service) {
        try {
            String cas = "https://pass.neu.edu.cn/tpass/login?service="
                    + URLEncoder.encode(service, "UTF-8");
            String loc = httpGetLocation(cas);
            if (loc == null || !loc.contains("ticket=")) loc = httpGetLocation(cas);
            if (loc == null) return null;
            int i = loc.indexOf("ticket=");
            if (i < 0) return null;
            String t = loc.substring(i + 7);
            int amp = t.indexOf('&');
            if (amp >= 0) t = t.substring(0, amp);
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    String[] httpGetText(String startUrl, int maxHops) throws Exception {
        return httpGetText(startUrl, maxHops, null);
    }

    String[] httpGetText(String startUrl, int maxHops, String stayHost) throws Exception {
        String url = startUrl;
        String body = "";
        for (int i = 0; i < maxHops; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            String ck = cookieHeaderFor(new URL(url));
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            body = readAll(c);
            if (code >= 300 && code < 400 && loc != null && !loc.isEmpty()) {
                String next = new URL(new URL(url), loc).toString();
                if (stayHost != null && !new URL(next).getHost().endsWith(stayHost)) break;
                url = next;
                continue;
            }
            break;
        }
        return new String[]{url, body};
    }

    boolean ipgwReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://ipgw.neu.edu.cn:8800/").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            readAll(c);
            return code > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").replace("&nbsp;", " ").trim();
    }

    JSONObject parseIpwg(String html, String via) {
        String seg = html;
        java.util.regex.Matcher sec = java.util.regex.Pattern
                .compile("产品信息.*?<tbody>(.*?)</tbody>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        if (sec.find()) seg = sec.group(1);
        String[] cells = new String[8];
        for (int i = 0; i < cells.length; i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("data-col-seq=\"" + i + "\"[^>]*>(.*?)</td>", java.util.regex.Pattern.DOTALL)
                    .matcher(seg);
            cells[i] = m.find() ? stripTags(m.group(1)) : "";
        }
        String usageText = cells[3], feeText = cells[6], balText = cells[7];
        java.util.regex.Matcher um = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT])")
                .matcher(usageText.toUpperCase());
        if (!um.find()) return null;
        double n = Double.parseDouble(um.group(1));
        String u = um.group(2);
        double gb = "T".equals(u) ? n * 1024 : "M".equals(u) ? n / 1024 : "K".equals(u) ? n / 1024 / 1024 : n;
        JSONObject o = new JSONObject();
        put(o, "ok", true);
        put(o, "usage_gb", gb);
        put(o, "usage_text", usageText);
        put(o, "fee", numOrNull(feeText));
        put(o, "balance", numOrNull(balText));
        put(o, "policy", cells[2]);
        put(o, "product", cells[1]);
        put(o, "duration", cells[4]);
        put(o, "via", via);
        return o;
    }

    static Double numOrNull(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    public JSONObject ipgwUsage() {
        String svc = "http://ipgw.neu.edu.cn:8800/sso/neusoft/index";
        try {
            if (ipgwReachable()) {
                String cas = "https://pass.neu.edu.cn/tpass/login?service="
                        + URLEncoder.encode(svc, "UTF-8");
                String[] r = httpGetText(cas, 12);
                if (r[1].contains("已用流量")) return parseIpwg(r[1], "direct");
            }
        } catch (Exception ignored) {
        }
        try {
            if (!webvpnLogin()) return null;
            String token = webvpnToken("ipgw.neu.edu.cn");
            String base = WV + "/https-8800/" + token;
            String t = webvpnTicket(svc);
            if (t == null) return null;
            String[] r = httpGetText(base + "/sso/neusoft/index?ticket=" + URLEncoder.encode(t, "UTF-8"), 6, "webvpn.neu.edu.cn");
            if (!r[1].contains("已用流量")) r = httpGetText(base + "/home", 4, "webvpn.neu.edu.cn");
            if (r[1].contains("已用流量")) return parseIpwg(r[1], "webvpn");
        } catch (Exception ignored) {
        }
        return null;
    }

    boolean probeJwxtSession() {
        try {
            if (ticket().isEmpty()) return false;
            String base = webvpn ? webvpnBase : JWXT;
            if (base == null || base.isEmpty()) return false;
            String url = base + "/jwapp/sys/homeapp/api/home/currentUser.do";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Referer", base + "/jwapp/sys/homeapp/home/index.html");
            String ck = cookieHeaderFor(new URL(url));
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
            String text = readAll(c).trim();
            if (!text.startsWith("{")) return false;
            JSONObject obj = new JSONObject(text);
            return "0".equals(str(obj, "code")) || "200".equals(str(obj, "code"));
        } catch (Exception e) {
            return false;
        }
    }

    boolean webvpnSso() {
        try {
            String token = webvpnToken("jwxt.neu.edu.cn");
            String base = WV + "/https/" + token;
            String t = webvpnTicket();
            if (t == null || t.isEmpty()) return false;
            httpGetFollow(base + "/jwapp/sys/homeapp/index.do?ticket=" + URLEncoder.encode(t, "UTF-8"), 10);
            httpGetFollow(base + "/jwapp/sys/homeapp/home/index.html", 6);
            webvpnBase = base;
            st.put("webvpn_mode", true);
            st.put("webvpn_base", base);
            save();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    JSONObject jwxtSso() {
        JSONObject out = new JSONObject();
        if (ticket().isEmpty()) {
            put(out, "status", "need_login");
            put(out, "note", "尚未登录");
            return out;
        }
        try {
            String url = JWXT_HOME;
            int code = -1;
            for (int hop = 0; hop < 12; hop++) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", UA);
                String ck = cookieHeaderFor(new URL(url));
                if (!ck.isEmpty()) conn.setRequestProperty("Cookie", ck);
                code = conn.getResponseCode();
                String loc = conn.getHeaderField("Location");
                readAll(conn);
                if (code >= 300 && code < 400 && loc != null && !loc.isEmpty()) {
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                break;
            }
            put(out, "final_url", url);
            put(out, "http", code);
            URL u = new URL(url);
            boolean cas = "pass.neu.edu.cn".equals(u.getHost()) || u.getPath().startsWith("/tpass");
            if (cas) {
                put(out, "status", "need_login");
                put(out, "note", "CAS 未放行（需校园网/WebVPN）");
            } else if (code >= 400) {
                put(out, "status", "error");
                put(out, "note", "HTTP " + code);
            } else {
                jwxtOk = true;
                put(out, "status", "ok");
                // 触发首页，服务端会下发 _WEU 等会话 Cookie
                try {
                    String homeUrl = JWXT + "/jwapp/sys/homeapp/home/index.html";
                    HttpURLConnection home = (HttpURLConnection) new URL(homeUrl).openConnection();
                    home.setConnectTimeout(15000);
                    home.setReadTimeout(20000);
                    home.setRequestProperty("User-Agent", UA);
                    String hc = cookieHeaderFor(new URL(homeUrl));
                    if (!hc.isEmpty()) home.setRequestProperty("Cookie", hc);
                    readAll(home);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            put(out, "status", "error");
            put(out, "note", e.getMessage());
        }
        return out;
    }

    public String webvpnJwxtUrl() {
        try {
            if (ensureJwxt() && webvpn) {
                return webvpnBase + "/jwapp/sys/homeapp/index.do";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    String cookieHeaderFor(URL url) {
        StringBuilder sb = new StringBuilder();
        String host = url.getHost();
        String path = url.getPath();
        for (HttpCookie c : cookies.getCookieStore().getCookies()) {
            if (!domainMatches(c.getDomain(), host)) continue;
            if (!pathMatches(c.getPath(), path)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(c.getName()).append('=').append(c.getValue());
        }
        return sb.toString();
    }

    static boolean domainMatches(String domain, String host) {
        if (domain == null || host == null) return false;
        String d = domain.startsWith(".") ? domain.substring(1) : domain;
        return host.equals(d) || host.endsWith("." + d);
    }

    static boolean pathMatches(String cookiePath, String reqPath) {
        if (cookiePath == null || cookiePath.isEmpty()) return true;
        if (reqPath == null || reqPath.isEmpty()) reqPath = "/";
        return reqPath.equals(cookiePath)
                || reqPath.startsWith(cookiePath.endsWith("/") ? cookiePath : cookiePath + "/");
    }

    JSONObject jwPost(String url, JSONObject data) throws Exception {
        if (!ensureJwxt()) throw new ApiException(10013, "教务未登录");
        String target = url;
        if (webvpn) {
            int i = url.indexOf("/jwapp/");
            if (i >= 0) target = webvpnBase + url.substring(i);
        }
        StringBuilder sb = new StringBuilder();
        if (data != null) {
            Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = data.optString(k, "");
                if (sb.length() > 0) sb.append('&');
                sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"));
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String referer = webvpn ? webvpnBase + "/jwapp/sys/homeapp/home/index.html"
                : JWXT + "/jwapp/sys/homeapp/home/index.html";
        conn.setRequestProperty("Referer", referer);
        String cookieHeader = cookieHeaderFor(new URL(target));
        if (!cookieHeader.isEmpty()) conn.setRequestProperty("Cookie", cookieHeader);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        String text = readAll(conn).trim();
        if (!text.startsWith("{")) {
            String snippet = text.length() > 120 ? text.substring(0, 120) : text;
            throw new ApiException(-1, "教务返回非 JSON（" + conn.getResponseCode() + "）" + snippet.replaceAll("\\s+", " "));
        }
        JSONObject obj = new JSONObject(text);
        String code = str(obj, "code");
        if (!"0".equals(code) && !"200".equals(code)) {
            throw new ApiException(-1, "教务错误 code=" + code);
        }
        return obj;
    }

    void invalidateJwxtCache() {
        ttCache = null;
        termCache = null;
    }

    JSONObject currentTerm() throws Exception {
        if (termCache != null && System.currentTimeMillis() - termAt < 300000) return termCache;
        JSONObject obj = jwPost(JW_CURRENT_USER, new JSONObject());
        JSONObject datas = obj.optJSONObject("datas");
        JSONObject info = datas == null ? null : datas.optJSONObject("welcomeInfo");
        JSONObject out = new JSONObject();
        if (info != null) {
            put(out, "term", str(info, "xnxqdm"));
            put(out, "term_name", str(info, "xnxqmc"));
            put(out, "week", info.optInt("classWeek", 0));
            put(out, "date", str(info, "date"));
        }
        termCache = out;
        termAt = System.currentTimeMillis();
        return out;
    }

    static JSONArray parseWeeks(String text) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*-\\s*(\\d+)\\s*周|(\\d+)\\s*周").matcher(text == null ? "" : text);
        while (m.find()) {
            if (m.group(1) != null) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                for (int i = a; i <= b && i <= 40; i++) set.add(i);
            } else if (m.group(3) != null) {
                set.add(Integer.parseInt(m.group(3)));
            }
        }
        JSONArray arr = new JSONArray();
        for (int i : set) arr.put(i);
        return arr;
    }

    static String parseTeacher(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/([^/\\[\\]]+)\\[").matcher(text == null ? "" : text);
        return m.find() ? m.group(1).trim() : "";
    }

    static boolean containsInt(JSONArray arr, int n) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) if (arr.optInt(i) == n) return true;
        return false;
    }

    JSONObject jwTimetable(String term) throws Exception {
        if (ttCache != null && term.equals(ttTerm) && System.currentTimeMillis() - ttAt < 600000) {
            return ttCache;
        }
        JSONObject data = new JSONObject();
        data.put("XNXQDM", term);
        JSONObject obj = jwPost(JW_TIMETABLE, data);
        JSONObject datas = obj.optJSONObject("datas");
        JSONObject d = datas == null ? null : datas.optJSONObject("getMyScheduleDetail");
        JSONArray courses = new JSONArray();
        if (d != null) {
            for (String key : new String[]{"arrangedList", "practiceList", "notArrangeList"}) {
                JSONArray rows = d.optJSONArray(key);
                if (rows == null) continue;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    if (r == null) continue;
                    JSONObject c = new JSONObject();
                    put(c, "course", str(r, "courseName"));
                    put(c, "code", str(r, "courseCode"));
                    put(c, "teacher", parseTeacher(str(r, "weeksAndTeachers")));
                    put(c, "place", str(r, "placeName"));
                    put(c, "day", r.optInt("dayOfWeek", 0));
                    put(c, "begin_section", r.optInt("beginSection", 0));
                    put(c, "end_section", r.optInt("endSection", 0));
                    put(c, "begin", str(r, "beginTime"));
                    put(c, "end", str(r, "endTime"));
                    put(c, "weeks", parseWeeks(str(r, "weeksAndTeachers")));
                    put(c, "weeks_text", str(r, "weeksAndTeachers"));
                    put(c, "credit", str(r, "credit"));
                    put(c, "color", str(r, "color"));
                    put(c, "teach_class", str(r, "teachClassId"));
                    put(c, "teach_class_name", str(r, "teachClassName"));
                    put(c, "campus", str(r, "campusName"));
                    put(c, "tags", str(r, "tags"));
                    put(c, "kind", key);
                    StringBuilder detail = new StringBuilder();
                    JSONArray cd = r.optJSONArray("cellDetail");
                    if (cd != null) {
                        for (int t = 0; t < cd.length(); t++) {
                            JSONObject cell = cd.optJSONObject(t);
                            if (cell == null) continue;
                            String txt = str(cell, "text");
                            if (txt.isEmpty()) continue;
                            if (detail.length() > 0) detail.append(" / ");
                            detail.append(txt);
                        }
                    }
                    put(c, "cell_detail", detail.toString());
                    put(c, "arranged", "arrangedList".equals(key));
                    courses.put(c);
                }
            }
        }
        JSONObject out = new JSONObject();
        put(out, "term", term);
        put(out, "courses", courses);
        ttCache = out;
        ttTerm = term;
        ttAt = System.currentTimeMillis();
        return out;
    }

    static Calendar parseDate(String start) {
        Calendar cal = Calendar.getInstance();
        if (start != null && start.length() >= 10) {
            String[] p = start.split("-");
            cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        }
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal;
    }

    /** 周日为一周第一天。 */
    static Calendar sundayOf(String start) {
        Calendar cal = parseDate(start);
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 周日=1
        cal.add(Calendar.DAY_OF_MONTH, -(dow - 1));
        return cal;
    }

    static Calendar mondayOfDate(Calendar src) {
        Calendar cal = (Calendar) src.clone();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        cal.add(Calendar.DAY_OF_MONTH, -((dow + 5) % 7));
        return cal;
    }

    JSONObject weekEvents(String start) throws Exception {
        JSONObject term = currentTerm();
        int cur = term.optInt("week", 1);
        Calendar first = mondayOfDate(parseDate(null));
        first.add(Calendar.DAY_OF_MONTH, -(cur - 1) * 7);
        Calendar anchor = sundayOf(start);
        JSONObject tt = jwTimetable(str(term, "term"));
        JSONArray courses = tt.optJSONArray("courses");
        List<JSONObject> evs = new ArrayList<>();
        if (courses != null) {
            for (int i = 0; i < 7; i++) {
                Calendar d = (Calendar) anchor.clone();
                d.add(Calendar.DAY_OF_MONTH, i);
                int colDay = (i == 0) ? 7 : i; // 周日=7，周一=1 … 周六=6
                Calendar dMon = mondayOfDate(d);
                int weekNo = (int) ((dMon.getTimeInMillis() - first.getTimeInMillis()) / 86400000L / 7) + 1;
                for (int j = 0; j < courses.length(); j++) {
                    JSONObject c = courses.optJSONObject(j);
                    if (c == null || !c.optBoolean("arranged")) continue;
                    if (c.optInt("day", 0) != colDay) continue;
                    if (!containsInt(c.optJSONArray("weeks"), weekNo)) continue;
                    String begin = str(c, "begin");
                    String end = str(c, "end");
                    if (begin.isEmpty()) begin = "00:00";
                    if (end.isEmpty()) end = begin;
                    JSONObject ev = new JSONObject();
                    put(ev, "title", str(c, "course"));
                    put(ev, "start", fmtDate(d) + "T" + begin + ":00");
                    put(ev, "end", fmtDate(d) + "T" + end + ":00");
                    put(ev, "location", str(c, "place"));
                    put(ev, "desc", str(c, "weeks_text"));
                    put(ev, "teacher", str(c, "teacher"));
                    put(ev, "color", str(c, "color"));
                    evs.add(ev);
                }
            }
        }
        evs.sort((a, b) -> a.optString("start").compareTo(b.optString("start")));
        JSONArray events = new JSONArray();
        for (JSONObject e : evs) events.put(e);
        Calendar endCal = (Calendar) anchor.clone();
        endCal.add(Calendar.DAY_OF_MONTH, 6);
        Calendar labelMon = mondayOfDate(parseDate(fmtDate(endCal)));
        int labelWeek = (int) ((labelMon.getTimeInMillis() - first.getTimeInMillis()) / 86400000L / 7) + 1;
        JSONObject out = new JSONObject();
        put(out, "start", fmtDate(anchor));
        put(out, "end", fmtDate(endCal));
        put(out, "week", labelWeek);
        put(out, "current_week", cur);
        put(out, "events", events);
        put(out, "courses", tt.optJSONArray("courses"));
        return out;
    }

    JSONArray jwExams() throws Exception {
        JSONObject obj = jwPost(JW_EXAMS, new JSONObject());
        JSONObject datas = obj.optJSONObject("datas");
        JSONObject d = datas == null ? null : datas.optJSONObject("queryMyExamArrangeMent");
        JSONArray rows = d == null ? null : d.optJSONArray("arranged");
        JSONArray out = new JSONArray();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                JSONObject e = new JSONObject();
                put(e, "course", str(r, "KCM"));
                String time = (str(r, "KSRQ") + " " + str(r, "KSSJ")
                        + "-" + str(r, "JSSJ")).trim();
                put(e, "time", time);
                put(e, "place", str(r, "JASMC"));
                put(e, "seat", str(r, "ZWH"));
                put(e, "type", str(r, "KSLXDM_DISPLAY"));
                put(e, "term", str(r, "XNXQDM"));
                put(e, "desc", str(r, "KSSJMS"));
                out.put(e);
            }
        }
        return out;
    }

    JSONObject jwGrades() throws Exception {
        JSONObject obj = jwPost(JW_GRADES, new JSONObject());
        JSONObject datas = obj.optJSONObject("datas");
        JSONObject d = datas == null ? null : datas.optJSONObject("cxwdcj");
        JSONArray rows = d == null ? null : d.optJSONArray("rows");
        JSONArray items = new JSONArray();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                JSONObject g = new JSONObject();
                put(g, "course", str(r, "KCM"));
                put(g, "score", str(r, "XSZCJ"));
                put(g, "credit", str(r, "XF"));
                put(g, "gpa", str(r, "JD"));
                put(g, "term", r.optString("XNXQDM_DISPLAY", str(r, "XNXQDM")));
                put(g, "type", str(r, "KCXZDM_DISPLAY"));
                put(g, "passed", str(r, "SFJG_DISPLAY"));
                items.put(g);
            }
        }
        JSONObject rank = new JSONObject();
        try {
            JSONObject r = jwPost(JW_RANK, new JSONObject());
            JSONObject rd = r.optJSONObject("datas");
            rd = rd == null ? null : rd.optJSONObject("queryPjxfjd");
            if (rd != null) {
                put(rank, "gpa", rd.optString("ZPJXFJDNEW", str(rd, "ZPJXFJD")));
                put(rank, "major_rank", str(rd, "ZPJXFJDPM"));
            }
        } catch (Exception ignored) {
        }
        JSONObject out = new JSONObject();
        put(out, "items", items);
        put(out, "rank", rank);
        return out;
    }

    public JSONObject jwxtStatus() {
        JSONObject out = new JSONObject();
        try {
            invalidateJwxtCache();
            if (!ensureJwxt()) {
                put(out, "status", "need_login");
                put(out, "note", "教务未登录（需校园网/WebVPN）");
                return out;
            }
            JSONObject term = currentTerm();
            JSONObject g = jwGrades();
            JSONArray items = g.optJSONArray("items");
            put(out, "status", "ok");
            put(out, "mode", webvpn ? "webvpn" : "direct");
            put(out, "term", term);
            put(out, "exams", jwExams().length());
            put(out, "grades", items == null ? 0 : items.length());
            put(out, "rank", g.optJSONObject("rank"));
        } catch (Exception e) {
            put(out, "status", "error");
            put(out, "note", e.getMessage());
        }
        return out;
    }

    // --------------------------------------------------------------- 导出
    static String csvCell(String v) {
        if (v == null) v = "";
        boolean need = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        v = v.replace("\"", "\"\"");
        return need ? "\"" + v + "\"" : v;
    }

    static void csvRow(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells[i]));
        }
        sb.append("\r\n");
    }

    static String dayName(int d) {
        switch (d) {
            case 1: return "周一";
            case 2: return "周二";
            case 3: return "周三";
            case 4: return "周四";
            case 5: return "周五";
            case 6: return "周六";
            case 7: return "周日";
            default: return "";
        }
    }

    static int dayOrder(int d) {
        return d == 7 ? 0 : (d >= 1 && d <= 6 ? d : 99);
    }

    static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return (v == null || v == JSONObject.NULL) ? "" : String.valueOf(v);
    }

    public String examsCsv() throws Exception {
        JSONObject obj = jwPost(JW_EXAMS, new JSONObject());
        JSONObject datas = obj.optJSONObject("datas");
        JSONObject d = datas == null ? null : datas.optJSONObject("queryMyExamArrangeMent");
        JSONArray rows = d == null ? null : d.optJSONArray("arranged");
        StringBuilder sb = new StringBuilder("\ufeff");
        csvRow(sb, "学期", "考试名称", "考试类型", "课程代码", "课程名称", "教学班", "任课教师",
                "考试日期", "开始时间", "结束时间", "考场", "座位号", "考试周次",
                "学院", "专业", "班级", "年级", "是否缓考", "考试状态",
                "考试时间描述", "备注", "原始数据");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) continue;
                String sfhk = str(r, "SFHK");
                if ("1".equals(sfhk)) sfhk = "是";
                else if ("0".equals(sfhk)) sfhk = "否";
                String note = str(r, "KSSM");
                if (note.isEmpty()) note = str(r, "PKSM");
                csvRow(sb,
                        str(r, "XNXQDM"),
                        str(r, "KSDM_DISPLAY"),
                        str(r, "KSLXDM_DISPLAY"),
                        str(r, "KCH"),
                        str(r, "KCM"),
                        str(r, "JXBID"),
                        str(r, "SKJS"),
                        str(r, "KSRQ"),
                        str(r, "KSSJ"),
                        str(r, "JSSJ"),
                        str(r, "JASMC"),
                        str(r, "ZWH"),
                        str(r, "ZC"),
                        str(r, "YXDM_DISPLAY"),
                        str(r, "ZYDM_DISPLAY"),
                        str(r, "BJDM_DISPLAY"),
                        str(r, "XZNJ_DISPLAY"),
                        sfhk,
                        str(r, "KSZT"),
                        str(r, "KSSJMS"),
                        note,
                        r.toString());
            }
        }
        return sb.toString();
    }

    static boolean isClassGroupToken(String s) {
        return s != null && s.matches("[^,\\s]+\\(\\d+\\)(?:,[^,\\s]+\\(\\d+\\))*");
    }

    static String cleanPlace(JSONObject c) {
        String raw = str(c, "place").replace("*", "").trim();
        if (raw.isEmpty() || "停课".equals(raw)) return "暂未安排教室";
        java.util.Set<String> teachers = new java.util.HashSet<>();
        for (String x : str(c, "teacher").split("[,，、/\\s]+")) {
            if (!x.isEmpty()) teachers.add(x);
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String p : raw.split("\\s+")) {
            if (!p.isEmpty()) parts.add(p);
        }
        parts.removeIf(p -> teachers.contains(p) || p.endsWith("校区"));
        while (parts.size() > 1 && isClassGroupToken(parts.get(parts.size() - 1))) {
            parts.remove(parts.size() - 1);
        }
        if (parts.size() == 1 && isClassGroupToken(parts.get(0))) parts.clear();
        String out = String.join(" ", parts).trim();
        return out.isEmpty() ? "暂未安排教室" : out;
    }

    static String weeksFromNumbers(JSONArray arr) {
        if (arr == null) return "";
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int i = 0; i < arr.length(); i++) {
            int n = arr.optInt(i, 0);
            if (n > 0) set.add(n);
        }
        if (set.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int start = -1, prev = -1;
        for (int n : set) {
            if (start < 0) { start = prev = n; continue; }
            if (n == prev + 1) { prev = n; continue; }
            if (sb.length() > 0) sb.append("、");
            sb.append(start == prev ? start + "周" : start + "-" + prev + "周");
            start = prev = n;
        }
        if (sb.length() > 0) sb.append("、");
        sb.append(start == prev ? start + "周" : start + "-" + prev + "周");
        return sb.toString();
    }

    static String cleanWeek(JSONObject c) {
        String raw = str(c, "weeks_text");
        int bracket = raw.indexOf('[');
        if (bracket >= 0) raw = raw.substring(0, bracket);
        int slash = raw.indexOf('/');
        if (slash >= 0) raw = raw.substring(0, slash);
        String text = raw.replace(",", "、").replace("，", "、")
                .replace("(", "").replace(")", "").trim();
        if (text.startsWith("第")) text = text.substring(1);
        return text.isEmpty() ? weeksFromNumbers(c.optJSONArray("weeks")) : text;
    }

    public String scheduleFileName() {
        JSONObject p = st.optJSONObject("profile");
        String base = (str(p, "name") + str(p, "xgh")).trim();
        return base.isEmpty() ? "课表" : base;
    }

    public String timetableCsv() throws Exception {
        JSONObject term = currentTerm();
        JSONObject tt = jwTimetable(str(term, "term"));
        JSONArray courses = tt.optJSONArray("courses");
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (courses != null) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c == null || !c.optBoolean("arranged")) continue;
                int day = c.optInt("day", 0);
                int begin = c.optInt("begin_section", 0);
                int end = c.optInt("end_section", 0);
                if (day < 1 || day > 7 || begin < 1 || end < begin || end > 12) continue;
                String courseName = str(c, "course").trim();
                if (courseName.isEmpty()) continue;
                String place = cleanPlace(c);
                if ("停课".equals(place)) continue;
                String teacher = str(c, "teacher").trim();
                if (teacher.isEmpty()) teacher = "暂未安排教师";
                rows.add(new String[]{courseName, String.valueOf(day), String.valueOf(begin),
                        String.valueOf(end), teacher, place, cleanWeek(c)});
            }
        }
        rows.sort((a, b) -> {
            int cmp = Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(Integer.parseInt(a[2]), Integer.parseInt(b[2]));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(Integer.parseInt(a[3]), Integer.parseInt(b[3]));
            if (cmp != 0) return cmp;
            return a[0].compareTo(b[0]);
        });
        StringBuilder sb = new StringBuilder("\ufeff");
        csvRow(sb, "课程名称", "星期", "开始节数", "结束节数", "老师", "地点", "周数");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] row : rows) {
            String key = String.join("\u0001", row);
            if (seen.add(key)) csvRow(sb, row);
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- export
    public String exportJson() {
        snapshotCookies();
        return st.toString();
    }

    public void importJson(String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        st = obj;
        invalidateJwxtCache();
        if (str(st, "imei").isEmpty()) st.put("imei", UUID.randomUUID().toString());
        save();
        syncCookies(true);
    }

    // ---------------------------------------------------------------- 汇总
    static long remainMs(long deadline) {
        return Math.max(1, deadline - System.currentTimeMillis());
    }

    public JSONObject overview(boolean probe) {
        long t0 = System.currentTimeMillis();
        invalidateJwxtCache();
        JSONObject out = new JSONObject();
        put(out, "profile", st.optJSONObject("profile"));
        put(out, "session", sessionInfo());

        long tc = System.currentTimeMillis();
        JSONArray all = cards();
        long tCards = System.currentTimeMillis() - tc;
        long td = System.currentTimeMillis();
        JSONArray deep = deepLinks();
        long tDeep = System.currentTimeMillis() - td;

        JSONArray net = new JSONArray(), examCards = new JSONArray(), gradeCards = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject c = all.optJSONObject(i);
            if (c == null) continue;
            String name = str(c, "name");
            if (containsAny(name, NET_KEYS)) net.put(c);
            if (containsAny(name, EXAM_KEYS)) examCards.put(c);
            if (containsAny(name, GRADE_KEYS)) gradeCards.put(c);
            String key = str(c, "key");
            if ("card.balance".equals(key) || name.contains("校园卡")) {
                put(out, "card_balance", str(c, "value"));
                put(out, "card_unit", str(c, "unit"));
            }
            if ("mail.coremailStudent".equals(key) || name.contains("未读邮件")) {
                put(out, "mail_unread", str(c, "value"));
            }
        }
        JSONArray netLinks = filterLinks(deep, NET_KEYS);
        JSONObject netObj = new JSONObject();
        put(netObj, "status", net.length() > 0 ? "ok" : (netLinks.length() > 0 ? "partial" : "unavailable"));
        put(netObj, "cards", net);
        put(netObj, "links", netLinks);
        put(netObj, "note", net.length() > 0 ? "" : "门户未返回网费卡片，可从链接进入自助服务");
        put(out, "net", netObj);

        JSONObject scheduleObj = null;
        boolean jwOk = false;
        JSONArray examItems = new JSONArray(), gradeItems = new JSONArray();
        JSONObject rank = new JSONObject();
        JSONObject jw = new JSONObject();
        long tEnsure = 0, tTerm = 0, tParallel = 0, tSchedule = 0;
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            long te = System.currentTimeMillis();
            try { jwOk = ensureJwxt(); } catch (Exception e) { jwOk = false; }
            tEnsure = System.currentTimeMillis() - te;

            if (jwOk) {
                String term = "";
                long tt = System.currentTimeMillis();
                try { term = str(currentTerm(), "term"); } catch (Exception ignored) {}
                tTerm = System.currentTimeMillis() - tt;

                long tp = System.currentTimeMillis();
                final String termFinal = term;
                final long deadline = System.currentTimeMillis() + 15000;
                Future<?> ttF = pool.submit(() -> jwTimetable(termFinal));
                Future<JSONArray> examF = pool.submit(() -> jwExams());
                Future<JSONObject> gradeF = pool.submit(() -> jwGrades());
                try { ttF.get(remainMs(deadline), TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                try { examItems = examF.get(remainMs(deadline), TimeUnit.MILLISECONDS); } catch (Exception ignored) { examItems = new JSONArray(); }
                try {
                    JSONObject g = gradeF.get(remainMs(deadline), TimeUnit.MILLISECONDS);
                    if (g.optJSONArray("items") != null) gradeItems = g.optJSONArray("items");
                    if (g.optJSONObject("rank") != null) rank = g.optJSONObject("rank");
                } catch (Exception ignored) {}
                tParallel = System.currentTimeMillis() - tp;

                long ts = System.currentTimeMillis();
                try { scheduleObj = schedule(null); } catch (Exception ignored) {}
                tSchedule = System.currentTimeMillis() - ts;
                put(jw, "status", "ok");
            } else {
                put(jw, "status", "need_login");
                put(jw, "note", "教务未登录（需校园网/WebVPN）");
            }
        } finally {
            pool.shutdownNow();
        }

        if (scheduleObj == null) {
            try { scheduleObj = schedule(null); }
            catch (Exception e) {
                JSONObject s = new JSONObject();
                put(s, "error", e.getMessage());
                scheduleObj = s;
            }
        }
        put(out, "schedule", scheduleObj);

        JSONObject exams = new JSONObject();
        put(exams, "status", jw.optString("status", "need_login"));
        put(exams, "note", str(jw, "note"));
        put(exams, "items", examItems);
        put(exams, "cards", examCards);
        put(exams, "links", filterLinks(deep, EXAM_KEYS));
        put(out, "exams", exams);

        JSONObject grades = new JSONObject();
        put(grades, "status", jw.optString("status", "need_login"));
        put(grades, "note", str(jw, "note"));
        put(grades, "items", gradeItems);
        put(grades, "cards", gradeCards);
        put(grades, "links", filterLinks(deep, GRADE_KEYS));
        put(grades, "rank", rank);
        put(out, "grades", grades);

        JSONObject timings = new JSONObject();
        put(timings, "cards", tCards);
        put(timings, "links", tDeep);
        put(timings, "ensure", tEnsure);
        put(timings, "term", tTerm);
        put(timings, "parallel", tParallel);
        put(timings, "schedule", tSchedule);
        long total = System.currentTimeMillis() - t0;
        put(timings, "total", total);
        put(out, "timings", timings);
        android.util.Log.d("NeuTime", "overview cards=" + tCards + " links=" + tDeep
                + " ensure=" + tEnsure + " term=" + tTerm + " parallel=" + tParallel
                + " schedule=" + tSchedule + " total=" + total + " jw=" + jwOk);
        return out;
    }

    static boolean containsAny(String s, String[] keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    static JSONArray filterLinks(JSONArray all, String[] keys) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < all.length() && out.length() < 8; i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && containsAny(str(o, "name"), keys)) out.put(o);
        }
        return out;
    }

    public JSONObject sessionInfo() {
        JSONObject o = new JSONObject();
        put(o, "logged_in", !ticket().isEmpty());
        put(o, "profile", st.optJSONObject("profile"));
        String im = imei();
        put(o, "imei", im.length() > 8 ? im.substring(0, 8) + "…" : im);
        JSONObject rem = st.optJSONObject("remember");
        JSONObject prof = st.optJSONObject("profile");
        String savedName = rem == null ? "" : str(rem, "name");
        String savedXgh = rem == null ? "" : str(rem, "xgh");
        if (savedName.isEmpty() && prof != null) savedName = str(prof, "name");
        if (savedXgh.isEmpty() && prof != null) savedXgh = str(prof, "xgh");
        put(o, "remember", rem != null);
        put(o, "saved_user", rem == null ? "" : str(rem, "username"));
        put(o, "saved_name", savedName);
        put(o, "saved_xgh", savedXgh);
        put(o, "updated_at", str(st, "updated_at"));
        put(o, "demo", false);
        return o;
    }

    public String ssoUrl(String target) {
        try {
            if (ticket().isEmpty()) return "";
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    CAS_LOGIN + "?service=" + URLEncoder.encode(target, "UTF-8")).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", UA);
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            if (loc != null && loc.contains("ticket=")) return loc;
        } catch (Exception ignored) {}
        return "";
    }
}





