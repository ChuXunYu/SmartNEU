package com.neulite.app;

import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.KeyEvent;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.content.res.Configuration;
import android.database.Cursor;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Comparator;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT = 1001;
    private static final int REQ_ATTACH = 1002;
    private static final int MAX_ATTACH = 3;
    private static final long MAX_ATTACH_FILE = 10L * 1024 * 1024;
    private static final long MAX_ATTACH_TOTAL = 12L * 1024 * 1024;
    private WebView web;
    private Api api;
    private String pendingImportPassword = "";
    private RunBinder runBinder;
    private final List<Uri> attachments = new ArrayList<>();
    private boolean backPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = new Api(this);
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
        trimWebCacheIfNeeded();
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
        if (backPending) return;
        if (web == null) { super.onBackPressed(); return; }
        backPending = true;
        web.evaluateJavascript(
                "(function(){try{return window.handleBack?window.handleBack():false}catch(e){return false}})()",
                value -> {
                    backPending = false;
                    if (!"true".equals(value)) finishAndRemoveTask();
                });
    }

    private long dirSize(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) n += dirSize(x);
        return n;
    }

    /** WebView 缓存超过 24MB 时自动清一次，避免长期浏览缴费/邮箱页累积。 */
    private void trimWebCacheIfNeeded() {
        new Thread(() -> {
            long size = dirSize(getCacheDir());
            File ext = getExternalCacheDir();
            if (ext != null) size += dirSize(ext);
            if (size > 24L * 1024 * 1024) {
                runOnUiThread(() -> { if (web != null) web.clearCache(true); });
            }
        }).start();
    }

    // ------------------------------------------------------------ 状态导出
    public void pickAttachments() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_ATTACH);
    }

    public void clearAttachments() {
        attachments.clear();
    }

    public String attachmentsJson() {
        JSONArray out = new JSONArray();
        for (Uri u : attachments) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", displayName(u));
                o.put("size", querySize(u));
                o.put("mime", String.valueOf(getContentResolver().getType(u)));
                out.put(o);
            } catch (Exception ignored) {}
        }
        return out.toString();
    }

    public List<SmtpClient.Attachment> readAttachments() throws Exception {
        List<SmtpClient.Attachment> out = new ArrayList<>();
        long total = 0;
        for (Uri u : attachments) {
            byte[] data = readUri(u, MAX_ATTACH_FILE);
            total += data.length;
            if (total > MAX_ATTACH_TOTAL) throw new Exception("附件合计不能超过 12MB");
            String mime = getContentResolver().getType(u);
            out.add(new SmtpClient.Attachment(displayName(u),
                    mime == null || mime.isEmpty() ? "application/octet-stream" : mime, data));
        }
        return out;
    }

    private byte[] readUri(Uri uri, long max) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取附件");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new Exception("单个附件不能超过 10MB");
            }
            return out.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "attachment";
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0) return c.getLong(i);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public String getCampusRunUrl() {
        return RunUrl.refresh(SecretStore.getRunUrl(this));
    }

    public String getMailPassword() {
        return SecretStore.get(this);
    }

    public boolean hasMailPassword() {
        return SecretStore.has(this);
    }

    public void saveMailPassword(String password) {
        try {
            SecretStore.save(this, password);
        } catch (Exception ignored) {}
    }

    public void clearMailPassword() {
        SecretStore.clear(this);
    }

    public String exportState(String password) {
        try {
            byte[] data = CryptoBox.encrypt(api.exportJson().getBytes(StandardCharsets.UTF_8), password);
            String path = writeDownload("备份", "neu-lite-state", "neubak", "application/octet-stream", data);
            cleanupLegacyExports();
            return path;
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /** 加密导出课表/考试。文件扩展名 .neucsv，只有本 App 输入密码才能还原。 */
    public String exportEncrypted(String kind, String password) {
        try {
            boolean exams = "exams".equals(kind);
            boolean state = "state".equals(kind);
            String text = state ? api.exportJson() : (exams ? api.examsCsv() : api.timetableCsv());
            String folder = state ? "备份" : (exams ? "考试" : "课表");
            String base = state ? "neu-lite-state" : (exams ? "考试安排" : api.scheduleFileName());
            String ext = state ? "neubak" : "neucsv";
            byte[] data = CryptoBox.encrypt(text.getBytes(StandardCharsets.UTF_8), password);
            String path = writeDownload(folder, base, ext, "application/octet-stream", data);
            if (state) cleanupLegacyExports();
            return path;
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /** 兼容 Excel / WakeUP 的显式明文 CSV，两个文件一起导出。 */
    public String exportPlainCsvAll() {
        String a = exportCsv("timetable");
        String b = exportCsv("exams");
        if (a.startsWith("ERR:") || b.startsWith("ERR:")) return a.startsWith("ERR:") ? a : b;
        return "课表：" + a + "；考试：" + b;
    }

    // ------------------------------------------------------------ CSV 导出
    public String exportCsv(String kind) {
        try {
            boolean exams = "exams".equals(kind);
            String csv = exams ? api.examsCsv() : api.timetableCsv();
            String folder = exams ? "考试" : "课表";
            String base = exams ? "考试安排" : api.scheduleFileName();
            return writeDownload(folder, base, "csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /**
     * 所有用户可见导出统一写入：
     * Android 10+  下载/neu-lite/{课表|考试|备份}/名称-时间.csv|json
     * Android 9-   应用专属外部目录/exports/...
     */
    private String writeDownload(String folder, String base, String ext, String mime, byte[] data) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String name = base + "-" + stamp + "." + ext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/neu-lite/" + folder);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("无法创建下载文件");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("无法写入下载文件");
                os.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            pruneExports(folder, base, 30);
            return "下载/neu-lite/" + folder + "/" + name;
        }
        File dir = new File(getExternalFilesDir(null), "exports/" + folder);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建导出目录");
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        pruneExports(folder, base, 30);
        return f.getAbsolutePath();
    }

    /** 每个用途最多保留最近 30 份导出，避免长期累积。 */
    private void pruneExports(String folder, String base, int keep) {
        if (Build.VERSION.SDK_INT >= 29) {
            String path = Environment.DIRECTORY_DOWNLOADS + "/neu-lite/" + folder + "%";
            String[] proj = {MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_ADDED};
            List<long[]> rows = new ArrayList<>();
            try (Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    proj, MediaStore.Downloads.RELATIVE_PATH + " LIKE ?", new String[]{path},
                    MediaStore.Downloads.DATE_ADDED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(1);
                        if (name != null && name.startsWith(base)) {
                            rows.add(new long[]{c.getLong(0), c.getLong(2)});
                        }
                    }
                }
            }
            if (rows.size() <= keep) return;
            rows.sort(Comparator.comparingLong(a -> a[1]));
            int remove = rows.size() - keep;
            for (int i = 0; i < remove; i++) {
                Uri u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.get(i)[0]);
                getContentResolver().delete(u, null, null);
            }
        } else {
            File dir = new File(getExternalFilesDir(null), "exports/" + folder);
            File[] files = dir.listFiles();
            if (files == null) return;
            List<File> list = new ArrayList<>();
            for (File f : files) if (f.getName().startsWith(base)) list.add(f);
            if (list.size() <= keep) return;
            list.sort(Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < list.size() - keep; i++) list.get(i).delete();
        }
    }

    /** 旧版把状态文件写在 Android/data/.../files/export，迁移完成后清理。 */
    private void cleanupLegacyExports() {
        File old = new File(getExternalFilesDir(null), "export");
        File[] files = old.listFiles();
        if (files != null) for (File f : files) f.delete();
        old.delete();
    }

    /** 打开统一的导出目录。 */
    public void openExports() {
        runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    Uri uri = DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download/neu-lite");
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(uri, "vnd.android.document/directory");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } else {
                    toast("导出文件位于：" + new File(getExternalFilesDir(null), "exports").getAbsolutePath());
                }
            } catch (Exception e) {
                toast("请用系统文件管理器打开 下载/neu-lite");
            }
        });
    }

    /** 只清网页临时缓存，不动登录状态和用户导出文件。 */
    public void clearWebCache() {
        runOnUiThread(() -> {
            if (web != null) web.clearCache(true);
            toast("已清理网页临时缓存");
        });
    }

    // ------------------------------------------------------------ 状态导入
    public void requestImport(String password) {
        pendingImportPassword = password == null ? "" : password;
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH) {
            attachments.clear();
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData cd = data.getClipData();
                    for (int i = 0; i < cd.getItemCount() && attachments.size() < MAX_ATTACH; i++) {
                        Uri u = cd.getItemAt(i).getUri();
                        if (u != null) attachments.add(u);
                    }
                } else if (data.getData() != null) {
                    attachments.add(data.getData());
                }
            }
            return;
        }
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] bytes = out.toByteArray();
            if (CryptoBox.isEncrypted(bytes)) {
                if (pendingImportPassword.isEmpty()) throw new Exception("这是加密备份，请输入导出时设置的密码");
                bytes = CryptoBox.decrypt(bytes, pendingImportPassword);
            }
            api.importJson(new String(bytes, StandardCharsets.UTF_8));
            toast("导入成功");
            if (web != null) {
                web.evaluateJavascript("try{localStorage.clear()}catch(e){}", value -> {
                    if (web != null) web.reload();
                });
            }
        } catch (Exception e) {
            toast("导入失败: " + e.getMessage());
        } finally {
            pendingImportPassword = "";
        }
    }

    // ------------------------------------------------------------ 免登跳转
    /** 登录成功后调用；全程无可见界面，成功后加密保存乐跑链接。 */
    public void startAutoBindRun() {
        runOnUiThread(() -> {
            if (runBinder != null) return;
            runBinder = new RunBinder(this, api, ok -> {
                runBinder = null;
                if (ok) toast("校园乐跑已自动绑定");
            });
            runBinder.start();
        });
    }

    public void openPay(String url, List<String[]> cookies) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(this, PaymentActivity.class);
        i.putExtra("url", url);
        ArrayList<String> lines = new ArrayList<>();
        if (cookies != null) {
            for (String[] c : cookies) {
                if (c == null || c.length < 2) continue;
                lines.add(c[0] + "\t" + c[1] + "\t" + (c.length > 2 ? c[2] : "")
                        + "\t" + (c.length > 3 ? c[3] : "/"));
            }
        }
        i.putStringArrayListExtra("cookies", lines);
        runOnUiThread(() -> startActivity(i));
    }

    public void openWeb(String url, String title, List<String[]> cookies) {
        if (url == null || url.isEmpty()) return;
        Intent i = new Intent(this, PaymentActivity.class);
        i.putExtra("url", url);
        i.putExtra("title", title == null ? "网页" : title);
        ArrayList<String> lines = new ArrayList<>();
        if (cookies != null) {
            for (String[] c : cookies) {
                if (c == null || c.length < 2) continue;
                lines.add(c[0] + "\t" + c[1] + "\t" + (c.length > 2 ? c[2] : "")
                        + "\t" + (c.length > 3 ? c[3] : "/"));
            }
        }
        i.putStringArrayListExtra("cookies", lines);
        runOnUiThread(() -> startActivity(i));
    }

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

    @Override
    protected void onDestroy() {
        if (runBinder != null) {
            runBinder.cancel();
            runBinder = null;
        }
        super.onDestroy();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }
}
