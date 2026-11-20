package com.mazedcu.masjid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Masjid Finder: a WebView that opens the Masjid Finder server, with native location and navigation. */
public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String server() {
        String s = prefs.getString("server", "");
        if (s.isEmpty()) s = getString(R.string.default_server).trim();
        return s;
    }

    class Bridge {
        @JavascriptInterface public void setServer(String url) {
            url = url.trim();
            if (!url.startsWith("http")) url = "http://" + url;
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            prefs.edit().putString("server", url).apply();
            final String u = url;
            runOnUiThread(() -> web.loadUrl(u + "/"));
        }
        @JavascriptInterface public void resetServer() {
            prefs.edit().remove("server").apply();
            runOnUiThread(() -> web.loadUrl("file:///android_asset/setup.html"));
        }
        @JavascriptInterface public void requestLocation() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 9);
                    return;
                }
                fetchLocation();
            });
        }
        @JavascriptInterface public void navigate(double lat, double lng, String name) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(" + Uri.encode(name) + ")")));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lng + "#map=18/" + lat + "/" + lng)));
                    } catch (Exception e2) { }
                }
            });
        }
    }

    private void deliver(Location l) {
        web.evaluateJavascript("window.onNativeLocation&&window.onNativeLocation(" + l.getLatitude() + "," + l.getLongitude() + ",null)", null);
    }

    private void deliverErr(String m) {
        web.evaluateJavascript("window.onNativeLocation&&window.onNativeLocation(0,0,'" + m + "')", null);
    }

    private void fetchLocation() {
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (SecurityException e) {
            deliverErr("denied");
            return;
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 120000) {
            deliver(best);
            return;
        }
        String prov = null;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) prov = LocationManager.GPS_PROVIDER;
            else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) prov = LocationManager.NETWORK_PROVIDER;
        } catch (Exception e) { }
        if (prov == null) {
            if (best != null) deliver(best); else deliverErr("off");
            return;
        }
        final Location fb = best;
        final boolean[] done = {false};
        final LocationListener ln = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (!done[0]) { done[0] = true; deliver(l); }
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            lm.requestSingleUpdate(prov, ln, Looper.getMainLooper());
        } catch (SecurityException e) {
            deliverErr("denied");
            return;
        }
        handler.postDelayed(() -> {
            if (!done[0]) {
                done[0] = true;
                try { lm.removeUpdates(ln); } catch (Exception e) { }
                if (fb != null) deliver(fb); else deliverErr("timeout");
            }
        }, 12000);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 9) return;
        boolean ok = false;
        for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        if (ok) fetchLocation(); else deliverErr("denied");
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("masjid", MODE_PRIVATE);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.startsWith("http") && url.startsWith(server()))
                    v.loadUrl("file:///android_asset/setup.html?err=1");
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        String sv = server();
        web.loadUrl(sv.isEmpty() ? "file:///android_asset/setup.html" : sv + "/");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.appBack?window.appBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }
}
