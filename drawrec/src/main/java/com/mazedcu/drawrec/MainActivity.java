package com.mazedcu.drawrec;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private PermissionRequest pendingPerm;
    private OutputStream vOut;
    private File vRaw;
    private String vName = "lecture.mp4", vMime = "video/mp4";

    private static final String DIR = "Movies/BoardRecorder";

    /** window.Android: records to a temp file, remuxes it, and offers a video library with trim / cut / split. */
    class Bridge {
        @JavascriptInterface
        public boolean startVideo(String name, String mime) {
            try {
                vName = name; vMime = mime;
                vRaw = new File(getCacheDir(), "rec-" + System.nanoTime() + ".tmp");
                vOut = new FileOutputStream(vRaw);
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

        /** Returns immediately; the page gets window.__videoSaved(ok, where, name) when the file is ready. */
        @JavascriptInterface
        public void finishVideo() {
            try { if (vOut != null) { vOut.flush(); vOut.close(); } } catch (Exception e) { }
            vOut = null;
            final File raw = vRaw; final String name = vName, mime = vMime;
            new Thread(() -> {
                File out = new File(getCacheDir(), "fix-" + System.nanoTime() + extOf(name));
                File use = raw;
                try {
                    export(Uri.fromFile(raw), new long[][]{{0, -1}}, out, fmtFor(name));
                    use = out;
                } catch (Exception e) { use = raw; }
                try {
                    String where = saveToStore(use, name, mime);
                    js("window.__videoSaved(true," + JSONObject.quote(where) + "," + JSONObject.quote(name) + ")");
                } catch (Exception e) {
                    js("window.__videoSaved(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ",\"\")");
                }
                raw.delete(); out.delete();
            }).start();
        }

        @JavascriptInterface
        public String listVideos() {
            JSONArray a = new JSONArray();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED};
                    Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                        MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{DIR + "%"}, MediaStore.Video.Media.DATE_ADDED + " DESC");
                    if (c != null) {
                        while (c.moveToNext()) {
                            JSONObject o = new JSONObject();
                            o.put("id", String.valueOf(c.getLong(0))); o.put("name", c.getString(1));
                            o.put("size", c.getLong(2)); o.put("dur", c.getLong(3)); o.put("date", c.getLong(4) * 1000);
                            a.put(o);
                        }
                        c.close();
                    }
                } else {
                    File[] fs = getExternalFilesDir(Environment.DIRECTORY_MOVIES).listFiles();
                    if (fs != null) for (File f : fs) {
                        if (f.getName().endsWith(".tmp")) continue;
                        JSONObject o = new JSONObject();
                        long d = 0;
                        try { MediaMetadataRetriever m = new MediaMetadataRetriever(); m.setDataSource(f.getPath()); d = Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); m.release(); } catch (Exception e) { }
                        o.put("id", "f:" + f.getName()); o.put("name", f.getName()); o.put("size", f.length()); o.put("dur", d); o.put("date", f.lastModified());
                        a.put(o);
                    }
                }
            } catch (Exception e) { }
            return a.toString();
        }

        /** jobs: [{label, segs:[[startMs,endMs(-1=end)],...]}] — each job becomes one new file in the library. */
        @JavascriptInterface
        public void exportJobs(final String id, final String jobsJson) {
            new Thread(() -> {
                try {
                    JSONArray jobs = new JSONArray(jobsJson);
                    String base = nameOf(id); String ext = extOf(base);
                    String stem = base.substring(0, base.length() - ext.length());
                    JSONArray names = new JSONArray();
                    for (int k = 0; k < jobs.length(); k++) {
                        JSONObject job = jobs.getJSONObject(k);
                        JSONArray sg = job.getJSONArray("segs");
                        long[][] segs = new long[sg.length()][2];
                        for (int q = 0; q < sg.length(); q++) {
                            segs[q][0] = Math.round(sg.getJSONArray(q).getDouble(0) * 1000);
                            double e = sg.getJSONArray(q).getDouble(1);
                            segs[q][1] = e < 0 ? -1 : Math.round(e * 1000);
                        }
                        String outName = stem + "-" + job.getString("label") + "-" + (System.currentTimeMillis() % 100000) + ext;
                        File tmp = new File(getCacheDir(), "exp-" + System.nanoTime() + ext);
                        export(uriFor(id), segs, tmp, fmtFor(outName));
                        saveToStore(tmp, outName, ext.equals(".webm") ? "video/webm" : "video/mp4");
                        tmp.delete();
                        names.put(outName);
                    }
                    js("window.__exportDone(true," + JSONObject.quote(names.toString()) + ")");
                } catch (Exception e) {
                    js("window.__exportDone(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public boolean deleteVideo(String id) {
            try {
                if (id.startsWith("f:")) return new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), id.substring(2)).delete();
                return getContentResolver().delete(uriFor(id), null, null) > 0;
            } catch (Exception e) { toast("Delete failed: " + e.getMessage()); return false; }
        }

        @JavascriptInterface
        public void shareVideo(String id) {
            try {
                if (id.startsWith("f:")) { toast("Sharing needs Android 10 or newer"); return; }
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("video/*");
                i.putExtra(Intent.EXTRA_STREAM, uriFor(id));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share lecture"));
            } catch (Exception e) { toast("Share failed: " + e.getMessage()); }
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

    private void js(final String call) {
        runOnUiThread(() -> web.evaluateJavascript(call, null));
    }

    private static String extOf(String n) { return n.toLowerCase().endsWith(".webm") ? ".webm" : ".mp4"; }
    private static int fmtFor(String n) { return n.toLowerCase().endsWith(".webm") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4; }

    private Uri uriFor(String id) {
        if (id.startsWith("f:")) return Uri.fromFile(new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), id.substring(2)));
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id));
    }

    private String nameOf(String id) {
        if (id.startsWith("f:")) return id.substring(2);
        Cursor c = getContentResolver().query(uriFor(id), new String[]{MediaStore.Video.Media.DISPLAY_NAME}, null, null, null);
        String n = "video.mp4";
        if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        return n;
    }

    /** Copies a finished file into Movies/BoardRecorder (visible in the Gallery). */
    private String saveToStore(File src, String name, String mime) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out; Uri u = null; String where;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, mime);
            v.put(MediaStore.Video.Media.RELATIVE_PATH, DIR);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            u = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            out = getContentResolver().openOutputStream(u);
            where = DIR;
        } else {
            File f = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), name);
            out = new FileOutputStream(f);
            where = f.getParent();
        }
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush(); out.close(); in.close();
        if (u != null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
        }
        return where;
    }

    /**
     * Lossless cut: copies the compressed samples of the given segments (start/end in ms, end -1 = to the end)
     * into a new file, joining them in order. Segments start at the keyframe at or before the requested start.
     */
    private void export(Uri src, long[][] segsMs, File out, int format) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null; boolean started = false, wrote = false;
        try {
            ex.setDataSource(this, src, null);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            mux = new MediaMuxer(out.getPath(), format);
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && (m.startsWith("video/") || m.startsWith("audio/"))) { map[i] = mux.addTrack(f); ex.selectTrack(i); }
                else map[i] = -1;
            }
            mux.start(); started = true;
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offset = 0;
            for (long[] sg : segsMs) {
                long startUs = sg[0] * 1000, endUs = sg[1] < 0 ? Long.MAX_VALUE : sg[1] * 1000;
                if (endUs <= startUs) continue;
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long base = -1, last = 0;
                while (true) {
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) break;
                    long t = ex.getSampleTime();
                    if (t >= endUs) break;
                    int ti = ex.getSampleTrackIndex();
                    if (base < 0) base = t;
                    if (map[ti] >= 0) {
                        info.offset = 0; info.size = size;
                        info.presentationTimeUs = Math.max(0, t - base) + offset;
                        info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mux.writeSampleData(map[ti], buf, info);
                        wrote = true;
                    }
                    last = Math.max(last, t - base);
                    ex.advance();
                }
                offset += last + 33000;
            }
            if (!wrote) throw new Exception("That selection is empty or too short");
        } finally {
            try { if (started) mux.stop(); } catch (Exception e) { }
            try { if (mux != null) mux.release(); } catch (Exception e) { }
            ex.release();
        }
    }

    /** Stream limited to a byte range, for serving video to the page with Range support. */
    private static class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in, long left) { super(in); this.left = left; }
        @Override public int read() throws java.io.IOException { if (left <= 0) return -1; int r = super.read(); if (r >= 0) left--; return r; }
        @Override public int read(byte[] b, int o, int l) throws java.io.IOException { if (left <= 0) return -1; int r = super.read(b, o, (int) Math.min(l, left)); if (r > 0) left -= r; return r; }
    }

    private WebResourceResponse serveVideo(WebResourceRequest r, String id) {
        try {
            Uri u = uriFor(id);
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(u, "r");
            long len = afd.getLength();
            FileInputStream in = afd.createInputStream();
            long start = 0, end = len - 1; int code = 200; String msg = "OK";
            String range = r.getRequestHeaders().get("Range");
            if (range == null) range = r.getRequestHeaders().get("range");
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                start = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) end = Math.min(end, Long.parseLong(p[1]));
                long sk = start; while (sk > 0) { long k = in.skip(sk); if (k <= 0) break; sk -= k; }
                code = 206; msg = "Partial Content";
            }
            String mime = nameOf(id).toLowerCase().endsWith(".webm") ? "video/webm" : "video/mp4";
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(end - start + 1));
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            h.put("Content-Type", mime);
            return new WebResourceResponse(mime, null, code, msg, h, new Bounded(in, end - start + 1));
        } catch (Exception e) { return null; }
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
                    if (p != null && p.startsWith("/video/")) {
                        WebResourceResponse vr = serveVideo(r, p.substring(7));
                        if (vr != null) return vr;
                    }
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
