package com.neulite.app;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** 校园乐跑 H5 登录链接：保留 token，刷新 nonce/timestamp/sign。 */
final class RunUrl {
    private static final String SIGN_KEY = "rDJiNB9j7vD2";

    private RunUrl() {}

    static String refresh(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        int q = raw.indexOf('?');
        if (q < 0) return "";
        String base = raw.substring(0, q);
        Map<String, String> p = new LinkedHashMap<>();
        for (String pair : raw.substring(q + 1).split("&")) {
            int e = pair.indexOf('=');
            if (e <= 0) continue;
            try {
                p.put(URLDecoder.decode(pair.substring(0, e), "UTF-8"),
                        URLDecoder.decode(pair.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        if (p.get("token") == null || p.get("student_num") == null) return "";
        p.remove("sign");
        p.put("nonce", String.valueOf(100000 + new Random().nextInt(900000)));
        p.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));

        StringBuilder signSrc = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(p).entrySet()) {
            signSrc.append(e.getKey()).append(e.getValue());
        }
        p.put("sign", md5(signSrc + SIGN_KEY));

        StringBuilder out = new StringBuilder(base).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (!first) out.append('&');
            first = false;
            try {
                out.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=')
                        .append(URLEncoder.encode(e.getValue(), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
