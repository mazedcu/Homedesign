package com.mazedcu.screenrec;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Finds, saves, shares and deletes the recordings made by this app. */
public class Videos {
    public static class Video {
        public Uri uri;
        public String name;
        public long dur, size, date;
    }

    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, (s % 3600) / 60, s % 60)
            : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static String fmtSize(long b) {
        return b >= 1048576 ? String.format(Locale.US, "%.1f MB", b / 1048576.0) : (b / 1024) + " KB";
    }

    public static List<Video> list(Context c) {
        ArrayList<Video> out = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED};
                Cursor q = c.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{Environment.DIRECTORY_MOVIES + "/ScreenRec%"},
                    MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (q != null) {
                    while (q.moveToNext()) {
                        Video v = new Video();
                        v.uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, q.getLong(0));
                        v.name = q.getString(1);
                        v.dur = q.getLong(2);
                        v.size = q.getLong(3);
                        v.date = q.getLong(4) * 1000;
                        out.add(v);
                    }
                    q.close();
                }
            } else {
                File d = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                File[] fs = d == null ? null : d.listFiles((dir, n) -> n.endsWith(".mp4"));
                if (fs != null) {
                    Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : fs) {
                        Video v = new Video();
                        v.uri = Uri.fromFile(f);
                        v.name = f.getName();
                        v.size = f.length();
                        v.date = f.lastModified();
                        try {
                            MediaMetadataRetriever r = new MediaMetadataRetriever();
                            r.setDataSource(f.getAbsolutePath());
                            v.dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                            r.release();
                        } catch (Exception e) { }
                        out.add(v);
                    }
                }
            }
        } catch (Exception e) { }
        return out;
    }

    /** Copies an exported cache file into Movies/ScreenRec and returns its Uri. */
    public static Uri saveEdited(Context c, File src) throws Exception {
        String name = "ScreenRec_edit_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        InputStream in = new FileInputStream(src);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRec");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri u = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                OutputStream o = c.getContentResolver().openOutputStream(u);
                copy(in, o);
                o.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                c.getContentResolver().update(u, done, null, null);
                return u;
            } else {
                File d = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                d.mkdirs();
                File f = new File(d, name);
                OutputStream o = new java.io.FileOutputStream(f);
                copy(in, o);
                o.close();
                return Uri.fromFile(f);
            }
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }

    public static boolean delete(Context c, Uri u) {
        try {
            if ("file".equals(u.getScheme())) return new File(u.getPath()).delete();
            return c.getContentResolver().delete(u, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void share(Context c, Uri u) {
        try {
            Uri s = u;
            if ("file".equals(u.getScheme())) s = FileProvider.getUriForFile(c, c.getPackageName() + ".fp", new File(u.getPath()));
            Intent i = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, s)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(Intent.createChooser(i, "Share video"));
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "Could not share.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
