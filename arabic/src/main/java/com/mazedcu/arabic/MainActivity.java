package com.mazedcu.arabic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.util.Base64;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Locale;

/** Little Arabic: WebView app with an Arabic text-to-speech bridge (window.Android.speak). */
public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SharedPreferences prefs;
    private MediaPlayer player;
    private String arabicState = "loading"; // ok | missing | loading

    class Bridge {
        /** Opens the phone's screen for installing Arabic voice data for Google Text-to-speech. */
        @JavascriptInterface
        public void installVoice() {
            runOnUiThread(() -> {
                try {
                    android.content.Intent i = new android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                    i.setPackage("com.google.android.tts");
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception e2) { }
                }
            });
        }
        @JavascriptInterface
        public String speak(final String text) {
            final String srv = prefs.getString("srv", ""), code = prefs.getString("code", "");
            if (!srv.isEmpty()) {
                new Thread(() -> {
                    File f = cloudFile(srv, code, text);
                    if (f != null) runOnUiThread(() -> play(f));
                    else runOnUiThread(() -> phoneSpeak(text));
                }).start();
                return "ok";
            }
            return phoneSpeak(text);
        }

        /** Accepts the Cousin Heaven invite code (server address + family code). */
        @JavascriptInterface
        public boolean setVoiceInvite(String invite) {
            try {
                String d = new String(Base64.decode(invite.trim(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8");
                int i = d.indexOf('|');
                if (i > 0 && d.startsWith("http")) {
                    prefs.edit().putString("srv", d.substring(0, i)).putString("code", d.substring(i + 1)).apply();
                    return true;
                }
            } catch (Exception e) { }
            return false;
        }

        @JavascriptInterface public void clearVoice() { prefs.edit().remove("srv").remove("code").apply(); }
        @JavascriptInterface public String voiceMode() { return prefs.getString("srv", "").isEmpty() ? "phone" : "cloud"; }

        /** Downloads and keeps audio for many texts (one per line) so they work offline later. */
        @JavascriptInterface
        public void prefetch(final String lines) {
            final String srv = prefs.getString("srv", ""), code = prefs.getString("code", "");
            if (srv.isEmpty()) { toast("Set up the Google voice first."); return; }
            new Thread(() -> {
                String[] a = lines.split("\n"); int ok = 0, bad = 0;
                for (String t : a) { if (t.trim().isEmpty()) continue; if (cloudFile(srv, code, t.trim()) != null) ok++; else bad++; if (bad >= 5) break; }
                toast(bad >= 5 ? "Stopped: the server could not make voices. Check the Google key." : "Voices saved on this phone: " + ok);
            }).start();
        }
    }

    private String phoneSpeak(String text) {
        if (!ttsReady) return arabicState.equals("missing") ? "missing" : "loading";
        if ("missing".equals(arabicState)) return "missing";
        tts.setSpeechRate(0.75f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
        return "ok";
    }

    private void play(File f) {
        try {
            if (player != null) { try { player.stop(); } catch (Exception e) { } player.release(); }
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Exception e) { toast("Could not play the sound."); }
    }

    /** Returns the cached mp3 for this text, downloading it from the family server if needed. */
    private File cloudFile(String srv, String code, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder h = new StringBuilder();
            for (byte x : md.digest(text.getBytes("UTF-8"))) h.append(String.format("%02x", x));
            File dir = new File(getCacheDir(), "tts"); dir.mkdirs();
            File f = new File(dir, h + ".mp3");
            if (f.exists() && f.length() > 0) return f;
            HttpURLConnection c = (HttpURLConnection) new URL(srv + "/api/tts?c=" + URLEncoder.encode(code, "UTF-8") + "&t=" + URLEncoder.encode(text, "UTF-8")).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream(); File tmp = new File(dir, h + ".part");
            FileOutputStream o = new FileOutputStream(tmp); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            o.close(); in.close();
            return tmp.renameTo(f) ? f : null;
        } catch (Exception e) { return null; }
    }

    private void toast(final String m) { runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show()); }

    private void initTts(final String engine) {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                if (engine != null) { // Google engine not available: use the phone's default engine
                    runOnUiThread(() -> initTts(null));
                    return;
                }
                arabicState = "missing"; ttsReady = true; return;
            }
            int r = tts.setLanguage(new Locale("ar"));
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) r = tts.setLanguage(new Locale("ar", "SA"));
            arabicState = (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ? "missing" : "ok";
            ttsReady = true;
        }, engine);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("arabic", MODE_PRIVATE);
        initTts("com.google.android.tts");
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#fff7e8"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("window.appBack?appBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
}
