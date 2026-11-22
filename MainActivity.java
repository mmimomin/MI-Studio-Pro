package com.miapkstudio.mistudiopro;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * mi Apk Studio দিয়ে তৈরি। WebView-এ www/ ফোল্ডারের ওয়েব অ্যাপ চালায়।
 * ক্যামেরা, মাইক, লোকেশন, ফাইল বাছাই, ডাউনলোড ও ফুলস্ক্রিন ভিডিও সমর্থিত।
 */
public class MainActivity extends Activity {

    private static final String START_URL = "file:///android_asset/www/index.html";
    private static final boolean FULLSCREEN = true;
    private static final boolean KEEP_SCREEN_ON = true;
    private static final boolean ALLOW_ZOOM = true;
    private static final boolean EXTERNAL_IN_APP = true;
    private static final boolean ASK_AT_START = true;
    private static final boolean LIGHT_STATUS_ICONS = false;
    private static final boolean CAMERA_DECLARED = true;

    private static final String CAMERA_PERM = "android.permission.CAMERA";
    private static final int REQ_START = 100;
    private static final int REQ_WEB = 101;
    private static final int REQ_GEO = 102;
    private static final int REQ_FILE_CAM = 103;
    private static final int REQ_FILE = 104;

    private WebView web;
    private FrameLayout root;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private PermissionRequest pendingWebRequest;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCallback;

