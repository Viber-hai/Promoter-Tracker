package com.promoter.tracker;

import androidx.fragment.app.FragmentActivity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.DownloadManager;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import java.util.concurrent.Executors;

import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int AUTO_FOLDER_REQUEST = 1002;
    private static final int MANUAL_FOLDER_REQUEST = 1003;
    private static final int GOOGLE_SIGN_IN_REQUEST = 1004;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingFolderKind;
    private long pausedAt = 0L;
    private boolean pageLoaded = false;
    private boolean updateDialogShowing = false;
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private ProgressDialog updateProgressDialog;
    private Runnable updateProgressPoll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(Color.parseColor(dark ? "#0f1424" : "#f3f5fa"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new AppBackupBridge(this), "AppBackup");
        web.addJavascriptInterface(this, "AppUpdates");
        BackupScheduler.ensureScheduled(this);
        scheduleUpdateChecks();
        UpdateManager.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7102);
        checkForUpdates(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream","application/x-promoter-tracker-backup","application/json","text/plain"});
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker is available.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u=request.getUrl();
                if ("file".equals(u.getScheme()) || ("https".equals(u.getScheme()) && "appassets.androidplatform.net".equals(u.getHost()))) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW,u)); }
                catch(ActivityNotFoundException e){Toast.makeText(MainActivity.this,"No app can open this link.",Toast.LENGTH_SHORT).show();}
                return true;
            }
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                pageLoaded = true;
                view.evaluateJavascript("if(window.onNativeAppReady){window.onNativeAppReady();}else if(window.syncNativeSnapshot){window.syncNativeSnapshot();}",null);
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @JavascriptInterface
    public void checkForUpdates() { checkForUpdates(true); }

    private void scheduleUpdateChecks() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(UpdateWorker.class, 24, TimeUnit.HOURS).build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("promoter_tracker_update_check",
                ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private void checkForUpdates(boolean manual) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject release = UpdateManager.fetchLatestRelease();
                int latest = UpdateManager.releaseVersionCode(release);
                int current = UpdateManager.currentVersionCode(this);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (latest <= current) {
                        if (manual) Toast.makeText(this, "You're using the latest version of Promoter Tracker.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (updateDialogShowing) return;
                    updateDialogShowing = true;
                    String version = release.optString("tag_name", "New version");
                    new AlertDialog.Builder(this)
                            .setTitle("Promoter Tracker update available · " + version)
                            .setMessage("A new version is ready to install.\n\nCurrent version: " + current + "\nNew version: " + latest)
                            .setPositiveButton("Download & Update", (dialog, which) -> {
                                try {
                                    String apk = UpdateManager.apkUrl(release);
                                    long downloadId = UpdateManager.download(this, apk, version);
                                    showUpdateDownloadProgress(downloadId, version);
                                } catch (Exception e) {
                                    Toast.makeText(this, "Could not start update: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("Later", null)
                            .setOnDismissListener(dialog -> updateDialogShowing = false)
                            .show();
                });
            } catch (Exception e) {
                if (manual) runOnUiThread(() -> Toast.makeText(this, "Could not check updates. Check your internet connection and try again.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showUpdateDownloadProgress(long downloadId, String version) {
        if (updateProgressDialog != null && updateProgressDialog.isShowing()) updateProgressDialog.dismiss();
        updateProgressDialog = new ProgressDialog(this);
        updateProgressDialog.setTitle("Downloading Promoter Tracker " + version);
        updateProgressDialog.setMessage("Preparing download…");
        updateProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        updateProgressDialog.setMax(100);
        updateProgressDialog.setProgress(0);
        updateProgressDialog.setCancelable(false);
        updateProgressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, "Run in background", (dialog, which) -> {
            dialog.dismiss();
            Toast.makeText(this, "Download will continue in the background. Check notifications for progress.", Toast.LENGTH_LONG).show();
        });
        updateProgressDialog.show();

        updateProgressPoll = new Runnable() {
            @Override public void run() {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
                try (Cursor cursor = dm.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        if (updateProgressDialog != null && updateProgressDialog.isShowing()) updateProgressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "The update download could not be found. Please try again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    if (total > 0) {
                        int percent = (int) Math.min(100, (downloaded * 100L) / total);
                        if (updateProgressDialog != null && updateProgressDialog.isShowing()) {
                            updateProgressDialog.setProgress(percent);
                            updateProgressDialog.setMessage((downloaded / 1024) + " KB of " + (total / 1024) + " KB");
                        }
                    } else if (updateProgressDialog != null && updateProgressDialog.isShowing()) {
                        updateProgressDialog.setMessage("Connecting to GitHub and preparing the APK…");
                    }

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        if (updateProgressDialog != null && updateProgressDialog.isShowing()) updateProgressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "Update downloaded. Opening Android installer…", Toast.LENGTH_LONG).show();
                        UpdateManager.installPending(MainActivity.this);
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        if (updateProgressDialog != null && updateProgressDialog.isShowing()) updateProgressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "Update download failed (code " + reason + "). Check internet and try again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    updateHandler.postDelayed(this, 700);
                } catch (Exception e) {
                    if (updateProgressDialog != null && updateProgressDialog.isShowing()) updateProgressDialog.dismiss();
                    Toast.makeText(MainActivity.this, "Could not monitor the update download: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        };
        updateHandler.post(updateProgressPoll);
    }

    boolean isBiometricAvailable() {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    void authenticateBiometric() {
        runOnUiThread(() -> {
            if (!isBiometricAvailable()) {
                sendBiometricResult(false, "No enrolled strong fingerprint is available.");
                return;
            }
            Executor executor = ContextCompat.getMainExecutor(this);
            BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    sendBiometricResult(true, "Fingerprint verified.");
                }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    sendBiometricResult(false, errString == null ? "Fingerprint authentication cancelled." : errString.toString());
                }
                @Override public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    sendBiometricResult(false, "Fingerprint not recognized. Try again or use your PIN.");
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Promoter Tracker")
                    .setSubtitle("Confirm your fingerprint")
                    .setNegativeButtonText("Use PIN")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            prompt.authenticate(info);
        });
    }

    private void sendBiometricResult(boolean success, String message) {
        if (web == null) return;
        String js = "window.onNativeBiometricResult && window.onNativeBiometricResult("
                + success + "," + JSONObject.quote(message) + ");";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    void connectGoogleDrive() {
        runOnUiThread(() -> {
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail()
                    .requestScopes(new Scope(DriveBackupManager.SCOPE))
                    .build();
            startActivityForResult(GoogleSignIn.getClient(this, options).getSignInIntent(), GOOGLE_SIGN_IN_REQUEST);
        });
    }

    void disconnectGoogleDrive() {
        runOnUiThread(() -> {
            GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestEmail().requestScopes(new Scope(DriveBackupManager.SCOPE)).build();
            GoogleSignIn.getClient(this, options).signOut().addOnCompleteListener(task ->
                    sendDriveEvent("connection", "Disconnected", null));
        });
    }

    void runDriveTask(String action, String value) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!DriveBackupManager.connected(this)) throw new Exception("Connect Google Drive first.");
                if ("backup".equals(action)) {
                    String name = DriveBackupManager.upload(this, value, "manual");
                    sendDriveEvent("backup", "Saved to Google Drive: " + name, null);
                } else if ("list".equals(action)) {
                    sendDriveEvent("list", "OK", DriveBackupManager.list(this).toString());
                } else if ("download".equals(action)) {
                    sendDriveEvent("download", "OK", DriveBackupManager.downloadEncoded(this, value));
                } else if ("delete".equals(action)) {
                    DriveBackupManager.delete(this, value);
                    sendDriveEvent("delete", "OK", "Backup deleted from Google Drive.");
                } else if ("deleteSelected".equals(action)) {
                    org.json.JSONArray ids = new org.json.JSONArray(value);
                    if (ids.length() == 0 || ids.length() > 1000)
                        throw new Exception("Select between 1 and 1,000 backups.");
                    int deleted = 0;
                    for (int i = 0; i < ids.length(); i++) {
                        String id = ids.optString(i, "");
                        if (!id.matches("[A-Za-z0-9_-]{8,200}"))
                            throw new Exception("The selected backup list contains an invalid item.");
                        DriveBackupManager.delete(this, id);
                        deleted++;
                    }
                    sendDriveEvent("deleteSelected", "OK", deleted + " selected Google Drive backup(s) deleted.");
                } else if ("deleteAll".equals(action)) {
                    org.json.JSONArray files = DriveBackupManager.list(this);
                    int deleted = 0;
                    for (int i = 0; i < files.length(); i++) {
                        String id = files.getJSONObject(i).optString("id", "");
                        if (!id.isEmpty()) { DriveBackupManager.delete(this, id); deleted++; }
                    }
                    sendDriveEvent("deleteAll", "OK", deleted + " Google Drive backup(s) deleted.");
                }
            } catch (Exception e) {
                sendDriveEvent(action, "ERROR: " + (e.getMessage() == null ? "Google Drive request failed." : e.getMessage()), null);
            }
        });
    }

    private void sendDriveEvent(String action, String status, String data) {
        String js = "window.onNativeDriveResult && window.onNativeDriveResult(" +
                JSONObject.quote(action) + "," + JSONObject.quote(status) + "," +
                (data == null ? "null" : JSONObject.quote(data)) + ");";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    public void launchBackupFolderPicker(String kind) {
        pendingFolderKind = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, "automatic".equals(kind) ? AUTO_FOLDER_REQUEST : MANUAL_FOLDER_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == GOOGLE_SIGN_IN_REQUEST) {
            try {
                GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
                sendDriveEvent("connection", "Connected: " + account.getEmail(), null);
            } catch (Exception e) {
                sendDriveEvent("connection", "ERROR: Google sign-in failed. Check your Google Cloud Android OAuth setup. " + e.getMessage(), null);
            }
            return;
        }
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result=null;
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null) result=new Uri[]{data.getData()};
            filePathCallback.onReceiveValue(result); filePathCallback=null; return;
        }
        if ((requestCode==AUTO_FOLDER_REQUEST || requestCode==MANUAL_FOLDER_REQUEST) && resultCode==RESULT_OK && data!=null) {
            Uri uri=data.getData();
            if(uri==null)return;
            try {
                int flags=data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri,flags);
                DocumentFile folder=DocumentFile.fromTreeUri(this,uri);
                String name=folder==null?"Selected folder":folder.getName();
                new AppBackupBridge(this).onBackupFolderSelected(pendingFolderKind,uri,name);
                String kind=pendingFolderKind;
                runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.onBackupFolderChanged && window.onBackupFolderChanged("+JSONObject.quote(kind)+","+JSONObject.quote(name)+");",null);});
            } catch(Exception e) { Toast.makeText(this,"Could not save folder permission: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
        }
    }

    @Override protected void onDestroy(){
        if(filePathCallback!=null){filePathCallback.onReceiveValue(null);filePathCallback=null;}
        if(web!=null){web.removeJavascriptInterface("AppBackup");web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onPause(){pausedAt=SystemClock.elapsedRealtime();super.onPause();if(web!=null)web.onPause();}
    @Override protected void onResume(){
        super.onResume();
        if(web!=null)web.onResume();
        BackupScheduler.ensureScheduled(this);
        UpdateManager.installPending(this);
        if(web!=null && pageLoaded && SecurityStore.isEnabled(this) && pausedAt>0){
            long minutes=(SystemClock.elapsedRealtime()-pausedAt)/60000L;
            int timeout=SecurityStore.getLockTimeout(this);
            if(timeout==0 || minutes>=timeout) web.evaluateJavascript("window.lockPromoterTracker && window.lockPromoterTracker();",null);
        }
        pausedAt=0L;
    }
}
