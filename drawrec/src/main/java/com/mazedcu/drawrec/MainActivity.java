package com.mazedcu.drawrec;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private PermissionRequest pendingPerm;
    private OutputStream vOut;
    private Uri vUri;
    private String vWhere = "Movies/BoardRecorder";

    /** window.Android: streams the recording to Movies/BoardRecorder and saves PNGs. */
    class Bridge {
        @JavascriptInterface
        public boolean startVideo(String name, String mime) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Video.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BoardRecorder");
                    v.put(MediaStore.Video.Media.IS_PENDING, 1);
                    vUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                    vOut = getContentResolver().openOutputStream(vUri);
                    vWhere = "Movies/BoardRecorder";
                } else {
                    File d = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                    File f = new File(d, name);
                    vOut = new FileOutputStream(f);
                    vUri = null;
                    vWhere = f.getParent();
                }
                return true;
            } catch (Exception e) {
                toast("Cannot create video: " + e.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public void chunk(String b64) {
            try { vOut.write(Base64.decode(b64, Base64.DEFAULT)); }
            catch (Exception e) { toast("Write failed: " + e.getMessage()); }
        }

        @JavascriptInterface
        public String finishVideo() {
            try {
                if (vOut != null) { vOut.flush(); vOut.close(); }
                if (vUri != null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(vUri, v, null, null);
                }
            } catch (Exception e) { toast("Finish failed: " + e.getMessage()); }
            vOut = null; vUri = null;
            return vWhere;
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Images.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BoardRecorder");
                    Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    OutputStream o = getContentResolver().openOutputStream(u);
                    o.write(data); o.close();
                    where = "Pictures/BoardRecorder";
                } else {
                    File f = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), name);
                    FileOutputStream o = new FileOutputStream(f);
                    o.write(data); o.close();
                    where = f.getParent();
                }
                toast("Saved " + name + " to " + where);
            } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
        }
    }

    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#10151c"));
        setContentView(web);
        immersive();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        // Serve the bundled page over https so getUserMedia (secure context) works.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) {
                    String p = u.getPath();
                    if (p == null || p.equals("/")) p = "/index.html";
                    try {
                        InputStream in = getAssets().open(p.substring(1));
                        String mime = p.endsWith(".html") ? "text/html" : p.endsWith(".js") ? "text/javascript" : "application/octet-stream";
                        return new WebResourceResponse(mime, "utf-8", in);
                    } catch (Exception e) { /* fall through */ }
                }
                return super.shouldInterceptRequest(v, r);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(req.getResources());
                    } else {
                        pendingPerm = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 5);
                    }
                });
            }
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), 77); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 6);
        }
        if (b != null) web.restoreState(b); else web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (code == 5 && pendingPerm != null) {
            if (ok) pendingPerm.grant(pendingPerm.getResources()); else pendingPerm.deny();
            pendingPerm = null;
        }
        if (!ok) toast("Microphone permission denied — recordings will have no sound");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 77 && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.__back&&window.__back()", v -> {
            if (!"true".equals(v)) finish();
        });
    }
}
