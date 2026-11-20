package com.mazedcu.screenrec;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.Size;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GalleryActivity extends Activity {
    private LinearLayout list;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(30), dp(16), dp(10));
        TextView back = new TextView(this);
        back.setText("←  Gallery");
        back.setTextColor(Color.WHITE);
        back.setTextSize(22);
        back.setPadding(0, 0, 0, dp(12));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        list.removeAllViews();
        List<Videos.Video> vs = Videos.list(this);
        if (vs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No recordings yet.\nGo back and tap Start recording.");
            t.setTextColor(0xFF8A94A3);
            t.setTextSize(15);
            t.setPadding(0, dp(30), 0, 0);
            list.addView(t);
            return;
        }
        for (final Videos.Video v : vs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFF1A212B);
            g.setCornerRadius(dp(12));
            row.setBackground(g);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(8);

            final ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setBackgroundColor(0xFF000000);
            row.addView(th, new LinearLayout.LayoutParams(dp(120), dp(68)));
            pool.execute(() -> {
                try {
                    final Bitmap bm = Build.VERSION.SDK_INT >= 29
                        ? getContentResolver().loadThumbnail(v.uri, new Size(320, 180), null)
                        : ThumbnailUtils.createVideoThumbnail(v.uri.getPath(), MediaStore.Video.Thumbnails.MINI_KIND);
                    if (bm != null) runOnUiThread(() -> th.setImageBitmap(bm));
                } catch (Exception e) { }
            });

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12), 0, 0, 0);
            TextView n = new TextView(this);
            n.setText(v.name);
            n.setTextColor(Color.WHITE);
            n.setTextSize(14);
            n.setSingleLine(true);
            TextView m = new TextView(this);
            m.setText(Videos.fmt(v.dur) + "  ·  " + Videos.fmtSize(v.size) + "\n" + DateFormat.format("d MMM yyyy, h:mm a", new Date(v.date)));
            m.setTextColor(0xFF8A94A3);
            m.setTextSize(12);
            info.addView(n);
            info.addView(m);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(x -> startActivity(new Intent(this, EditorActivity.class).putExtra("uri", v.uri.toString()).putExtra("name", v.name)));
            list.addView(row, rl);
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
