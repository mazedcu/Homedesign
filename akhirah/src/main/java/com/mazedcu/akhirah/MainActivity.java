package com.mazedcu.akhirah;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.provider.DocumentsContract;
import android.database.Cursor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    /** Exposed to the page as window.Android — saves exported files to Downloads. */
    class Bridge {
        /** Let the user pick a folder (e.g. one inside Google Drive) for silent backups. */
        @JavascriptInterface
        public void pickBackupFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(i, 78);
            });
        }

        @JavascriptInterface
        public String backupFolder() {
            return getSharedPreferences("lb", 0).getString("treeName", "");
        }

        @JavascriptInterface
        public void clearBackupFolder() {
            getSharedPreferences("lb", 0).edit().remove("tree").remove("treeName").apply();
        }

        /** Writes (overwriting) a file in the chosen folder. Returns "" on success, else an error. */
        @JavascriptInterface
        public String folderBackup(String name, String b64) {
            try {
                String t = getSharedPreferences("lb", 0).getString("tree", "");
                if (t.isEmpty()) return "No folder chosen";
                Uri tree = Uri.parse(t);
                String docId = DocumentsContract.getTreeDocumentId(tree);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
                Uri target = null;
                Cursor c = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        if (name.equals(c.getString(1))) { target = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); break; }
                    }
                    c.close();
                }
                if (target == null) target = DocumentsContract.createDocument(getContentResolver(), parent, "application/json", name);
                if (target == null) return "Could not create file";
                OutputStream o = getContentResolver().openOutputStream(target, "wt");
                o.write(Base64.decode(b64, Base64.DEFAULT)); o.close();
                return "";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        /** Silent auto-backup: overwrites one file per name in Downloads/LifeApp. */
        @JavascriptInterface
        public boolean autoBackup(String name, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    Uri u = null;
                    android.database.Cursor c = getContentResolver().query(col,
                        new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                        new String[]{name, Environment.DIRECTORY_DOWNLOADS + "/LifeApp%"}, null);
                    if (c != null) {
                        if (c.moveToFirst()) u = android.content.ContentUris.withAppendedId(col, c.getLong(0));
                        c.close();
                    }
                    if (u == null) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                        v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LifeApp");
                        u = getContentResolver().insert(col, v);
                    }
                    OutputStream o = getContentResolver().openOutputStream(u, "wt");
                    o.write(data); o.close();
                } else {
                    File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
                    FileOutputStream o = new FileOutputStream(f);
                    o.write(data); o.close();
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LifeApp");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    OutputStream o = getContentResolver().openOutputStream(u);
                    o.write(data); o.close();
                    where = "Downloads/LifeApp";
                } else {
                    File d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(d, name);
                    FileOutputStream o = new FileOutputStream(f);
                    o.write(data); o.close();
                    where = f.getParent();
                }
                toast("Saved " + name + " to " + where);
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }

    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e1a16"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // autosave (localStorage)
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), 77); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (b != null) web.restoreState(b); else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 77 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        } else if (req == 78) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception e) { }
                String id = DocumentsContract.getTreeDocumentId(u);
                String nm = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                if (nm.isEmpty()) nm = "selected folder";
                getSharedPreferences("lb", 0).edit().putString("tree", u.toString()).putString("treeName", nm).apply();
                final String fn = nm;
                web.post(() -> web.evaluateJavascript("window.onBackupFolder&&window.onBackupFolder(" + org.json.JSONObject.quote(fn) + ")", null));
            }
        } else super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
