package com.mazedcu.screenrec;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.Crop;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plays a recording and edits it: trim, cut out a section, crop. */
public class EditorActivity extends Activity {
    private Uri uri;
    private VideoView vv;
    private CropOverlay crop;
    private SeekBar seek;
    private TextView timeTv, playBtn, saveBtn, summaryTv;
    private LinearLayout panel;
    private final TextView[] tabs = new TextView[4];
    private RangeBar trimBar, cutBar;
    private TextView trimLabel, cutLabel;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long dur = 0, trimS = 0, trimE = 0, cutS = -1, cutE = -1;
    private int vw = 0, vh = 0, tab = 0;
    private boolean ready = false;
    private Transformer transformer;
    private AlertDialog progressDlg;
    private ProgressBar progressBar;
    private TextView progressTv;

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView txt(String t, float size, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private TextView button(String t, View.OnClickListener l) {
        TextView v = txt(t, 14, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2B3440);
        g.setCornerRadius(dp(10));
        v.setBackground(g);
        v.setOnClickListener(l);
        return v;
    }

    private void bg(View v, int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        v.setBackground(g);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        uri = Uri.parse(getIntent().getStringExtra("uri"));
        String name = getIntent().getStringExtra("name");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(28), dp(12), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = txt("←", 22, Color.WHITE);
        back.setPadding(dp(4), 0, dp(14), 0);
        back.setOnClickListener(v -> finish());
        TextView title = txt(name == null ? "Video" : name, 14, 0xFFB4BCC8);
        title.setSingleLine(true);
        top.addView(back);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView share = txt("Share", 15, 0xFF5AA2FF);
        share.setPadding(dp(12), dp(6), dp(12), dp(6));
        share.setOnClickListener(v -> Videos.share(this, uri));
        TextView del = txt("Delete", 15, 0xFFFF5A52);
        del.setPadding(dp(12), dp(6), dp(4), dp(6));
        del.setOnClickListener(v -> confirmDelete());
        top.addView(share);
        top.addView(del);
        root.addView(top);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        vv = new VideoView(this);
        FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(-1, -1);
        vl.gravity = Gravity.CENTER;
        box.addView(vv, vl);
        crop = new CropOverlay(this);
        crop.setVisibility(View.GONE);
        crop.onChange = this::refreshSummary;
        box.addView(crop, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1);
        bl.topMargin = dp(6);
        root.addView(box, bl);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        playBtn = txt("▶", 24, Color.WHITE);
        playBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        playBtn.setOnClickListener(v -> togglePlay());
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && dur > 0) vv.seekTo((int) (dur * p / 1000));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) { }

