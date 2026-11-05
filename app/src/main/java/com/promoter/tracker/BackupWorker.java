package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BackupWorker extends Worker {
    static final String PREFS = "promoter_backup_native";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_FREQUENCY = "frequency";
    static final String KEY_LAST_BACKUP = "last_backup";

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) return Result.success();

        File snapshot = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.json");
        if (!snapshot.isFile()) return Result.retry();

        try {
            String json = new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8);
            String path = BackupStorage.writeBackup(context, json, "automatic");
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            prefs.edit().putString(KEY_LAST_BACKUP,
                    "Automatic backup saved · " + stamp + " · " + path).apply();
            return Result.success();
        } catch (IOException | RuntimeException e) {
            prefs.edit().putString(KEY_LAST_BACKUP,
                    "Automatic backup failed: " + (e.getMessage() == null ? "Storage error" : e.getMessage())).apply();
            return Result.retry();
        }
    }
}
