package com.mazedcu.screenrec;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private SharedPreferences cfg;
    private TextView startBtn;
    private static final int REQ_PROJ = 1, REQ_PERM = 2;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String t, float size, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = getSharedPreferences("cfg", 0);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(28));
        sv.addView(root);
        setContentView(sv);

        root.addView(text("Screen Recorder", 28, Color.WHITE));
        TextView sub = text("High-quality recording with on-screen drawing", 14, 0xFF8A94A3);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        chips(root, "Resolution", "res", 1, "720p", "1080p", "Max");
        chips(root, "Frame rate", "fps", 0, "30 fps", "60 fps");
        chips(root, "Quality", "qual", 1, "Standard", "High", "Ultra");
        chips(root, "Microphone", "mic", 0, "Off", "On");
        chips(root, "Countdown 3-2-1", "count", 1, "Off", "On");

        startBtn = text("", 18, Color.WHITE);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setPadding(0, dp(16), 0, dp(16));
        startBtn.setOnClickListener(v -> onStartTap());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(22);
        root.addView(startBtn, sl);

        TextView last = text("Open last recording", 15, 0xFF5AA2FF);
        last.setGravity(Gravity.CENTER);
        last.setPadding(0, dp(16), 0, dp(10));
        last.setOnClickListener(v -> openLast());
        root.addView(last);

        TextView tips = text("While recording a floating bar appears: ✎ opens drawing tools (pen, marker, arrow, 5 colours, undo, clear, auto-fade), ⏸ pauses, ■ stops, ⌃ shrinks the bar. Recordings are saved in Movies/ScreenRec. Lock your screen rotation before you start. Sound from the phone itself cannot be captured by this app; the microphone can.", 13, 0xFF8A94A3);
        tips.setPadding(0, dp(14), 0, 0);
        root.addView(tips);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean r = RecorderService.running;
        startBtn.setText(r ? "■  Stop recording" : "●  Start recording");
        startBtn.setBackground(bg(r ? 0xFF4B5563 : 0xFFE53935, 16));
    }

    private void chips(LinearLayout parent, String title, final String key, int def, String... opts) {
        TextView t = text(title, 13, 0xFF8A94A3);
        t.setPadding(0, dp(14), 0, dp(6));
        parent.addView(t);
        final LinearLayout row = new LinearLayout(this);
        final int n = opts.length;
        final TextView[] cs = new TextView[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            TextView c = text(opts[i], 15, Color.WHITE);
            c.setGravity(Gravity.CENTER);
            c.setPadding(0, dp(12), 0, dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = i < n - 1 ? dp(8) : 0;
            c.setOnClickListener(v -> {
                cfg.edit().putInt(key, idx).apply();
                paint(cs, idx);
            });
            cs[i] = c;
            row.addView(c, lp);
        }
        paint(cs, cfg.getInt(key, def));
        parent.addView(row);
    }

    private void paint(TextView[] cs, int sel) {
        for (int i = 0; i < cs.length; i++) {
            cs[i].setBackground(bg(i == sel ? 0xFF2563EB : 0xFF232B36, 12));
            cs[i].setTextColor(i == sel ? Color.WHITE : 0xFFB4BCC8);
        }
    }

    private void onStartTap() {
        if (RecorderService.running) {
            startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACT_STOP));
            startBtn.postDelayed(this::refresh, 600);
            return;
        }
        ArrayList<String> need = new ArrayList<>();
        if (cfg.getInt("mic", 0) == 1 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
            return;
        }
        checkOverlay();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERM) return;
        if (cfg.getInt("mic", 0) == 1 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            cfg.edit().putInt("mic", 0).apply();
            Toast.makeText(this, "Microphone not allowed — recording without sound.", Toast.LENGTH_LONG).show();
        }
        checkOverlay();
    }

    private void checkOverlay() {
        if (!Settings.canDrawOverlays(this) && !cfg.getBoolean("asked", false)) {
            cfg.edit().putBoolean("asked", true).apply();
            new AlertDialog.Builder(this)
                .setTitle("Drawing tools")
                .setMessage("To draw on the screen while recording, allow “Display over other apps” for this app, then come back and tap Start again.")
                .setPositiveButton("Allow", (d, w) -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Record without drawing", (d, w) -> requestProjection())
                .show();
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_PROJ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PROJ && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACT_START)
                .putExtra("code", res).putExtra("data", data);
            startForegroundService(i);
            moveTaskToBack(true);
        }
    }

    private void openLast() {
        String u = cfg.getString("last", "");
        if (u.isEmpty()) {
            Toast.makeText(this, "No recording yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, "Could not open it. Look in Movies/ScreenRec.", Toast.LENGTH_LONG).show();
        }
    }
}
