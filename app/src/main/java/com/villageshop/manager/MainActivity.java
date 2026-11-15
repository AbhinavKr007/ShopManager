package com.villageshop.manager;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shop Manager: the web app in assets/index.html running in a WebView, with
 * - data saved to a file in the app's private storage (included in Android's own Google backup)
 * - automatic backup to a file in the user's Google Drive, chosen once via the system file picker
 * - restore from a backup file, printing bills, saving CSV/bill files, WhatsApp links
 */
public class MainActivity extends Activity {

    private static final int REQ_CONNECT_DRIVE = 11;
    private static final int REQ_RESTORE = 12;
    private static final int REQ_SAVE_FILE = 13;
    private static final long AUTO_BACKUP_DELAY_MS = 2 * 60 * 1000L;

    private WebView web;
    private WebView printView; // kept so it is not garbage-collected while printing
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object fileLock = new Object();
    private volatile boolean changedSinceBackup = false;
    private String pendingFileData;

    private final Runnable autoBackup = () -> backupToDrive(true);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("shop", MODE_PRIVATE);
        changedSinceBackup = prefs.getBoolean("dirty", false);

        getWindow().setStatusBarColor(Color.parseColor("#1E3A8A"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#1E3A8A"));
        setContentView(web);

        // Keep content clear of the status bar / navigation bar / keyboard on all Android versions.
        web.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternal(u);
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("window.__back?window.__back():false", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("typeof saveNow==='function'&&saveLocal.t&&saveNow()", null);
        // Give the save a moment to land, then push to Drive.
        main.removeCallbacks(autoBackup);
        main.postDelayed(autoBackup, 1500);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(autoBackup);
        io.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- storage

    private File dataFile() { return new File(getFilesDir(), "shop.json"); }

    private String readData() {
        synchronized (fileLock) {
            File f = dataFile();
            if (!f.exists()) return "";
            try (InputStream in = new FileInputStream(f)) {
                return readAll(in);
            } catch (Exception e) {
                return "";
            }
        }
    }

    private void writeData(String json) throws Exception {
        synchronized (fileLock) {
            File tmp = new File(getFilesDir(), "shop.json.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            File f = dataFile();
            if (!tmp.renameTo(f)) {
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    // ---------------------------------------------------------------- Google Drive backup

    private void pickDriveFile() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "ShopBackup.json");
        try {
            startActivityForResult(i, REQ_CONNECT_DRIVE);
        } catch (ActivityNotFoundException e) {
            toast("No file picker found on this phone");
        }
    }

    /** Writes the current data file to the chosen Drive file. Runs off the main thread. */
    private void backupToDrive(boolean silent) {
        final String uriStr = prefs.getString("backupUri", null);
        if (uriStr == null) {
            if (!silent) pickDriveFile();
            return;
        }
        if (silent && !changedSinceBackup) return;
        io.execute(() -> {
            String data = readData();
            if (data.isEmpty()) { if (!silent) notifyJs("Nothing to back up yet"); return; }
            Uri uri = Uri.parse(uriStr);
            try {
                writeToUri(uri, data);
                changedSinceBackup = false;
                prefs.edit().putLong("lastBackup", System.currentTimeMillis())
                        .putBoolean("dirty", false).remove("backupError").apply();
                if (!silent) notifyJs("Backed up to Google Drive");
                else notifyJs(null);
            } catch (Exception e) {
                String msg = e instanceof SecurityException
                        ? "Permission to the Drive file was lost — tap Change file"
                        : "Could not reach the backup file (" + e.getClass().getSimpleName() + ")";
                prefs.edit().putString("backupError", msg).apply();
                notifyJs(silent ? null : msg);
            }
        });
    }

    private void writeToUri(Uri uri, String data) throws Exception {
        OutputStream out = null;
        try {
            try {
                out = getContentResolver().openOutputStream(uri, "wt");
            } catch (Exception notSupported) {
                out = null;
            }
            if (out == null) out = getContentResolver().openOutputStream(uri, "w");
            if (out == null) throw new IllegalStateException("no stream");
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "ShopBackup.json";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_SAVE_FILE) pendingFileData = null;
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_CONNECT_DRIVE) {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception e) {
                toast("This location can't be used for automatic backup. Please choose Google Drive.");
                return;
            }
            prefs.edit().putString("backupUri", uri.toString())
                    .putString("backupName", displayName(uri))
                    .putString("backupProvider", uri.getAuthority())
                    .remove("backupError").apply();
            changedSinceBackup = true;
            backupToDrive(false);
            notifyJs(null);
        } else if (requestCode == REQ_RESTORE) {
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException();
                    final String text = readAll(in);
                    main.post(() -> web.evaluateJavascript("window.__restore(" + JSONObject.quote(text) + ")", null));
                } catch (Exception e) {
                    notifyJs("Could not open that file");
                }
            });
        } else if (requestCode == REQ_SAVE_FILE) {
            final String content = pendingFileData;
            pendingFileData = null;
            if (content == null) return;
            io.execute(() -> {
                try {
                    writeToUri(uri, content);
                    notifyJs("File saved");
                } catch (Exception e) {
                    notifyJs("Could not save the file");
                }
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private void notifyJs(final String message) {
        main.post(() -> web.evaluateJavascript(
                "window.__native&&window.__native('event'," + (message == null ? "null" : JSONObject.quote(message)) + ")", null));
    }

    private void toast(String m) { main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    private void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app found to open this link");
        }
    }

    // ---------------------------------------------------------------- JS bridge

    private class Bridge {
        @JavascriptInterface
        public String load() { return readData(); }

        @JavascriptInterface
        public void save(String json) {
            try {
                writeData(json);
                changedSinceBackup = true;
                prefs.edit().putBoolean("dirty", true).apply();
                main.removeCallbacks(autoBackup);
                main.postDelayed(autoBackup, AUTO_BACKUP_DELAY_MS);
            } catch (Exception e) {
                toast("Could not save data on the phone!");
            }
        }

        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("connected", prefs.getString("backupUri", null) != null);
                o.put("name", prefs.getString("backupName", ""));
                o.put("provider", prefs.getString("backupProvider", ""));
                o.put("last", prefs.getLong("lastBackup", 0));
                o.put("error", prefs.getString("backupError", ""));
                o.put("pending", changedSinceBackup);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void connectDrive() { main.post(MainActivity.this::pickDriveFile); }

        @JavascriptInterface
        public void backupNow() {
            main.post(() -> {
                changedSinceBackup = true;
                backupToDrive(false);
            });
        }

        @JavascriptInterface
        public void restore() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try {
                    startActivityForResult(i, REQ_RESTORE);
                } catch (ActivityNotFoundException e) {
                    toast("No file picker found on this phone");
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String data, String mime) {
            main.post(() -> {
                pendingFileData = data;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "text/plain" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    toast("No file picker found on this phone");
                }
            });
        }

        @JavascriptInterface
        public void printHtml(String html, String jobName) {
            main.post(() -> {
                printView = new WebView(MainActivity.this);
                printView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        if (pm == null) { toast("Printing not available"); return; }
                        pm.print(jobName, view.createPrintDocumentAdapter(jobName), new PrintAttributes.Builder().build());
                    }
                });
                printView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Shop", text));
            });
        }

        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Share"));
            });
        }
    }
}
