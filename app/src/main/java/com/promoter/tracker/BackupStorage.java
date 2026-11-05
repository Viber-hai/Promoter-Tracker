package com.promoter.tracker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BackupStorage {
    private static final String PREFS = BackupWorker.PREFS;
    private static final String FOLDER_URI_PREFIX = "folder_uri_";
    private static final String FOLDER_NAME_PREFIX = "folder_name_";

    private BackupStorage() {}

    static String defaultFolderLabel(String kind) {
        String sub = "automatic".equals(kind) ? "Automatic Backups" : "Manual Backups";
        return "Downloads / Promoter Tracker / " + sub;
    }

    static String writeBackup(Context context, String json, String kind) throws IOException {
        if (!"automatic".equals(kind)) kind = "manual";
        if (json == null || json.trim().isEmpty()) throw new IOException("There is no backup data to save.");
        if (json.length() > 5 * 1024 * 1024) throw new IOException("Backup is larger than 5 MB.");

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String fileName = "promoter-tracker-" + kind + "-" + stamp + ".json";
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String selectedTree = prefs.getString(FOLDER_URI_PREFIX + kind, null);

        if (selectedTree != null && !selectedTree.isEmpty()) {
            return writeToSelectedFolder(context, Uri.parse(selectedTree), fileName, json, kind);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeToDownloads(context, fileName, json, kind);
        }

        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) base = context.getFilesDir();
        File folder = new File(new File(base, "Promoter Tracker"), kind.equals("automatic") ? "Automatic Backups" : "Manual Backups");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create " + defaultFolderLabel(kind) + ".");
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

    private static String writeToSelectedFolder(Context context, Uri treeUri, String fileName,
                                                  String json, String kind) throws IOException {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.isDirectory() || !tree.canWrite()) {
            throw new IOException("The selected " + kind + " backup folder is no longer writable. Choose it again.");
        }
        DocumentFile file = tree.createFile("application/json", fileName);
        if (file == null) throw new IOException("Android could not create the backup file in the selected folder.");
        boolean complete = false;
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri(), "w")) {
            if (out == null) throw new IOException("Android could not open the new backup file.");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            complete = true;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String label = prefs.getString(FOLDER_NAME_PREFIX + kind, tree.getName());
            return (label == null ? tree.getName() : label) + "/" + fileName;
        } finally {
            if (!complete) file.delete();
        }
    }

    private static String writeToDownloads(Context context, String fileName, String json,
                                           String kind) throws IOException {
        String sub = kind.equals("automatic") ? "Automatic Backups" : "Manual Backups";
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/Promoter Tracker/" + sub;
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Android could not create a backup file in " + relativePath + ".");

        boolean complete = false;
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Android could not open the new backup file.");
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, ready, null, null) <= 0) {
                throw new IOException("Android could not finish publishing the backup file.");
            }
            complete = true;
            return relativePath + "/" + fileName;
        } finally {
            if (!complete) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
            }
        }
    }
}
