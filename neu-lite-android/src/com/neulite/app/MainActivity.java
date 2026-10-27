package com.neulite.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.res.Configuration;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 1001;
    private WebView web;
    private Api api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
        if (getIntent() != null && getIntent().getBooleanExtra("webview_debug", false)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int paper = dark ? 0xFF17140F : 0xFFF4EEE2;
        int uiFlags = dark ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUserAgentString(Api.UA);
        s.setTextZoom(100);
        web.setBackgroundColor(paper);
        web.addJavascriptInterface(new Bridge(this, api), "NEU");
        setContentView(web);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (getIntent() != null && getIntent().getBooleanExtra("run_refresh", false)) {
                    view.postDelayed(() -> view.evaluateJavascript(
                            "window.refresh&&window.refresh()", null), 1200);
                }
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        if (getIntent() != null && getIntent().getBooleanExtra("run_refresh", false)) {
            web.postDelayed(() -> web.evaluateJavascript(
                    "window.refresh&&window.refresh()", null), 2500);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------ 状态导出
    public String exportState() {
        try {
            File dir = new File(getExternalFilesDir(null), "export");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
            File f = new File(dir, "neu-lite-state.json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(api.exportJson().getBytes(StandardCharsets.UTF_8));
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    // ------------------------------------------------------------ CSV 导出
    public String exportCsv(String kind) {
        try {
            String csv = "exams".equals(kind) ? api.examsCsv() : api.timetableCsv();
            String name = ("exams".equals(kind) ? "考试安排" : "课表") + ".csv";
            return writeCsv(name, csv.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String writeCsv(String name, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/neu-lite");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("无法创建下载文件");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("无法写入下载文件");
                os.write(data);
            }
            return "下载/neu-lite/" + name;
        }
        File dir = new File(getExternalFilesDir(null), "export");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建导出目录");
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f.getAbsolutePath();
    }

    // ------------------------------------------------------------ 状态导入
    public void requestImport() {
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_IMPORT);
            } catch (Exception e) {
                toast("无法打开文件选择器");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            api.importJson(sb.toString());
            toast("导入成功");
            if (web != null) web.reload();
        } catch (Exception e) {
            toast("导入失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 免登跳转
    public void openSso(String target) {
        if (target == null || target.isEmpty()) return;
        if (!target.startsWith("https://")) return;
        runOnUiThread(() -> {
            Toast.makeText(this, "正在获取免登票据…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String resolved = target.contains("jwxt.neu.edu.cn") ? api.webvpnJwxtUrl() : null;
                if (resolved == null) resolved = api.ssoUrl(target);
                final String url = resolved;
                runOnUiThread(() -> {
                    if (url == null || url.isEmpty()) {
                        toast("获取免登票据失败（TGT 可能已失效）");
                        return;
                    }
                    openExternal(url);
                });
            }).start();
        });
    }

    public void openExternal(String url) {
        runOnUiThread(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                toast("没有可用的浏览器");
            }
        });
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