    private ValueCallback<Uri[]> fileCallback;
    private WebChromeClient.FileChooserParams fileParams;
    private final ArrayList<File> captureFiles = new ArrayList<>();
    private final ArrayList<Uri> captureUris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (KEEP_SCREEN_ON) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (FULLSCREEN && Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setupWebView();
        applySystemUi();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
        if (ASK_AT_START) {
            askStartupPermissions();
        }
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setGeolocationEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setSupportZoom(ALLOW_ZOOM);
        s.setBuiltInZoomControls(ALLOW_ZOOM);
        s.setDisplayZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new AppChrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                handleDownload(url, userAgent, contentDisposition, mimeType));
    }

    /* ---------- সিস্টেম UI ---------- */

    @SuppressWarnings("deprecation")
    private void applySystemUi() {
        View d = getWindow().getDecorView();
        int f = View.SYSTEM_UI_FLAG_VISIBLE;
        if (FULLSCREEN || customView != null) {
            f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        } else if (LIGHT_STATUS_ICONS && Build.VERSION.SDK_INT >= 23) {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        d.setSystemUiVisibility(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi();
    }

    /* ---------- লিংক ও নেভিগেশন ---------- */

    private class AppClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl().toString(), request.isForMainFrame());
        }
    }

    private boolean handleUrl(String url, boolean mainFrame) {
        if (!mainFrame) return false;
        if (url.startsWith("file:") || url.startsWith("about:") || url.startsWith("javascript:")
                || url.startsWith("blob:") || url.startsWith("data:")) {
            return false;
        }
        if ((url.startsWith("http://") || url.startsWith("https://")) && EXTERNAL_IN_APP) {
            return false;
        }
        return openExternal(url);
    }

    private boolean openExternal(String url) {
        try {
            Intent i;
            if (url.startsWith("intent:")) {
                i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            }
            startActivity(i);
        } catch (Exception e) {
            toast("Cannot open this link");
        }
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /* ---------- পারমিশন ---------- */

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void addPerm(List<String> list, String perm, int minSdk, int maxSdk) {
        int sdk = Build.VERSION.SDK_INT;
        if (sdk < minSdk || sdk > maxSdk) return;
        if (!has(perm) && !list.contains(perm)) list.add(perm);
    }

    private void askStartupPermissions() {
        List<String> list = new ArrayList<>();
        addPerm(list, "android.permission.CAMERA", 0, 99);
        addPerm(list, "android.permission.RECORD_AUDIO", 0, 99);
        addPerm(list, "android.permission.READ_MEDIA_IMAGES", 33, 99);
        addPerm(list, "android.permission.READ_MEDIA_VIDEO", 33, 99);
        addPerm(list, "android.permission.READ_MEDIA_AUDIO", 33, 99);
        addPerm(list, "android.permission.READ_EXTERNAL_STORAGE", 0, 32);
        addPerm(list, "android.permission.WRITE_EXTERNAL_STORAGE", 0, 28);
        if (!list.isEmpty()) {
            requestPermissions(list.toArray(new String[0]), REQ_START);
        }
    }

    private class AppChrome extends WebChromeClient {

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> handleWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingWebRequest == request) pendingWebRequest = null;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (has("android.permission.ACCESS_FINE_LOCATION") || has("android.permission.ACCESS_COARSE_LOCATION")) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeoOrigin = origin;
            pendingGeoCallback = callback;
            requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}, REQ_GEO);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         WebChromeClient.FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            fileParams = params;
            boolean wantsCamera = mentions(params.getAcceptTypes(), "image") || mentions(params.getAcceptTypes(), "video");
            if (CAMERA_DECLARED && wantsCamera && !has(CAMERA_PERM)) {
                requestPermissions(new String[]{CAMERA_PERM}, REQ_FILE_CAM);
            } else {
                launchFileChooser();
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            applySystemUi();
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        web.setVisibility(View.VISIBLE);
        applySystemUi();
    }

    private void handleWebPermission(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String res : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) && !has(CAMERA_PERM)) {
                need.add(CAMERA_PERM);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res) && !has("android.permission.RECORD_AUDIO")) {
                need.add("android.permission.RECORD_AUDIO");
            }
        }
        if (need.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingWebRequest != null) pendingWebRequest.deny();
        pendingWebRequest = request;
        requestPermissions(need.toArray(new String[0]), REQ_WEB);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean any = false;
        for (int r : grantResults) if (r == PackageManager.PERMISSION_GRANTED) any = true;

        if (requestCode == REQ_WEB && pendingWebRequest != null) {
            List<String> ok = new ArrayList<>();
            for (String res : pendingWebRequest.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                    if (has(CAMERA_PERM)) ok.add(res);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                    if (has("android.permission.RECORD_AUDIO")) ok.add(res);
                } else {
                    ok.add(res);
                }
            }
            if (ok.isEmpty()) pendingWebRequest.deny();
            else pendingWebRequest.grant(ok.toArray(new String[0]));
            pendingWebRequest = null;
        } else if (requestCode == REQ_GEO && pendingGeoCallback != null) {
            pendingGeoCallback.invoke(pendingGeoOrigin, any, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQ_FILE_CAM) {
            launchFileChooser();
        }
    }

    /* ---------- ফাইল বাছাই (গ্যালারি, ফাইল, ক্যামেরা) ---------- */

    private boolean mentions(String[] types, String kind) {
        if (types == null) return false;
        for (String t : types) {
            if (t == null) continue;
            t = t.trim().toLowerCase();
            if (t.startsWith(kind + "/")) return true;
            if (kind.equals("image") && t.matches("\\.(jpe?g|png|gif|webp|heic|bmp)")) return true;
            if (kind.equals("video") && t.matches("\\.(mp4|webm|mov|3gp|mkv)")) return true;
            if (kind.equals("audio") && t.matches("\\.(mp3|wav|m4a|ogg|aac)")) return true;
        }
        return false;
    }

    private void addCapture(List<Intent> list, String action, String ext) {
        try {
            File dir = getExternalCacheDir();
            if (dir == null) dir = getCacheDir();
            File f = File.createTempFile("capture_", ext, dir);
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(action);
            i.putExtra(MediaStore.EXTRA_OUTPUT, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            list.add(i);
            captureFiles.add(f);
            captureUris.add(u);
        } catch (Exception ignored) {
        }
    }

    private void launchFileChooser() {
        if (fileCallback == null) return;
        captureFiles.clear();
        captureUris.clear();
        String[] types = fileParams.getAcceptTypes();
        boolean camOk = !CAMERA_DECLARED || has(CAMERA_PERM);
        List<Intent> extras = new ArrayList<>();
        if (camOk && mentions(types, "image")) addCapture(extras, MediaStore.ACTION_IMAGE_CAPTURE, ".jpg");
        if (camOk && mentions(types, "video")) addCapture(extras, MediaStore.ACTION_VIDEO_CAPTURE, ".mp4");
        if (mentions(types, "audio")) extras.add(new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION));

        Intent chosen;
        if (fileParams.isCaptureEnabled() && !extras.isEmpty()) {
            chosen = extras.get(0);
        } else {
            Intent content;
            try {
                content = fileParams.createIntent();
            } catch (Exception e) {
                content = null;
            }
            if (content == null) {
                content = new Intent(Intent.ACTION_GET_CONTENT);
                content.addCategory(Intent.CATEGORY_OPENABLE);
                content.setType("*/*");
            }
            chosen = Intent.createChooser(content, "Choose file");
            if (!extras.isEmpty()) {
                chosen.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toArray(new Intent[0]));
            }
        }
        try {
            startActivityForResult(chosen, REQ_FILE);
        } catch (Exception e) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else {
                for (int i = 0; i < captureFiles.size(); i++) {
                    if (captureFiles.get(i).length() > 0) {
                        results = new Uri[]{captureUris.get(i)};
                        break;
                    }
                }
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    /* ---------- ডাউনলোড ---------- */

    private static String jsEsc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url.startsWith("blob:")) {
            String name = URLUtil.guessFileName("download", contentDisposition, mimeType);
            String js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + jsEsc(url) + "',true);"
                    + "x.responseType='blob';x.onload=function(){var r=new FileReader();"
                    + "r.onloadend=function(){AndroidApp.saveFile(r.result,'" + jsEsc(name) + "','" + jsEsc(mimeType) + "');};"
                    + "r.readAsDataURL(x.response);};x.send();})();";
            web.evaluateJavascript(js, null);
            return;
        }
        if (url.startsWith("data:")) {
            saveDataUrl(url, URLUtil.guessFileName("download", contentDisposition, mimeType), mimeType);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null) req.setMimeType(mimeType);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setTitle(name);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29 || has("android.permission.WRITE_EXTERNAL_STORAGE")) {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
            toast("Downloading...");
        } catch (Exception e) {
            openExternal(url);
        }
    }

    private void saveDataUrl(String dataUrl, String name, String mime) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(0, comma);
            String data = dataUrl.substring(comma + 1);
            byte[] bytes = meta.contains(";base64")
                    ? Base64.decode(data, Base64.DEFAULT)
                    : Uri.decode(data).getBytes(StandardCharsets.UTF_8);
            if (mime == null || mime.isEmpty()) {
                mime = meta.length() > 5 ? meta.substring(5).split(";")[0] : "";
                if (mime.isEmpty()) mime = "application/octet-stream";
            }
            if (name == null || name.isEmpty()) name = URLUtil.guessFileName("download", null, mime);
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String where = writeDownload(bytes, name, mime);
            toast("Saved: " + where);
        } catch (Exception e) {
            toast("Could not save file");
        }
    }

    private String writeDownload(byte[] bytes, String name, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("insert failed");
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                o.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
            return "Downloads/" + name;
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getFilesDir();
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(bytes);
        }
        return f.getAbsolutePath();
    }

    private class Bridge {
        @JavascriptInterface
        public void saveFile(String dataUrl, String name, String mime) {
            saveDataUrl(dataUrl, name, mime);
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    /* ---------- লাইফসাইকেল ---------- */

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
