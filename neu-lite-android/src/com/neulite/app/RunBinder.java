package com.neulite.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

/**
 * 后台准备校园乐跑链接：
 * 优先校园网直连；直连失败由 MainActivity 再用 WebVPN 重试。
 */
final class RunBinder {
    interface Callback {
        void onDone(boolean ok, String error);
    }

    private static final String PORTAL = "https://personal.neu.edu.cn/portal";
    private static final String APP_CENTER =
            "https://personal.neu.edu.cn/portal/pages/appmarketsite/site/application/m_home";

    private final Context ctx;
    private final Api api;
    private final boolean vpnOnly;
    private final Callback callback;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private WebView web;
    private boolean done;
    private int step;
    private int tries;
    private String portalUrl = PORTAL;
    private String appCenterUrl = APP_CENTER;
    private final int timeoutMs;
    private final java.util.List<WebView> popups = new java.util.ArrayList<>();

    RunBinder(Context ctx, Api api, boolean vpnOnly, Callback callback) {
        this.ctx = ctx;
        this.api = api;
        this.vpnOnly = vpnOnly;
        this.callback = callback;
        this.timeoutMs = vpnOnly ? 45000 : 25000;
    }

    void cancel() {
        finish(false, "已取消");
    }

    void start() {
        new Thread(() -> {
            try {
                if (vpnOnly) {
                    if (!api.webvpnLogin()) {
                        finish(false, "无法建立 WebVPN 连接");
                        return;
                    }
                    String base = Api.WV + "/https/" + Api.webvpnToken("personal.neu.edu.cn");
                    portalUrl = base + "/portal";
                    appCenterUrl = base + "/portal/pages/appmarketsite/site/application/m_home";
                    final String startUrl = portalUrl;
                    ui.post(() -> open(startUrl));
                    return;
                }
                String ticket = api.ssoUrl(PORTAL);
                final String startUrl = (ticket == null || ticket.isEmpty()) ? PORTAL : ticket;
                ui.post(() -> open(startUrl));
            } catch (Exception e) {
                finish(false, e.getMessage() == null ? "准备失败" : e.getMessage());
            }
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
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setSupportMultipleWindows(true);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            // 清空/首次登录后，隐藏 WebView 的 CookieStore 是空的；把 Api 的会话 Cookie 注入。
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
                    super.onPageStarted(view, url, favicon);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    inspect(url);
                    super.onPageFinished(view, url);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request != null && request.isForMainFrame()) {
                        String desc = error == null ? "网络错误" : String.valueOf(error.getDescription());
                        finish(false, "网络访问失败：" + desc);
                    }
                }
            });
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onCreateWindow(WebView view, boolean isDialog,
                                             boolean isUserGesture, Message resultMsg) {
                    try {
                        WebView popup = new WebView(ctx);
                        WebSettings ps = popup.getSettings();
                        ps.setJavaScriptEnabled(true);
                        ps.setDomStorageEnabled(true);
                        ps.setDatabaseEnabled(true);
                        ps.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                        popup.setWebViewClient(web.getWebViewClient());
                        popup.setWebChromeClient(web.getWebChromeClient());
                        popups.add(popup);
                        WebView.WebViewTransport transport =
                                (WebView.WebViewTransport) resultMsg.obj;
                        transport.setWebView(popup);
                        resultMsg.sendToTarget();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }

                @Override
                public void onReceivedTitle(WebView view, String title) {
                    inspect(view.getUrl());
                }
            });
            web.loadUrl(url);
            ui.postDelayed(this::timeout, timeoutMs);
        } catch (Exception e) {
            finish(false, e.getMessage() == null ? "无法初始化校园乐跑页面" : e.getMessage());
        }
    }

    private void inspect(String url) {
        if (done || url == null) return;
        Log.d("RunBinder", "url=" + url + " step=" + step + " vpn=" + vpnOnly);
        boolean runPage = url.contains("tybzhtypt.neu.edu.cn") || url.contains("bdlp_h5_fitness_test");
        if (runPage && url.contains("token=") && url.contains("sign=")) {
            try {
                SecretStore.saveRunUrl(ctx, url);
                finish(true, "");
            } catch (Exception e) {
                finish(false, "保存校园乐跑链接失败");
            }
            return;
        }
        // CAS 初始跳转 / 登录页：等待它跳到门户；若超时由 MainActivity 切 WebVPN。
        if (url.contains("cas_login") || url.contains("/tpass/")) return;

        boolean portal = url.contains("/portal") && !url.contains("appmarketsite");
        if (step == 0 && portal) {
            step = 1;
            ui.postDelayed(() -> load(appCenterUrl), 700);
            return;
        }
        boolean appCenter = url.contains("appmarketsite") || url.contains("/m_home");
        if (step <= 1 && appCenter) {
            step = 2;
            tries = 0;
            ui.postDelayed(this::clickClass, 900);
        }
    }

    private void load(String url) {
        if (!done && web != null) web.loadUrl(url);
    }

    private void clickClass() {
        if (done) return;
        String js = "(function(){const t='教学学习';const docs=[document];for(const f of document.querySelectorAll('iframe')){try{const d=f.contentDocument;if(d)docs.push(d);}catch(e){}}const ns=docs.flatMap(d=>Array.from(d.querySelectorAll('*'))).filter(e=>e.children.length===0&&e.textContent.trim()===t);for(const el of ns){let n=el;for(let i=0;i<6&&n;i++){if(n.tagName==='A'||n.onclick||n.getAttribute('onclick')||n.getAttribute('role')==='button'||(n.className&&/item|tab|menu|category/i.test(String(n.className)))){try{n.scrollIntoView({block:'center'});}catch(e){}n.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}n=n.parentElement;}try{el.scrollIntoView({block:'center'});}catch(e){}el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}return false;})()";
        eval(js, v -> ui.postDelayed(this::clickRun, 1100));
    }

    private void clickRun() {
        if (done) return;
        if (tries++ > 18) {
            finish(false, "门户中未找到校园乐跑入口");
            return;
        }
        String js = "(function(){const t='校园乐跑';const docs=[document];for(const f of document.querySelectorAll('iframe')){try{const d=f.contentDocument;if(d)docs.push(d);}catch(e){}}const ns=docs.flatMap(d=>Array.from(d.querySelectorAll('*'))).filter(e=>e.children.length===0&&e.textContent.trim()===t);for(const el of ns){let n=el;for(let i=0;i<6&&n;i++){if(n.tagName==='A'||n.onclick||n.getAttribute('onclick')||n.getAttribute('role')==='button'||(n.className&&/item|card|app/i.test(String(n.className)))){try{n.scrollIntoView({block:'center'});}catch(e){}n.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}n=n.parentElement;}try{el.scrollIntoView({block:'center'});}catch(e){}el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true;}return false;})()";
        eval(js, v -> {
            if (done) return;
            if (!"true".equals(v)) {
                if (tries % 4 == 0) clickClass();
                else ui.postDelayed(this::clickRun, 1100);
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
        finish(false, "准备超时，请连接校园网或 WebVPN 后重试");
    }

    private void finish(boolean ok, String error) {
        Log.d("RunBinder", "finish ok=" + ok + " step=" + step + " vpn=" + vpnOnly + " error=" + error);
        if (done) return;
        done = true;
        ui.removeCallbacksAndMessages(null);
        try {
            for (WebView p : popups) {
                try { p.stopLoading(); p.destroy(); } catch (Exception ignored) {}
            }
            popups.clear();
            if (web != null) {
                web.stopLoading();
                web.destroy();
            }
        } catch (Exception ignored) {}
        web = null;
        if (callback != null) callback.onDone(ok, error == null ? "" : error);
    }
}
