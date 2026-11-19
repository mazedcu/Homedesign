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

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;

    /** Exposed to the page as window.Android — saves exported files to Downloads. */
    class Bridge {
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
        } else super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
