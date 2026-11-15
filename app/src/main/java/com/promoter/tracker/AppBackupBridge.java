package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class AppBackupBridge {
    private final MainActivity activity;
    private final Context context;

    public AppBackupBridge(MainActivity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    @JavascriptInterface
    public String saveSnapshot(String json) {
        if (json == null || json.trim().isEmpty()) return "ERROR|No tracker data.";
        if (json.length() > 5 * 1024 * 1024) return "ERROR|Backup is larger than 5 MB.";
        File target = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.enc");
        File temp = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.tmp");
        try {
            byte[] encrypted = BackupCrypto.encryptLocal(context, json);
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(encrypted); out.getFD().sync();
            }
            if (target.exists() && !target.delete()) return "ERROR|Could not update the encrypted app snapshot.";
            if (!temp.renameTo(target)) return "ERROR|Could not finish saving the encrypted app snapshot.";
            File legacy = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.json");
            if (legacy.exists()) legacy.delete();
            return "OK";
        } catch (Exception e) {
            return "ERROR|" + (e.getMessage() == null ? "Could not encrypt the app backup snapshot." : e.getMessage());
        }
    }

    @JavascriptInterface
    public String createManualBackup(String json) {
        try {
            String path = BackupStorage.writeBackup(context, json, "manual");
            String message = "Encrypted manual backup saved · " + path;
            context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(BackupWorker.KEY_LAST_BACKUP, message).apply();
            return "OK|" + path;
        } catch (Exception e) {
            return "ERROR|" + (e.getMessage() == null ? "Device storage could not save the encrypted backup." : e.getMessage());
        }
    }

    @JavascriptInterface
    public String setRecoveryPassword(String password) {
        if (password == null || password.length() < 10) return "ERROR|Use at least 10 characters.";
        char[] chars = password.toCharArray();
        try {
            BackupCrypto.setRecoveryPassword(context, chars);
            return "OK|Recovery password configured. Keep it safe for restoring on a new phone.";
        } catch (Exception e) {
            return "ERROR|" + (e.getMessage() == null ? "Could not configure recovery password." : e.getMessage());
        } finally { java.util.Arrays.fill(chars, '\0'); }
    }

    @JavascriptInterface
    public boolean hasRecoveryPassword() {
        return BackupCrypto.hasRecoveryPassword(context);
    }

    @JavascriptInterface
    public String decryptBackup(String encodedFile, String recoveryPassword) {
        try {
            byte[] file = Base64.decode(encodedFile, Base64.DEFAULT);
            char[] password = recoveryPassword == null ? new char[0] : recoveryPassword.toCharArray();
            String json = BackupCrypto.decryptBackup(context, file, password);
            return "OK|" + Base64.encodeToString(json.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        } catch (BackupCrypto.RecoveryPasswordRequiredException e) {
            return "PASSWORD_REQUIRED|" + e.getMessage();
        } catch (Exception e) {
            return "ERROR|" + (e.getMessage() == null ? "Could not decrypt this backup." : e.getMessage());
        }
    }

    @JavascriptInterface
    public boolean isDriveConnected() { return DriveBackupManager.connected(context); }

    @JavascriptInterface
    public String getDriveAccount() {
        String email = DriveBackupManager.accountEmail(context);
        return email == null ? "" : email;
    }

    @JavascriptInterface
    public void connectDrive() { activity.connectGoogleDrive(); }

    @JavascriptInterface
    public void disconnectDrive() {
        setDriveAutoBackupEnabled(false);
        activity.disconnectGoogleDrive();
    }

    @JavascriptInterface
    public void backupToDrive(String json) {
        if (json == null || json.length() > 5 * 1024 * 1024) return;
        activity.runDriveTask("backup", json);
    }

    @JavascriptInterface
    public void listDriveBackups() { activity.runDriveTask("list", ""); }

    @JavascriptInterface
    public void downloadDriveBackup(String id) { activity.runDriveTask("download", id); }

    @JavascriptInterface
    public boolean isDriveAutoBackupEnabled() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getBoolean("drive_auto_enabled", false);
    }

    @JavascriptInterface
    public String setDriveAutoBackupEnabled(boolean enabled) {
        if (enabled && !DriveBackupManager.connected(context))
            return "ERROR|Connect your Google account before enabling Drive backups.";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit().putBoolean("drive_auto_enabled", enabled);
        if (enabled) edit.putBoolean(BackupWorker.KEY_ENABLED, false);
        edit.apply();
        BackupScheduler.configure(context);
        return "OK";
    }

    @JavascriptInterface
    public String setDriveAutoBackupFrequency(String frequency) {
        String safe = safeBackupFrequency(frequency);
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("drive_frequency", safe).apply();
        if (prefs.getBoolean("drive_auto_enabled", false)) BackupScheduler.configure(context);
        return "OK";
    }

    @JavascriptInterface
    public String getDriveAutoBackupFrequency() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString("drive_frequency", "weekly");
    }

    @JavascriptInterface
    public String configureAutoBackup(String destination, String localFrequency, String driveFrequency) {
        if (!"off".equals(destination) && !"local".equals(destination) && !"drive".equals(destination))
            return "ERROR|Choose Local, Google Drive, or Off.";
        if ("drive".equals(destination) && !DriveBackupManager.connected(context))
            return "ERROR|Connect your Google account before enabling Google Drive automatic backups.";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean(BackupWorker.KEY_ENABLED, "local".equals(destination))
                .putString(BackupWorker.KEY_FREQUENCY, safeBackupFrequency(localFrequency))
                .putBoolean("drive_auto_enabled", "drive".equals(destination))
                .putString("drive_frequency", safeBackupFrequency(driveFrequency))
                .commit();
        BackupScheduler.configure(context);
        return "OK";
    }

    @JavascriptInterface
    public String getLastDriveBackupStatus() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString("last_drive_backup", "No Google Drive backup yet");
    }

    @JavascriptInterface
    public void chooseBackupFolder(String kind) {
        if (!"automatic".equals(kind) && !"manual".equals(kind)) return;
        activity.runOnUiThread(() -> activity.launchBackupFolderPicker(kind));
    }

    public void onBackupFolderSelected(String kind, android.net.Uri uri, String name) {
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("folder_uri_" + kind, uri.toString())
                .putString("folder_name_" + kind, name).apply();
    }

    @JavascriptInterface
    public String getBackupFolderName(String kind) {
        if (!"automatic".equals(kind) && !"manual".equals(kind)) kind = "manual";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        return prefs.getString("folder_name_" + kind, BackupStorage.defaultFolderLabel(kind));
    }

    @JavascriptInterface
    public String setAutoBackup(boolean enabled, String frequency) {
        String safeFrequency = safeBackupFrequency(frequency);
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit().putBoolean(BackupWorker.KEY_ENABLED, enabled)
                .putString(BackupWorker.KEY_FREQUENCY, safeFrequency);
        if (enabled) edit.putBoolean("drive_auto_enabled", false);
        edit.apply();
        BackupScheduler.configure(context);
        return enabled ? "OK|Automatic encrypted device backup enabled for " + safeFrequency + " schedule."
                : "OK|Automatic device backup disabled.";
    }

    private static String safeBackupFrequency(String frequency) {
        return ("daily".equals(frequency) || "weekly".equals(frequency)
                || "monthly".equals(frequency) || "yearly".equals(frequency)) ? frequency : "weekly";
    }

    @JavascriptInterface
    public boolean isAutoBackupEnabled() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BackupWorker.KEY_ENABLED, false);
    }

    @JavascriptInterface
    public String getAutoBackupFrequency() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString(BackupWorker.KEY_FREQUENCY, "weekly");
    }

    @JavascriptInterface
    public String getLastBackupStatus() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString(BackupWorker.KEY_LAST_BACKUP, "Not yet created");
    }

    @JavascriptInterface
    public boolean isSecurityEnabled() { return SecurityStore.isEnabled(context); }

    @JavascriptInterface
    public boolean isPinConfigured() { return SecurityStore.isPinConfigured(context); }

    @JavascriptInterface
    public boolean isBiometricAvailable() { return activity.isBiometricAvailable(); }

    @JavascriptInterface
    public String setBiometricEnabled(boolean enabled) {
        if (enabled && !SecurityStore.isPinConfigured(context)) return "ERROR|Set a 6-digit backup PIN before enabling fingerprint unlock.";
        if (enabled && !activity.isBiometricAvailable()) return "ERROR|No enrolled strong fingerprint is available on this phone.";
        SecurityStore.setEnabled(context, enabled);
        return "OK";
    }

    @JavascriptInterface
    public String setPin(String pin) {
        try { SecurityStore.setPin(context, pin); return "OK"; }
        catch (Exception e) { return "ERROR|" + e.getMessage(); }
    }

    @JavascriptInterface
    public boolean verifyPin(String pin) { return SecurityStore.verifyPin(context, pin); }

    @JavascriptInterface
    public int getLockTimeoutMinutes() { return SecurityStore.getLockTimeout(context); }

    @JavascriptInterface
    public void setLockTimeoutMinutes(int minutes) { SecurityStore.setLockTimeout(context, minutes); }

    @JavascriptInterface
    public void authenticateBiometric() { activity.authenticateBiometric(); }
}
