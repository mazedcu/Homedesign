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

import java.util.Locale;

/** Little Arabic: WebView app with an Arabic text-to-speech bridge (window.Android.speak). */
public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String arabicState = "loading"; // ok | missing | loading

    class Bridge {
        @JavascriptInterface
        public String speak(String text) {
            if (!ttsReady) return arabicState.equals("missing") ? "missing" : "loading";
            if ("missing".equals(arabicState)) return "missing";
            tts.setSpeechRate(0.8f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
            return "ok";
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) { arabicState = "missing"; ttsReady = true; return; }
            int r = tts.setLanguage(new Locale("ar"));
            arabicState = (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) ? "missing" : "ok";
            ttsReady = true;
        });
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
