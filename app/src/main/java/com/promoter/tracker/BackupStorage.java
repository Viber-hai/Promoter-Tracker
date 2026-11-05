package com.promoter.tracker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BackupStorage {
    private static final String FOLDER = "Promoter Tracker Backups";

    private BackupStorage() {}

    static String writeBackup(Context context, String json, String kind) throws IOException {
        if (json == null || json.trim().isEmpty()) throw new IOException("There is no backup data to save.");
        if (json.length() > 5 * 1024 * 1024) throw new IOException("Backup is larger than 5 MB.");

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String fileName = "promoter-tracker-" + kind + "-" + stamp + ".json";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Android could not create a backup file in Downloads.");

            boolean complete = false;
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Android could not open the new backup file.");
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
                complete = true;
                return "Downloads/" + FOLDER + "/" + fileName;
            } finally {
                if (!complete) {
                    try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
                }
            }
        }

        // Android 8/9 fallback: app-specific Documents storage, no broad storage permission.
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) base = context.getFilesDir();
        File folder = new File(base, FOLDER);
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create the app backup folder.");
        File target = new File(folder, fileName);
        File temp = new File(folder, fileName + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            if (target.exists() && !target.delete()) throw new IOException("Could not replace the temporary backup file.");
            if (!temp.renameTo(target)) throw new IOException("Could not finish writing the backup file.");
        }
        return target.getAbsolutePath();
    }
}
