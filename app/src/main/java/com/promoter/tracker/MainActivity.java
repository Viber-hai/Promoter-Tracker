package com.promoter.tracker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;
import androidx.documentfile.provider.DocumentFile;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int AUTO_FOLDER_REQUEST = 1002;
    private static final int MANUAL_FOLDER_REQUEST = 1003;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private String pendingFolderKind;

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
        BackupScheduler.ensureScheduled(this);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json","text/plain","application/octet-stream"});
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
            @Override public void onPageFinished(WebView view,String url){super.onPageFinished(view,url);view.evaluateJavascript("if(window.syncNativeSnapshot){window.syncNativeSnapshot();}",null);}
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    public void launchBackupFolderPicker(String kind) {
        pendingFolderKind = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, "automatic".equals(kind) ? AUTO_FOLDER_REQUEST : MANUAL_FOLDER_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
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

    @Override protected void onDestroy(){if(filePathCallback!=null){filePathCallback.onReceiveValue(null);filePathCallback=null;}if(web!=null){web.removeJavascriptInterface("AppBackup");web.stopLoading();web.destroy();web=null;}super.onDestroy();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onPause(){super.onPause();if(web!=null)web.onPause();}
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();BackupScheduler.ensureScheduled(this);}
}
