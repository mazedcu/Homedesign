package com.mazedcu.cousin;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Cousin Heaven: a WebView that opens the family server saved on first launch. */
public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private SharedPreferences prefs;

    class Bridge {
        @JavascriptInterface public String getServer() { return prefs.getString("server", ""); }
        @JavascriptInterface public void setServer(String url) {
            url = url.trim();
            if (!url.startsWith("http")) url = "http://" + url;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            prefs.edit().putString("server", url).apply();
            final String u = url;
            runOnUiThread(() -> web.loadUrl(u + "/"));
        }
        @JavascriptInterface public String getCode() { return prefs.getString("code", ""); }
        /** Accepts an invite code (server|familycode, base64) or a plain server address. */
        @JavascriptInterface public boolean join(String invite) {
            invite = invite.trim();
            try {
                String d = new String(android.util.Base64.decode(invite, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), "UTF-8");
                int i = d.indexOf('|');
                if (i > 0 && d.startsWith("http")) { prefs.edit().putString("code", d.substring(i + 1)).apply(); setServer(d.substring(0, i)); return true; }
            } catch (Exception e) { }
            if (invite.contains(".") || invite.contains(":")) { setServer(invite); return true; }
            return false;
        }
        @JavascriptInterface public void resetServer() {
            prefs.edit().remove("server").remove("code").apply();
            runOnUiThread(() -> web.loadUrl("file:///android_asset/setup.html"));
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cousin", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.startsWith("http")) v.loadUrl("file:///android_asset/setup.html?err=1");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try { startActivityForResult(p.createIntent(), 7); }
                catch (Exception e) { chooser = null; return false; }
                return true;
            }
        });
        String srv = prefs.getString("server", "");
        web.loadUrl(srv.isEmpty() ? "file:///android_asset/setup.html" : srv + "/");
    }

    @Override protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && chooser != null) {
            chooser.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(res, d));
            chooser = null;
        }
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }
}
