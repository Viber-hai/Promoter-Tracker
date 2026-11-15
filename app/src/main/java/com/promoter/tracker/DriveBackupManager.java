package com.promoter.tracker;

import android.content.Context;
import android.accounts.Account;
import android.util.Base64;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Private, encrypted Drive app-data backups. No user Drive files are read. */
final class DriveBackupManager {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String BASE = "https://www.googleapis.com/drive/v3/files";
    private DriveBackupManager() {}

    static boolean connected(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        return account != null && account.getAccount() != null && account.getGrantedScopes().contains(
                new com.google.android.gms.common.api.Scope(SCOPE));
    }

    static String accountEmail(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        return connected(context) ? account.getEmail() : null;
    }

    private static String token(Context context) throws Exception {
        GoogleSignInAccount signedIn = GoogleSignIn.getLastSignedInAccount(context);
        Account account = signedIn == null ? null : signedIn.getAccount();
        if (account == null || !connected(context)) throw new IOException("Connect your Google account in Backup & Restore first.");
        return GoogleAuthUtil.getToken(context, account, "oauth2:" + SCOPE);
    }

    private static byte[] request(Context context, String method, String url, byte[] body, String type) throws Exception {
        String accessToken = token(context);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        if (type != null) connection.setRequestProperty("Content-Type", type);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        }
        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            byte[] response = input == null ? new byte[0] : readAll(input, 8 * 1024 * 1024);
            if (status < 200 || status >= 300) throw new IOException("Google Drive returned HTTP " + status + ". " +
                    new String(response, StandardCharsets.UTF_8).substring(0, Math.min(240, response.length)));
            return response;
        } finally { connection.disconnect(); }
    }

    private static byte[] readAll(InputStream input, int limit) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > limit) throw new IOException("Google Drive backup exceeds the size limit.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String upload(Context context, String json, String kind) throws Exception {
        if (json == null || json.isEmpty() || json.length() > 5 * 1024 * 1024) throw new IOException("Invalid backup size.");
        byte[] encrypted = BackupCrypto.encryptBackup(context, json);
        String name = "promoter-tracker-" + ("automatic".equals(kind) ? "automatic" : "manual") + "-"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".ptbackup";
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("parents", new JSONArray().put("appDataFolder"));
        meta.put("mimeType", "application/octet-stream");
        String boundary = "ptbackup" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + meta + "\r\n--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(encrypted);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] response = request(context, "POST",
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name",
                body.toByteArray(), "multipart/related; boundary=" + boundary);
        JSONObject created = new JSONObject(new String(response, StandardCharsets.UTF_8));
        if (!created.has("id")) throw new IOException("Drive did not confirm the backup.");
        return name;
    }

    static JSONArray list(Context context) throws Exception {
        String url = BASE + "?spaces=appDataFolder&q=" +
                java.net.URLEncoder.encode("name contains 'promoter-tracker-' and trashed = false", "UTF-8") +
                "&fields=nextPageToken,files(id,name,createdTime,size)&orderBy=createdTime%20desc&pageSize=50";
        JSONObject response = new JSONObject(new String(request(context, "GET", url, null, null), StandardCharsets.UTF_8));
        return response.optJSONArray("files") == null ? new JSONArray() : response.getJSONArray("files");
    }

    static String downloadEncoded(Context context, String id) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9_-]{8,200}")) throw new IOException("Invalid backup identifier.");
        byte[] encrypted = request(context, "GET", BASE + "/" + id + "?alt=media", null, null);
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }
}