            @Override
            public void onStopTrackingTouch(SeekBar s) { }
        });
        timeTv = txt("0:00 / 0:00", 12, 0xFF8A94A3);
        ctl.addView(playBtn);
        ctl.addView(seek, new LinearLayout.LayoutParams(0, -2, 1));
        ctl.addView(timeTv);
        root.addView(ctl);

        LinearLayout tabRow = new LinearLayout(this);
        String[] tn = {"Play", "Trim", "Cut", "Crop"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tabs[i] = txt(tn[i], 15, Color.WHITE);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(0, dp(10), 0, dp(10));
            tabs[i].setOnClickListener(v -> showTab(idx));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
            tl.rightMargin = i < 3 ? dp(6) : 0;
            tabRow.addView(tabs[i], tl);
        }
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-1, -2);
        trl.topMargin = dp(4);
        root.addView(tabRow, trl);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setMinimumHeight(dp(120));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        summaryTv = txt("", 12, 0xFF8A94A3);
        summaryTv.setGravity(Gravity.CENTER);
        root.addView(summaryTv);
        saveBtn = txt("Save edited copy", 17, Color.WHITE);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(0, dp(14), 0, dp(14));
        bg(saveBtn, 0xFF2563EB, 14);
        saveBtn.setOnClickListener(v -> export());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(6);
        root.addView(saveBtn, sl);
        setContentView(root);

        trimBar = new RangeBar(this);
        trimBar.listener = (s, e, startMoved) -> {
            if (!ready) return;
            trimS = (long) (s * dur);
            trimE = (long) (e * dur);
            vv.seekTo((int) (startMoved ? trimS : trimE));
            refreshSummary();
        };
        cutBar = new RangeBar(this);
        cutBar.accent = 0xFFE53935;
        cutBar.listener = (s, e, startMoved) -> {
            if (!ready) return;
            cutS = (long) (s * dur);
            cutE = (long) (e * dur);
            vv.seekTo((int) (startMoved ? cutS : cutE));
            refreshSummary();
        };
        trimLabel = txt("", 13, Color.WHITE);
        cutLabel = txt("", 13, Color.WHITE);

        vv.setOnPreparedListener(mp -> {
            dur = vv.getDuration();
            vw = mp.getVideoWidth();
            vh = mp.getVideoHeight();
            trimS = 0;
            trimE = dur;
            trimBar.set(0, 1);
            crop.setVideo(vw, vh);
            ready = true;
            vv.seekTo(1);
            showTab(tab);
            refreshSummary();
            h.post(ticker);
        });
        vv.setOnCompletionListener(mp -> {
            playBtn.setText("▶");
        });
        vv.setOnErrorListener((mp, w, e) -> {
            Toast.makeText(this, "Could not play this video.", Toast.LENGTH_LONG).show();
            return true;
        });
        vv.setVideoURI(uri);
        showTab(0);
    }

    // -------------------------------------------------------------- playback
    private void togglePlay() {
        if (!ready) return;
        if (vv.isPlaying()) {
            vv.pause();
            playBtn.setText("▶");
        } else {
            long pos = vv.getCurrentPosition();
            if (pos < trimS || pos >= trimE - 150) vv.seekTo((int) trimS);
            vv.start();
            playBtn.setText("⏸");
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (ready) {
                long pos = vv.getCurrentPosition();
                if (vv.isPlaying()) {
                    if (cutS >= 0 && cutE > cutS && pos >= cutS && pos < cutE) {
                        vv.seekTo((int) cutE);
                        pos = cutE;
                    }
                    if (pos >= trimE) {
                        vv.pause();
                        vv.seekTo((int) trimS);
                        playBtn.setText("▶");
                    }
                }
                if (dur > 0) seek.setProgress((int) (1000 * pos / dur));
                timeTv.setText(Videos.fmt(pos) + " / " + Videos.fmt(dur));
            }
            h.postDelayed(this, 120);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        if (ready && vv.isPlaying()) {
            vv.pause();
            playBtn.setText("▶");
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (transformer != null) {
            try { transformer.cancel(); } catch (Exception e) { }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ tabs
    private void showTab(int t) {
        tab = t;
        for (int i = 0; i < 4; i++) {
            bg(tabs[i], i == t ? 0xFF2563EB : 0xFF232B36, 10);
        }
        panel.removeAllViews();
        crop.setVisibility(t == 3 && ready ? View.VISIBLE : View.GONE);
        if (t == 3 && ready && vv.isPlaying()) {
            vv.pause();
            playBtn.setText("▶");
        }
        if (t == 0) {
            TextView i = txt("Play your recording. Use Trim to keep only a part, Cut to remove a section from the middle, Crop to keep only part of the screen. You can combine them, then Save.", 13, 0xFF8A94A3);
            i.setPadding(dp(4), dp(12), dp(4), dp(4));
            panel.addView(i);
        } else if (t == 1) {
            panel.addView(trimLabel);
            panel.addView(trimBar);
            LinearLayout r = new LinearLayout(this);
            r.addView(button("[  Start here", v -> {
                if (!ready) return;
                long p = Math.min(vv.getCurrentPosition(), trimE - 300);
                trimS = Math.max(0, p);
                trimBar.set(trimS / (float) dur, trimE / (float) dur);
                refreshSummary();
            }), lp());
            r.addView(button("End here  ]", v -> {
                if (!ready) return;
                long p = Math.max(vv.getCurrentPosition(), trimS + 300);
                trimE = Math.min(dur, p);
                trimBar.set(trimS / (float) dur, trimE / (float) dur);
                refreshSummary();
            }), lp());
            r.addView(button("Reset", v -> {
                trimS = 0;
                trimE = dur;
                trimBar.set(0, 1);
                refreshSummary();
            }), lp());
            panel.addView(r);
            refreshSummary();
        } else if (t == 2) {
            if (ready && cutS < 0) {
                long p = vv.getCurrentPosition();
                cutS = Math.max(trimS, Math.min(p, trimE - 1000));
                cutE = Math.min(trimE, cutS + Math.max(500, dur / 10));
                cutBar.set(cutS / (float) dur, cutE / (float) dur);
            }
            panel.addView(cutLabel);
            panel.addView(cutBar);
            LinearLayout r = new LinearLayout(this);
            r.addView(button("[  From here", v -> {
                if (!ready) return;
                long p = Math.min(vv.getCurrentPosition(), (cutE < 0 ? dur : cutE) - 300);
                cutS = Math.max(0, p);
                if (cutE <= cutS) cutE = Math.min(dur, cutS + 1000);
                cutBar.set(cutS / (float) dur, cutE / (float) dur);
                refreshSummary();
            }), lp());
            r.addView(button("To here  ]", v -> {
                if (!ready) return;
                long p = Math.max(vv.getCurrentPosition(), (cutS < 0 ? 0 : cutS) + 300);
                cutE = Math.min(dur, p);
                if (cutS < 0 || cutS >= cutE) cutS = Math.max(0, cutE - 1000);
                cutBar.set(cutS / (float) dur, cutE / (float) dur);
                refreshSummary();
            }), lp());
            r.addView(button("No cut", v -> {
                cutS = -1;
                cutE = -1;
                showTab(2);
                refreshSummary();
            }), lp());
            panel.addView(r);
            refreshSummary();
        } else {
            TextView i = txt("Drag the corners or the box to choose the part of the screen to keep.", 13, 0xFF8A94A3);
            i.setPadding(dp(4), dp(8), dp(4), dp(6));
            panel.addView(i);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            LinearLayout r = new LinearLayout(this);
            String[] rn = {"Free", "1:1", "16:9", "9:16", "4:3", "3:4"};
            final float[] rv = {0f, 1f, 16f / 9f, 9f / 16f, 4f / 3f, 3f / 4f};
            for (int k = 0; k < rn.length; k++) {
                final int idx = k;
                r.addView(button(rn[k], v -> {
                    if (rv[idx] == 0f) crop.ratio = 0f; else crop.setRatio(rv[idx]);
                }), lp());
            }
            r.addView(button("Reset", v -> crop.reset()), lp());
            hs.addView(r);
            panel.addView(hs);
            refreshSummary();
        }
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.rightMargin = dp(8);
        l.topMargin = dp(4);
        return l;
    }

    private boolean cutValid() {
        if (cutS < 0 || cutE <= cutS) return false;
        long cs = Math.max(cutS, trimS), ce = Math.min(cutE, trimE);
        return ce - cs > 200;
    }

    private boolean trimmed() {
        return trimS > 100 || trimE < dur - 100;
    }

    private void refreshSummary() {
        if (!ready) return;
        trimLabel.setText("Keep  " + Videos.fmt(trimS) + " – " + Videos.fmt(trimE) + "   (" + Videos.fmt(trimE - trimS) + ")");
        cutLabel.setText(cutS < 0 ? "No section removed" : "Remove  " + Videos.fmt(cutS) + " – " + Videos.fmt(cutE) + "   (" + Videos.fmt(cutE - cutS) + ")");
        ArrayList<String> parts = new ArrayList<>();
        if (trimmed()) parts.add("Trim " + Videos.fmt(trimS) + "–" + Videos.fmt(trimE));
        if (cutValid()) parts.add("Cut " + Videos.fmt(Math.max(cutS, trimS)) + "–" + Videos.fmt(Math.min(cutE, trimE)));
        if (!crop.isFull()) {
            int[] c = cropPixels();
            parts.add("Crop " + c[2] + "×" + c[3]);
        }
        boolean edited = !parts.isEmpty();
        summaryTv.setText(edited ? android.text.TextUtils.join("  ·  ", parts) : "No edits yet");
        saveBtn.setAlpha(edited ? 1f : 0.4f);
    }

    private int[] cropPixels() {
        int x = (int) Math.round(crop.n.left * vw) & ~1, y = (int) Math.round(crop.n.top * vh) & ~1;
        int w = (int) Math.round(crop.n.width() * vw) & ~1, hh = (int) Math.round(crop.n.height() * vh) & ~1;
        w = Math.max(16, Math.min(w, vw - x & ~1));
        hh = Math.max(16, Math.min(hh, vh - y & ~1));
        return new int[]{x, y, w, hh};
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Delete this video?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
                if (Videos.delete(this, uri)) finish();
                else Toast.makeText(this, "Could not delete it.", Toast.LENGTH_LONG).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- export
    private void export() {
        if (!ready) return;
        boolean doCrop = !crop.isFull();
        if (!trimmed() && !cutValid() && !doCrop) {
            Toast.makeText(this, "Make a trim, cut or crop first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (vv.isPlaying()) {
            vv.pause();
            playBtn.setText("▶");
        }
        List<long[]> segs = new ArrayList<>();
        if (cutValid()) {
            long cs = Math.max(cutS, trimS), ce = Math.min(cutE, trimE);
            if (cs - trimS > 200) segs.add(new long[]{trimS, cs});
            if (trimE - ce > 200) segs.add(new long[]{ce, trimE});
            if (segs.isEmpty()) {
                Toast.makeText(this, "Nothing would be left after the cut.", Toast.LENGTH_LONG).show();
                return;
            }
        } else {
            segs.add(new long[]{trimS, trimE});
        }

        List<Effect> fx = new ArrayList<>();
        int outW = vw, outH = vh;
        if (doCrop) {
            int[] c = cropPixels();
            float left = 2f * c[0] / vw - 1f, right = 2f * (c[0] + c[2]) / vw - 1f;
            float top = 1f - 2f * c[1] / vh, bottom = 1f - 2f * (c[1] + c[3]) / vh;
            fx.add(new Crop(left, right, bottom, top));
            outW = c[2];
            outH = c[3];
        }
        long total = 0;
        List<EditedMediaItem> items = new ArrayList<>();
        for (long[] s : segs) {
            MediaItem mi = new MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s[0]).setEndPositionMs(s[1]).build())
                .build();
            EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mi);
            if (!fx.isEmpty()) eb.setEffects(new Effects(Collections.<AudioProcessor>emptyList(), fx));
            items.add(eb.build());
            total += s[1] - s[0];
        }

        // keep quality: reuse the source bitrate, scaled for a cropped area
        int br = 16_000_000;
        try {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            r.setDataSource(this, uri);
            String b = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (b != null) br = Integer.parseInt(b);
            r.release();
        } catch (Exception e) { }
        br = (int) (br * ((double) outW * outH / ((double) vw * vh)) * 1.15);
        br = Math.max(3_000_000, Math.min(45_000_000, br));

        final File tmp = new File(getCacheDir(), "export.mp4");
        tmp.delete();
        VideoEncoderSettings ves = new VideoEncoderSettings.Builder().setBitrate(br).build();
        DefaultEncoderFactory ef = new DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(ves).setEnableFallback(true).build();
        transformer = new Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(ef)
            .addListener(new Transformer.Listener() {
                @Override
                public void onCompleted(Composition composition, ExportResult result) {
                    h.removeCallbacks(progressTick);
                    dismissProgress();
                    try {
                        Uri nu = Videos.saveEdited(EditorActivity.this, tmp);
                        tmp.delete();
                        Toast.makeText(EditorActivity.this, "Saved to Movies/ScreenRec", Toast.LENGTH_LONG).show();
                        startActivity(new Intent(EditorActivity.this, EditorActivity.class)
                            .putExtra("uri", nu.toString()).putExtra("name", "Edited video"));
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(EditorActivity.this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onError(Composition composition, ExportResult result, ExportException ex) {
                    h.removeCallbacks(progressTick);
                    dismissProgress();
                    tmp.delete();
                    Toast.makeText(EditorActivity.this, "Edit failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }).build();

        showProgress();
        try {
            transformer.start(new Composition.Builder(new EditedMediaItemSequence(items)).build(), tmp.getAbsolutePath());
            h.postDelayed(progressTick, 300);
        } catch (Exception e) {
            dismissProgress();
            Toast.makeText(this, "Could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (transformer == null || progressBar == null) return;
            ProgressHolder ph = new ProgressHolder();
            int st = transformer.getProgress(ph);
            if (st == Transformer.PROGRESS_STATE_AVAILABLE) {
                progressBar.setProgress(ph.progress);
                progressTv.setText("Saving…  " + ph.progress + "%");
            }
            h.postDelayed(this, 300);
        }
    };

    private void showProgress() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(20), dp(24), dp(8));
        progressTv = txt("Saving…  0%", 15, Color.BLACK);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        l.addView(progressTv);
        l.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        progressDlg = new AlertDialog.Builder(this).setTitle("Editing video").setView(l).setCancelable(false)
            .setNegativeButton("Cancel", (d, w) -> {
                h.removeCallbacks(progressTick);
                try { transformer.cancel(); } catch (Exception e) { }
            }).show();
    }

    private void dismissProgress() {
        try { if (progressDlg != null) progressDlg.dismiss(); } catch (Exception e) { }
        progressDlg = null;
        progressBar = null;
    }
}
