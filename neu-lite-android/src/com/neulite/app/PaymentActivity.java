package com.neulite.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** 全屏支付 WebView：只承载学校缴费页，不注入任何 JS。 */
public class PaymentActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String url = getIntent().getStringExtra("url");
        String pageTitle = getIntent().getStringExtra("title");
        if (pageTitle == null || pageTitle.isEmpty()) pageTitle = "缴费";
        ArrayList<String> cookies = getIntent().getStringArrayListExtra("cookies");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(255, 253, 248));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(Color.rgb(255, 253, 248));

        TextView title = new TextView(this);
        title.setText(pageTitle);
        title.setTextSize(15);
        title.setTextColor(Color.rgb(23, 20, 17));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(12);
        close.setTextColor(Color.rgb(168, 58, 43));
        close.setPadding(dp(14), dp(6), dp(2), dp(6));
        close.setOnClickListener(v -> finish());
        bar.addView(close);
        root.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(224, 216, 203));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString());
        web.setBackgroundColor(Color.rgb(255, 253, 248));

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (cookies != null) {
            for (String row : cookies) {
                String[] p = row.split("\t", -1);
                if (p.length < 2) continue;
                String name = p[0], value = p[1];
                String domain = p.length > 2 ? p[2] : "";
                String path = p.length > 3 && !p[3].isEmpty() ? p[3] : "/";
                String host = domain.startsWith(".") ? domain.substring(1) : domain;
                if (host.isEmpty()) continue;
                String cookie = name + "=" + value + "; Domain=" + (domain.startsWith(".") ? domain : host)
                        + "; Path=" + path + "; Secure";
                try { cm.setCookie("https://" + host, cookie); } catch (Exception ignored) {}
            }
        }
        cm.flush();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                android.util.Log.d("NeuPay", "title=" + title + " url=" + view.getUrl());
            }
        });

        if (url != null && !url.isEmpty()) web.loadUrl(url);
    }

    private boolean handle(String url) {
        if (url == null) return true;
        if (url.startsWith("http://") || url.startsWith("https://")) return false;
        try {
            Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
        return true;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
