package com.neulite.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

/**
 * 不可见 WebView：登录后在后台走
 * 一号通 -> 应用中心 -> 教学学习 -> 校园乐跑，捕获链接后立即销毁。
 */
final class RunBinder {
    interface Callback {
        void onDone(boolean ok);
    }

    private static final String PORTAL = "https://personal.neu.edu.cn/portal";
    private static final String APP_CENTER =
            "https://personal.neu.edu.cn/portal/pages/appmarketsite/site/application/m_home";

    private final Context ctx;
    private final Api api;
    private final Callback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView web;
    private boolean done;
    private int step;
    private int tries;

    RunBinder(Context ctx, Api api, Callback callback) {
        this.ctx = ctx;
        this.api = api;
        this.callback = callback;
    }

    void cancel() {
        finish(false);
    }

    void start() {
        new Thread(() -> {
            String ticket = api.ssoUrl(PORTAL);
            final String startUrl = (ticket == null || ticket.isEmpty()) ? PORTAL : ticket;
            ui.post(() -> open(startUrl));
        }, "run-binder-ticket").start();
    }

    private void open(String url) {
        if (done) return;
        try {
            web = new WebView(ctx);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            // 清除数据/首次登录后，隐藏 WebView 的 CookieStore 是空的；把 Api 的会话 Cookie 注入。
            for (String[] c : api.webViewCookies()) {
                if (c == null || c.length < 2) continue;
                String domain = c.length > 2 ? c[2] : "";
                String path = c.length > 3 ? c[3] : "/";
                if (domain == null || domain.isEmpty()) continue;
                String host = domain.startsWith(".") ? domain.substring(1) : domain;
                String cookie = c[0] + "=" + c[1] + "; Domain=" + domain + "; Path=" + path + "; Secure";
                cm.setCookie("https://" + host + path, cookie);
            }
            cm.flush();

            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    inspect(request.getUrl().toString());
                    return false;
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    inspect(url);
                    return false;
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    inspect(url);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    inspect(url);
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onReceivedTitle(WebView view, String title) {
                    inspect(view.getUrl());
                }
            });
            web.loadUrl(url);
            ui.postDelayed(this::timeout, 45000);
        } catch (Exception e) {
            finish(false);
        }
    }

    private void inspect(String url) {
        if (done || url == null) return;
        Log.d("RunBinder", "url=" + url + " step=" + step);
        if (url.startsWith("https://tybzhtypt.neu.edu.cn/") || url.startsWith("http://tybzhtypt.neu.edu.cn/")) {
            if (url.contains("token=") && url.contains("sign=")) {
                try {
                    SecretStore.saveRunUrl(ctx, url);
                    finish(true);
                } catch (Exception e) {
                    finish(false);
                }
                return;
            }
        }
        if (url.contains("cas_login") || url.contains("/tpass/")) return;
        if (step == 0 && url.contains("personal.neu.edu.cn/portal")) {
            step = 1;
            ui.postDelayed(() -> load(APP_CENTER), 700);
            return;
        }
        if (step == 1 && url.contains("appmarketsite") && url.contains("m_home")) {
            step = 2;
            tries = 0;
            ui.postDelayed(this::clickClass, 700);
        }
    }

    private void load(String url) {
        if (!done && web != null) web.loadUrl(url);
    }

    private void clickClass() {
        if (done) return;
        String js = "(function(){const t='教学学习';const ns=Array.from(document.querySelectorAll('*')).filter(e=>e.children.length===0&&e.textContent.trim()===t);for(const el of ns){let n=el;for(let i=0;i<6&&n;i++){if(n.tagName==='A'||n.onclick||n.getAttribute('onclick')||n.getAttribute('role')==='button'||(n.className&&/item|tab|menu|category/i.test(String(n.className)))){try{n.scrollIntoView({block:'center'});}catch(e){}n.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}n=n.parentElement;}try{el.scrollIntoView({block:'center'});}catch(e){}el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}return false;})()";
        eval(js, v -> ui.postDelayed(this::clickRun, 900));
    }

    private void clickRun() {
        if (done) return;
        if (tries++ > 15) {
            finish(false);
            return;
        }
        String js = "(function(){const t='校园乐跑';const ns=Array.from(document.querySelectorAll('*')).filter(e=>e.children.length===0&&e.textContent.trim()===t);for(const el of ns){let n=el;for(let i=0;i<6&&n;i++){if(n.tagName==='A'||n.onclick||n.getAttribute('onclick')||n.getAttribute('role')==='button'||(n.className&&/item|card|app/i.test(String(n.className)))){try{n.scrollIntoView({block:'center'});}catch(e){}n.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}n=n.parentElement;}try{el.scrollIntoView({block:'center'});}catch(e){}el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}return false;})()";
        eval(js, v -> {
            if (done) return;
            if (!"true".equals(v)) {
                if (tries % 4 == 0) clickClass();
                else ui.postDelayed(this::clickRun, 900);
            }
        });
    }

    private void eval(String js, android.webkit.ValueCallback<String> cb) {
        try {
            if (web == null) {
                if (cb != null) cb.onReceiveValue("false");
                return;
            }
            web.evaluateJavascript(js, cb);
        } catch (Exception e) {
            if (cb != null) cb.onReceiveValue("false");
        }
    }

    private void timeout() {
        finish(false);
    }

    private void finish(boolean ok) {
        Log.d("RunBinder", "finish ok=" + ok + " step=" + step);
        if (done) return;
        done = true;
        ui.removeCallbacksAndMessages(null);
        try {
            if (web != null) {
                web.stopLoading();
                web.destroy();
            }
        } catch (Exception ignored) {}
        web = null;
        if (callback != null) callback.onDone(ok);
    }
}
