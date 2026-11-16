package com.promoter.tracker;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

final class UpdateManager {
    static final String PREFS = "promoter_update_state";
    static final String KEY_DOWNLOAD_ID = "pending_update_download_id";
    static final String CHANNEL = "promoter_app_updates";
    static final String RELEASES_API = "https://api.github.com/repos/Viber-hai/Promoter-Tracker/releases?per_page=100";
    private UpdateManager() {}

    static JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Promoter-Tracker-Android");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("Update server returned HTTP " + code);
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            JSONArray releases = new JSONArray(body.toString());
            JSONObject newest = null;
            int newestCode = -1;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue;
                int versionCode = releaseVersionCode(release);
                if (versionCode <= newestCode || !hasApkAsset(release)) continue;
                newest = release;
                newestCode = versionCode;
            }
            if (newest == null) throw new Exception("No published Promoter Tracker release with an APK was found.");
            return newest;
        } finally { c.disconnect(); }
    }

    private static boolean hasApkAsset(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return false;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset != null && asset.optString("name", "").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
                    && !asset.optString("browser_download_url", "").isEmpty()) return true;
        }
        return false;
    }

    static int currentVersionCode(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
    }

    static int releaseVersionCode(JSONObject release) {
        String tag = release.optString("tag_name", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(tag);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String apkUrl(JSONObject release) throws Exception {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) throw new Exception("The latest release has no APK attached yet.");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk"))
                return asset.getString("browser_download_url");
        }
        throw new Exception("The latest release does not contain an APK.");
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    static void notifyUpdate(Context context, JSONObject release) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 7101, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String version = release.optString("tag_name", "New version");
        android.app.Notification n = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle("Promoter Tracker update available")
                .setContentText(version + " is ready. Tap to review and install.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText("A new Promoter Tracker version " + version + " is available. Open the app to review the changes and update."))
                .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).build();
        try { NotificationManagerCompat.from(context).notify(7101, n); } catch (SecurityException ignored) {}
    }

    static void notifyDownloadReady(Context context) {
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 7102, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle("Promoter Tracker update downloaded")
                .setContentText("Tap to continue installation.")
                .setContentIntent(pi).setAutoCancel(true).build();
        try { NotificationManagerCompat.from(context).notify(7102, n); } catch (SecurityException ignored) {}
    }

    static long download(Context context, String apkUrl, String version) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Promoter Tracker " + version)
                .setDescription("Downloading the signed app update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "PromoterTracker-" + version + ".apk");
        long id = dm.enqueue(req);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_DOWNLOAD_ID, id).apply();
        return id;
    }

    static void installPending(Context context) {
        long id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, -1);
        if (id < 0) return;
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settings);
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(install);
    }
}
