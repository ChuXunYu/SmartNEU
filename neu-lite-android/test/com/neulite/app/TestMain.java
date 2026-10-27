package com.neulite.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** 桌面协议自测：验证信封 + 真实账号的课表/考试/成绩接口。 */
public class TestMain {
    static void dumpCookies(String tag) {
        System.out.println("cookies " + tag + ":");
        java.net.CookieStore cs = ((java.net.CookieManager) java.net.CookieHandler.getDefault()).getCookieStore();
        for (java.net.HttpCookie c : cs.getCookies()) {
            String v = c.getValue() == null ? "" : c.getValue();
            System.out.println("  " + c.getName() + " dom=" + c.getDomain() + " path=" + c.getPath()
                    + " val=" + (v.length() > 14 ? v.substring(0, 14) + "…" : v));
        }
    }

    public static void main(String[] args) throws Exception {
        Api api = new Api(new Context());
        System.out.println("imei = " + api.imei());

        String user = System.getenv("STUDENT_ID");
        String pass = System.getenv("STUDENT_PASSWORD");
        if (user == null || pass == null || user.isEmpty()) {
            System.out.println("无 STUDENT_ID/STUDENT_PASSWORD，跳过真实登录");
            return;
        }
        JSONObject login = api.login(user, pass, false);
        System.out.println("login = " + login.optString("status")
                + " name=" + login.optJSONObject("profile").optString("name"));
        dumpCookies("after login");

        System.out.println("jwxtSso = " + api.jwxtSso());
        dumpCookies("after sso");

        JSONObject sch = api.schedule(null);
        JSONArray evs = sch.optJSONArray("events");
        System.out.println("schedule start=" + sch.optString("start") + " week=" + sch.optInt("week") + " events=" + (evs == null ? 0 : evs.length()));
        if (evs != null && evs.length() > 0) System.out.println("  first event: " + evs.optJSONObject(0));

        JSONArray exams = api.jwExams();
        System.out.println("exams = " + exams.length());
        if (exams.length() > 0) System.out.println("  first exam: " + exams.optJSONObject(0));

        JSONObject g = api.jwGrades();
        JSONArray items = g.optJSONArray("items");
        JSONObject rank = g.optJSONObject("rank");
        System.out.println("grades = " + (items == null ? 0 : items.length())
                + " gpa=" + (rank == null ? "" : rank.optString("gpa"))
                + " major_rank=" + (rank == null ? "" : rank.optString("major_rank")));
        if (items != null && items.length() > 0) System.out.println("  first grade: " + items.optJSONObject(0));

        String ec = api.examsCsv();
        String tc = api.timetableCsv();
        System.out.println("examsCsv=" + ec.length() + " timetableCsv=" + tc.length());
        System.out.println("examHead=" + ec.split("\r\n")[1].substring(0, Math.min(90, ec.split("\r\n")[1].length())));
        System.out.println("SELFTEST DONE");
    }
}
