package com.samp.mobile.game;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.joom.paranoid.Obfuscate;
import com.samp.mobile.game.ui.AttachEdit;
import com.samp.mobile.game.ui.CustomKeyboard;
import com.samp.mobile.game.ui.LoadingScreen;
import com.samp.mobile.game.ui.dialog.DialogManager;
import com.samp.mobile.launcher.util.SharedPreferenceCore;
import com.samp.mobile.launcher.util.SignatureChecker;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
@Obfuscate
public class SAMP extends GTASA implements CustomKeyboard.InputListener, HeightProvider.HeightListener {
    private static final String TAG = "SAMP";
    private static SAMP instance;

    private CustomKeyboard mKeyboard;
    private DialogManager mDialog;
    private HeightProvider mHeightProvider;

    private AttachEdit mAttachEdit;
    private LoadingScreen mLoadingScreen;
    private final ExecutorService mModelDownloadExecutor = Executors.newFixedThreadPool(3);

    public native void sendDialogResponse(int i, int i2, int i3, byte[] str);
    public native void onCustomModelDownloaded(int fileType, int crc, boolean success);

    public static SAMP getInstance() {
        return instance;
    }

    private void showTab()
    {

    }

    private void hideTab()
    {

    }

    private void setTab(int id, String name, int score, int ping)
    {

    }

    private void clearTab()
    {

    }

    private void showLoadingScreen()
    {

    }

