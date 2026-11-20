package com.mazedcu.screenrec;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecorderService extends Service {
    public static final String ACT_START = "com.mazedcu.screenrec.START";
    public static final String ACT_STOP = "com.mazedcu.screenrec.STOP";
    public static final String ACT_PAUSE = "com.mazedcu.screenrec.PAUSE";
    public static volatile boolean running = false;

    private static final String CH = "rec";
    private static final int NID = 11, NID_DONE = 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences cfg;
    private MediaProjection projection;
    private MediaRecorder recorder;
    private VirtualDisplay vd;
    private Uri outUri;
    private ParcelFileDescriptor pfd;
    private File outFile;
    private boolean recording = false, paused = false, stopping = false, mic = false;
    private long startAt, pausedTotal, pauseAt;

    // overlay
    private WindowManager wm;
    private AnnotationView ann;
    private WindowManager.LayoutParams annLp, barLp;
    private LinearLayout bar, rowTools, rowColors;
    private TextView timeTv, penBtn, pauseBtn, stopBtn, collapseBtn, handleTv;
    private TextView[] toolBtns = new TextView[3], dots;
    private TextView fadeBtn;
    private boolean annotating = false, collapsed = false;
    private TextView countdownTv;
    private final int[] COLORS = {0xFFFF3B30, 0xFFFFD60A, 0xFF34C759, 0xFF0A84FF, 0xFFFFFFFF};

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        if (in == null || in.getAction() == null) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        String a = in.getAction();
        if (ACT_STOP.equals(a)) {
            if (running) stopRecording(); else stopSelf();
        } else if (ACT_PAUSE.equals(a)) {
            togglePause();
        } else if (ACT_START.equals(a) && !running) {
            startFlow(in);
        }
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- start
    private void startFlow(Intent in) {
        cfg = getSharedPreferences("cfg", 0);
        mic = cfg.getInt("mic", 0) == 1 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        running = true;
        createChannel();
        Notification n = buildNotification(false);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                if (mic && Build.VERSION.SDK_INT >= 30) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NID, n, t);
            } else {
                startForeground(NID, n);
            }
        } catch (Exception e) {
            fail("Could not start: " + e.getMessage());
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(in.getIntExtra("code", 0), (Intent) in.getParcelableExtra("data"));
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    h.post(() -> { if (running && !stopping) stopRecording(); });
                }
            }, h);
        } catch (Exception e) {
            fail("Screen capture was not allowed.");
            return;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        boolean overlay = Settings.canDrawOverlays(this);
        if (cfg.getInt("count", 1) == 1 && overlay) {
            countdown(3);
        } else {
            beginRecording();
        }
    }

    private void countdown(final int n) {
        if (n == 3) {
            countdownTv = new TextView(this);
            countdownTv.setTextColor(Color.WHITE);
            countdownTv.setTextSize(96);
            countdownTv.setGravity(Gravity.CENTER);
            countdownTv.setShadowLayer(12, 0, 0, Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            try { wm.addView(countdownTv, lp); } catch (Exception e) { countdownTv = null; }
        }
        if (n == 0) {
            if (countdownTv != null) {
                try { wm.removeView(countdownTv); } catch (Exception e) { }
                countdownTv = null;
            }
            // let the overlay disappear before capture begins
            h.postDelayed(this::beginRecording, 250);
            return;
        }
        if (countdownTv != null) countdownTv.setText(String.valueOf(n));
        h.postDelayed(() -> { if (running && !stopping) countdown(n - 1); }, 1000);
    }

    private void beginRecording() {
        if (!running || stopping) return;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int W = dm.widthPixels, H = dm.heightPixels, shortSide = Math.min(W, H);
        int res = cfg.getInt("res", 1), fps = cfg.getInt("fps", 0) == 1 ? 60 : 30, q = cfg.getInt("qual", 1);
        int target = res == 0 ? 720 : res == 1 ? 1080 : shortSide;
        boolean ok = startWith(dm, target, fps, q);
        if (!ok) {
            // fall back to safer settings once
            ok = startWith(dm, Math.min(target, 1080), 30, Math.min(q, 1));
        }
        if (!ok) {
            fail("This phone could not start recording. Try lower settings.");
            return;
        }
        recording = true;
        startAt = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        if (Settings.canDrawOverlays(this)) buildOverlay();
        updateNotification();
        h.post(tick);
    }

    private static int even(int v) {
        return Math.max(2, v & ~1);
    }

    private boolean startWith(DisplayMetrics dm, int target, int fps, int q) {
        try {
            int W = dm.widthPixels, H = dm.heightPixels, shortSide = Math.min(W, H);
            if (target > shortSide) target = shortSide;
            float sc = (float) target / shortSide;
            int w = even(Math.round(W * sc)), hh = even(Math.round(H * sc));
            int longSide = Math.max(w, hh);
            if (longSide > 4096) {
                float s2 = 4096f / longSide;
                w = even(Math.round(w * s2));
                hh = even(Math.round(hh * s2));
            }
            float[] bpp = {0.09f, 0.15f, 0.25f};
            long br = (long) (w * (long) hh * fps * bpp[Math.max(0, Math.min(2, q))]);
            br = Math.max(2_000_000L, Math.min(45_000_000L, br));

            openOutput();
            recorder = new MediaRecorder();
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (mic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128000);
                recorder.setAudioSamplingRate(44100);
            }
            recorder.setVideoSize(w, hh);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate((int) br);
            if (pfd != null) recorder.setOutputFile(pfd.getFileDescriptor());
            else recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            vd = projection.createVirtualDisplay("ScreenRec", w, hh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start();
            return true;
        } catch (Exception e) {
            cleanupRecorder();
            discardOutput();
            return false;
        }
    }

    private void openOutput() throws Exception {
        String name = "ScreenRec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRec");
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
            outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            pfd = getContentResolver().openFileDescriptor(outUri, "w");
            outFile = null;
        } else {
            File d = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (d != null) d.mkdirs();
            outFile = new File(d, name);
            outUri = null;
            pfd = null;
        }
    }

    private void discardOutput() {
        try { if (pfd != null) pfd.close(); } catch (Exception e) { }
        pfd = null;
        try { if (outUri != null) getContentResolver().delete(outUri, null, null); } catch (Exception e) { }
        try { if (outFile != null) outFile.delete(); } catch (Exception e) { }
        outUri = null;
        outFile = null;
    }

    private void cleanupRecorder() {
        try { if (vd != null) vd.release(); } catch (Exception e) { }
        vd = null;
        try { if (recorder != null) recorder.release(); } catch (Exception e) { }
        recorder = null;
    }

    // ----------------------------------------------------------------- stop
    private void stopRecording() {
        if (stopping) return;
        stopping = true;
        h.removeCallbacks(tick);
        boolean saved = false;
        if (recording) {
            try {
                recorder.stop();
                saved = true;
            } catch (RuntimeException e) {
                saved = false;
            }
        }
        recording = false;
        cleanupRecorder();
        try { if (projection != null) projection.stop(); } catch (Exception e) { }
        projection = null;
        try { if (pfd != null) pfd.close(); } catch (Exception e) { }
        pfd = null;
        Uri done = null;
        if (saved) {
            if (outUri != null) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, v, null, null);
                    done = outUri;
                    cfg.edit().putString("last", outUri.toString()).apply();
                } catch (Exception e) { }
            } else if (outFile != null) {
                toast("Saved: " + outFile.getAbsolutePath());
            }
        } else {
            discardOutput();
            toast("Recording was too short to save.");
        }
        removeOverlay();
        running = false;
        stopForeground(true);
        if (done != null) notifyDone(done);
        stopSelf();
    }

    private void fail(String msg) {
        toast(msg);
        stopping = true;
        recording = false;
        cleanupRecorder();
        discardOutput();
        try { if (projection != null) projection.stop(); } catch (Exception e) { }
        removeOverlay();
        running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (running) {
            running = false;
            cleanupRecorder();
            removeOverlay();
        }
        super.onDestroy();
    }

    private void togglePause() {
        if (!recording || recorder == null) return;
        try {
            if (!paused) {
                recorder.pause();
                paused = true;
                pauseAt = SystemClock.elapsedRealtime();
            } else {
                recorder.resume();
                paused = false;
                pausedTotal += SystemClock.elapsedRealtime() - pauseAt;
            }
        } catch (Exception e) {
            toast("Pause is not supported here.");
            return;
        }
        if (pauseBtn != null) pauseBtn.setText(paused ? "▶" : "⏸");
        updateNotification();
    }

    private long elapsedMs() {
        long now = paused ? pauseAt : SystemClock.elapsedRealtime();
        return Math.max(0, now - startAt - pausedTotal);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            long s = elapsedMs() / 1000;
            String t = String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
            if (timeTv != null) timeTv.setText((paused ? "❚❚ " : "● ") + t);
            h.postDelayed(this, 500);
        }
    };

    // --------------------------------------------------------- notifications
    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CH, "Screen recording", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    private PendingIntent svc(String action, int code) {
        Intent i = new Intent(this, RecorderService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(boolean isPaused) {
        Notification.Builder b = new Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(isPaused ? "Recording paused" : "Recording screen")
            .setContentText("Tap Stop when you are done")
            .setOngoing(true)
            .addAction(R.drawable.ic_stat, isPaused ? "Resume" : "Pause", svc(ACT_PAUSE, 1))
            .addAction(R.drawable.ic_stat, "Stop", svc(ACT_STOP, 2));
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setContentIntent(open);
        return b.build();
    }

    private void updateNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID, buildNotification(paused));
        } catch (Exception e) { }
    }

    private void notifyDone(Uri u) {
        try {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PendingIntent pi = PendingIntent.getActivity(this, 4, v, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Recording saved")
                .setContentText("Movies/ScreenRec — tap to play")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID_DONE, n);
        } catch (Exception e) { }
        toast("Recording saved to Movies/ScreenRec");
    }

    private void toast(final String m) {
        h.post(() -> Toast.makeText(getApplicationContext(), m, Toast.LENGTH_LONG).show());
    }

    // --------------------------------------------------------------- overlay
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView btn(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(17);
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(40));
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        v.setOnClickListener(l);
        return v;
    }

    private void buildOverlay() {
        try {
            ann = new AnnotationView(this);
            annLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
            annLp.alpha = 0.8f;
            annLp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(ann, annLp);

            bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE141A22);
            bg.setCornerRadius(dp(22));
            bar.setBackground(bg);
            bar.setPadding(dp(4), dp(2), dp(4), dp(2));

            LinearLayout r1 = new LinearLayout(this);
            r1.setGravity(Gravity.CENTER_VERTICAL);
            handleTv = btn("⠿", null);
            handleTv.setTextColor(0xFF8A94A3);
            timeTv = btn("● 00:00", null);
            timeTv.setTextSize(14);
            penBtn = btn("✎", v -> setAnnotate(!annotating));
            pauseBtn = btn("⏸", v -> togglePause());
            stopBtn = btn("■", v -> stopRecording());
            stopBtn.setTextColor(0xFFFF5A52);
            collapseBtn = btn("⌃", v -> setCollapsed(!collapsed));
            r1.addView(handleTv);
            r1.addView(timeTv);
            r1.addView(penBtn);
            r1.addView(pauseBtn);
            r1.addView(stopBtn);
            r1.addView(collapseBtn);
            bar.addView(r1);

            rowTools = new LinearLayout(this);
            rowTools.setGravity(Gravity.CENTER_VERTICAL);
            String[] tn = {"Pen", "Mark", "Arrow"};
            for (int i = 0; i < 3; i++) {
                final int idx = i;
                toolBtns[i] = btn(tn[i], v -> selectTool(idx));
                toolBtns[i].setTextSize(13);
                rowTools.addView(toolBtns[i]);
            }
            rowTools.addView(btn("↶", v -> ann.undo()));
            rowTools.addView(btn("✕", v -> ann.clear()));
            fadeBtn = btn("Fade", v -> {
                ann.fade = !ann.fade;
                fadeBtn.setTextColor(ann.fade ? 0xFF34C759 : Color.WHITE);
                ann.invalidate();
            });
            fadeBtn.setTextSize(13);
            rowTools.addView(fadeBtn);
            rowTools.setVisibility(View.GONE);
            bar.addView(rowTools);

            rowColors = new LinearLayout(this);
            rowColors.setGravity(Gravity.CENTER);
            dots = new TextView[COLORS.length];
            for (int i = 0; i < COLORS.length; i++) {
                final int idx = i;
                TextView d = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
                lp.setMargins(dp(7), dp(4), dp(7), dp(6));
                d.setLayoutParams(lp);
                d.setOnClickListener(v -> selectColor(idx));
                dots[i] = d;
                rowColors.addView(d);
            }
            rowColors.setVisibility(View.GONE);
            bar.addView(rowColors);
            selectTool(0);
            selectColor(0);

            barLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            barLp.gravity = Gravity.TOP | Gravity.START;
            barLp.x = dp(8);
            barLp.y = dp(90);
            final float[] d0 = new float[4];
            handleTv.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        d0[0] = e.getRawX(); d0[1] = e.getRawY(); d0[2] = barLp.x; d0[3] = barLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        barLp.x = (int) (d0[2] + e.getRawX() - d0[0]);
                        barLp.y = (int) (d0[3] + e.getRawY() - d0[1]);
                        try { wm.updateViewLayout(bar, barLp); } catch (Exception ex) { }
                        return true;
                }
                return true;
            });
            wm.addView(bar, barLp);
        } catch (Exception e) {
            removeOverlay();
        }
    }

    private void selectTool(int i) {
        ann.tool = i;
        for (int k = 0; k < 3; k++) toolBtns[k].setTextColor(k == i ? 0xFF5AA2FF : Color.WHITE);
    }

    private void selectColor(int i) {
        ann.color = COLORS[i];
        for (int k = 0; k < dots.length; k++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(COLORS[k]);
            g.setStroke(dp(k == i ? 3 : 1), k == i ? 0xFF5AA2FF : 0x66FFFFFF);
            dots[k].setBackground(g);
        }
    }

    private void setAnnotate(boolean on) {
        if (ann == null) return;
        annotating = on;
        penBtn.setTextColor(on ? 0xFF5AA2FF : Color.WHITE);
        rowTools.setVisibility(on ? View.VISIBLE : View.GONE);
        rowColors.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            annLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            annLp.alpha = 1f;
        } else {
            annLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            annLp.alpha = 0.8f;
        }
        try { wm.updateViewLayout(ann, annLp); } catch (Exception e) { }
        try { wm.updateViewLayout(bar, barLp); } catch (Exception e) { }
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        if (c && annotating) setAnnotate(false);
        int vis = c ? View.GONE : View.VISIBLE;
        penBtn.setVisibility(vis);
        pauseBtn.setVisibility(vis);
        stopBtn.setVisibility(vis);
        collapseBtn.setText(c ? "⌄" : "⌃");
        try { wm.updateViewLayout(bar, barLp); } catch (Exception e) { }
    }

    private void removeOverlay() {
        try { if (bar != null) wm.removeView(bar); } catch (Exception e) { }
        try { if (ann != null) wm.removeView(ann); } catch (Exception e) { }
        try { if (countdownTv != null) wm.removeView(countdownTv); } catch (Exception e) { }
        bar = null;
        ann = null;
        countdownTv = null;
    }
}