    private void hideLoadingScreen()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mLoadingScreen.hide();
            }
        });
    }

    public void setPauseState(boolean pause) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (pause) {
                    mDialog.hideWithoutReset();
                    mAttachEdit.hideWithoutReset();
                }
                else {
                    if(mDialog.isShow)
                        mDialog.showWithOldContent();
                    if(mAttachEdit.isShow)
                        mAttachEdit.showWithoutReset();
                }
            }
        });
    }

    public void exitGame(){
        finishAndRemoveTask();
        System.exit(0);
    }

    public void showDialog(int dialogId, int dialogTypeId, byte[] bArr, byte[] bArr2, byte[] bArr3, byte[] bArr4) {
        final String caption = new String(bArr, StandardCharsets.UTF_8);
        final String content = new String(bArr2, StandardCharsets.UTF_8);
        final String leftBtnText = new String(bArr3, StandardCharsets.UTF_8);
        final String rightBtnText = new String(bArr4, StandardCharsets.UTF_8);
        runOnUiThread(() -> { this.mDialog.show(dialogId, dialogTypeId, caption, content, leftBtnText, rightBtnText); });
    }

    private native void onInputEnd(byte[] str);
    @Override
    public void OnInputEnd(String str)
    {
        byte[] toReturn = null;
        try
        {
            toReturn = str.getBytes("windows-1251");
        }
        catch(UnsupportedEncodingException e)
        {

        }

        try {
            onInputEnd(toReturn);
        }
        catch (UnsatisfiedLinkError e5) {
            Log.e(TAG, e5.getMessage());
        }
    }

    private void showKeyboard()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d("AXL", "showKeyboard()");
                mKeyboard.ShowInputLayout();
            }
        });
    }

    private void hideKeyboard()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mKeyboard.HideInputLayout();
            }
        });
    }

    private void showEditObject()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAttachEdit.show();
            }
        });
    }

    private void hideEditObject()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAttachEdit.hide();
            }
        });
    }

    public void downloadCustomModel(final int fileType, final int crc, final String urlStr, final String destPath)
    {
        if (urlStr == null || destPath == null || urlStr.isEmpty() || destPath.isEmpty()) {
            notifyCustomModelDownloaded(fileType, crc, false);
            return;
        }
        try {
            mModelDownloadExecutor.execute(() -> {
                boolean ok = false;
                try {
                    ok = performCustomModelHttpDownload(urlStr, destPath);
                } catch (Throwable t) {
                    Log.w(TAG, "0.3.DL HTTP download failed for " + urlStr + ": " + t.getMessage());
                    ok = false;
                } finally {
                    notifyCustomModelDownloaded(fileType, crc, ok);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "0.3.DL executor rejected download: " + t.getMessage());
            notifyCustomModelDownloaded(fileType, crc, false);
        }
    }

    private void notifyCustomModelDownloaded(int fileType, int crc, boolean success) {
        try {
            onCustomModelDownloaded(fileType, crc, success);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "onCustomModelDownloaded link error: " + e.getMessage());
        }
    }

    private boolean performCustomModelHttpDownload(String initialUrl, String destPath) {
        final long MAX_BYTES = 64L * 1024L * 1024L;
        final int MAX_REDIRECTS = 5;

        String currentUrl = initialUrl.trim();
        File destFile = new File(destPath);
        File parent = destFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(destPath + ".tmp");

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            String lower = currentUrl.toLowerCase(java.util.Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                Log.w(TAG, "0.3.DL rejected non-HTTP URL: " + currentUrl);
                return false;
            }

            HttpURLConnection conn = null;
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "SAMP/0.3");
                conn.connect();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        return false;
                    }
                    URL nextUrl = new URL(url, location);
                    currentUrl = nextUrl.toString();
                    continue;
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "0.3.DL HTTP status " + code + " for " + currentUrl);
                    return false;
                }

                long contentLength = conn.getContentLengthLong();
                if (contentLength > MAX_BYTES) {
                    Log.w(TAG, "0.3.DL file too large: " + contentLength);
                    return false;
                }

                long totalRead = 0L;
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream out = new FileOutputStream(tmpFile, false)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        totalRead += n;
                        if (totalRead > MAX_BYTES) {
                            Log.w(TAG, "0.3.DL stream exceeded max size");
                            return false;
                        }
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }

                if (totalRead <= 0) {
                    if (tmpFile.exists()) tmpFile.delete();
                    return false;
                }

                if (destFile.exists()) {
                    destFile.delete();
                }
                if (!tmpFile.renameTo(destFile)) {
                    if (tmpFile.exists()) tmpFile.delete();
                    return false;
                }
                return true;
            } catch (Throwable t) {
                if (tmpFile.exists()) tmpFile.delete();
                Log.w(TAG, "0.3.DL download error (" + currentUrl + "): " + t.getMessage());
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return false;
    }

    private void logJavaCrash(String stage, Throwable t) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File out = new java.io.File(dir, "samp_java_log.txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(out, false))) {
                pw.println("Stage: " + stage);
                pw.println("ABI: " + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS));
                t.printStackTrace(pw);
                pw.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "**** onCreate");
        try {
            super.onCreate(savedInstanceState);

            mKeyboard = new CustomKeyboard(this);

            mDialog = new DialogManager(this);

            mAttachEdit = new AttachEdit(this);

            mLoadingScreen = new LoadingScreen(this);

            instance = this;

            try {
                initializeSAMP();
            } catch (UnsatisfiedLinkError e5) {
                logJavaCrash("initializeSAMP", e5);
                Log.e(TAG, e5.getMessage() != null ? e5.getMessage() : e5.toString());
            }
        } catch (Throwable t) {
            logJavaCrash("SAMP.onCreate", t);
            Log.e(TAG, "SAMP.onCreate failed", t);
            Toast.makeText(this, "Game init error: " + t.getClass().getSimpleName() + ": " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private native void initializeSAMP();



    @Override
    public void onStart() {
        Log.i(TAG, "**** onStart");
        super.onStart();
    }

    @Override
    public void onRestart() {
        Log.i(TAG, "**** onRestart");
        super.onRestart();
    }

    @Override
    public void onResume() {
        Log.i(TAG, "**** onResume");
        super.onResume();
        //mHeightProvider.init(view);
    }

    public native void onEventBackPressed();

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        onEventBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK)
        {
            onEventBackPressed();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onPause() {
        Log.i(TAG, "**** onPause");
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.i(TAG, "**** onStop");
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "**** onDestroy");
        mModelDownloadExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onHeightChanged(int orientation, int height) {
        //mKeyboard.onHeightChanged(height);
        //mDialog.onHeightChanged(height);
    }
}
