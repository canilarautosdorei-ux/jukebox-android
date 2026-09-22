package site.r777b.jukebox;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "JukeboxWebView";
    private static final String START_URL = "https://teste.r777b.site/jukebox";
    private static final String START_HOST = "teste.r777b.site";
    private static final long REFRESH_AFTER_MS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_DELAY_MS = 4000L;
    private static final long PAGE_LOAD_TIMEOUT_MS = 40000L;
    private static final long HEALTH_INTERVAL_MS = 20000L;
    private static final long HEALTH_REPLY_TIMEOUT_MS = 8000L;
    private static final int MAX_HEALTH_MISSES = 2;
    private static final int MAX_BLANK_HITS = 2;
    private static final String PREFS = "jukebox_app";
    private static final String KEY_LAST_ACTIVE = "last_active_ms";
    private static final String KEY_LAST_URL = "last_good_url";
    private static final String KEY_FORCE_RELOAD = "force_reload_after_recovery";
    private static final String KEY_LAST_RECOVERY_REASON = "last_recovery_reason";
    private static final String KEY_LAST_RECOVERY_MS = "last_recovery_ms";
    private static final String DIAGNOSTIC_FILE = "webview-recovery.log";

    private static final String ERROR_HTML =
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>html,body{margin:0;width:100%;height:100%;background:#070b12;color:#fff;font-family:Arial,sans-serif}" +
            "body{display:flex;align-items:center;justify-content:center;text-align:center}" +
            ".box{max-width:520px;padding:32px}.icon{font-size:54px;margin-bottom:16px}" +
            "h1{font-size:26px;margin:0 0 12px}p{color:#b9c3d0;font-size:17px;line-height:1.5;margin:0 0 22px}" +
            "a{display:inline-block;color:#fff;text-decoration:none;background:#1c73e8;border-radius:12px;padding:14px 24px;font-weight:700}" +
            ".small{display:block;margin-top:18px;color:#7f8a99;font-size:13px}</style></head>" +
            "<body><div class='box'><div class='icon'>♪</div><h1>Conexão indisponível</h1>" +
            "<p>A Jukebox está tentando reconectar automaticamente.</p>" +
            "<a href='jukebox://retry'>TENTAR AGORA</a><span class='small'>Aguarde alguns segundos.</span>" +
            "</div></body></html>";

    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Handler healthHandler = new Handler(Looper.getMainLooper());
    private boolean showingErrorPage = false;
    private boolean appInForeground = false;
    private boolean recoveringWebView = false;
    private String lastGoodUrl = START_URL;
    private long pageLoadGeneration = 0L;
    private long heartbeatGeneration = 0L;
    private long heartbeatReplyGeneration = 0L;
    private int heartbeatMisses = 0;
    private int blankPageHits = 0;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || recoveringWebView) return;
            if (isNetworkAvailable()) {
                retrySite();
            } else {
                retryHandler.postDelayed(this, RETRY_DELAY_MS);
            }
        }
    };

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!appInForeground || isFinishing() || recoveringWebView || showingErrorPage || webView == null) {
                scheduleNextHealthCheck();
                return;
            }

            final long generation = ++heartbeatGeneration;
            final WebView target = webView;
            try {
                target.evaluateJavascript(
                        "(function(){try{if(document.readyState!=='complete')return 'loading';if(!document.body)return 'blank';return document.body.children.length>0?'ok':'blank';}catch(e){return 'error';}})()",
                        value -> {
                            if (recoveringWebView || target != webView || generation != heartbeatGeneration) return;
                            heartbeatReplyGeneration = generation;
                            heartbeatMisses = 0;

                            if ("\"blank\"".equals(value)) {
                                blankPageHits++;
                                if (blankPageHits >= MAX_BLANK_HITS) {
                                    triggerWebViewRecovery("blank_page_watchdog", false);
                                }
                            } else if (!"\"loading\"".equals(value)) {
                                blankPageHits = 0;
                            }
                        }
                );
            } catch (Throwable t) {
                recordDiagnostic("heartbeat_exception: " + t.getClass().getSimpleName());
            }

            healthHandler.postDelayed(() -> {
                if (!appInForeground || recoveringWebView || target != webView || generation != heartbeatGeneration) return;
                if (heartbeatReplyGeneration < generation) {
                    heartbeatMisses++;
                    recordDiagnostic("heartbeat_miss_" + heartbeatMisses);
                    if (heartbeatMisses >= MAX_HEALTH_MISSES) {
                        triggerWebViewRecovery("renderer_unresponsive_watchdog", false);
                    }
                }
            }, HEALTH_REPLY_TIMEOUT_MS);

            scheduleNextHealthCheck();
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_LAST_URL, START_URL);
        if (isJukeboxUrl(savedUrl)) lastGoodUrl = savedUrl;

        startPlaybackKeepAliveService();
        webView = findViewById(R.id.webView);
        configureWebView(webView);

        // Sempre inicia com cache limpo para buscar a versão mais recente do site.
        // Cookies, localStorage e demais dados de sessão são preservados.
        clearWebViewCacheBeforeInitialLoad();

        if (prefs.getBoolean(KEY_FORCE_RELOAD, false)) {
            prefs.edit().remove(KEY_FORCE_RELOAD).apply();
        }

        webView.loadUrl(lastGoodUrl);
    }

    private void clearWebViewCacheBeforeInitialLoad() {
        if (webView == null) return;
        try {
            webView.stopLoading();
            webView.clearCache(true);
            webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            recordDiagnostic("startup_cache_cleared");
        } catch (Throwable t) {
            recordDiagnostic("startup_cache_clear_failed: " + t.getClass().getSimpleName());
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView view) {
        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(view, true);

        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setOffscreenPreRaster(false);
        s.setUserAgentString(s.getUserAgentString() + " JukeboxAndroid/1.5");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            view.setWebViewClient(new RendererAwareClient());
        } else {
            view.setWebViewClient(new BaseClient());
        }

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View custom, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = custom;
                customViewCallback = callback;
                FrameLayout root = findViewById(R.id.root);
                root.addView(custom, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));
                if (webView != null) webView.setVisibility(View.GONE);
                enterImmersiveMode();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private class BaseClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            enterImmersiveMode();
            armPageLoadTimeout(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pageLoadGeneration++;
            if (isJukeboxUrl(url)) {
                lastGoodUrl = url;
                prefs.edit().putString(KEY_LAST_URL, url).apply();
                showingErrorPage = false;
                retryHandler.removeCallbacks(retryRunnable);
                view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                showConnectionError();
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            String currentUrl = view != null ? view.getUrl() : null;
            if (failingUrl == null || currentUrl == null || failingUrl.equals(currentUrl)) {
                showConnectionError();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request != null && request.isForMainFrame() && response != null) {
                int status = response.getStatusCode();
                if (status >= 500 || status == 408) {
                    showConnectionError();
                }
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showConnectionError();
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private class RendererAwareClient extends BaseClient {
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = detail != null && detail.didCrash();
            String reason = crashed ? "renderer_process_crashed" : "renderer_process_killed";
            triggerWebViewRecovery(reason, true);
            return true;
        }
    }

    private void armPageLoadTimeout(WebView target, String url) {
        final long generation = ++pageLoadGeneration;
        healthHandler.postDelayed(() -> {
            if (!appInForeground || recoveringWebView || showingErrorPage || target != webView) return;
            if (generation == pageLoadGeneration) {
                recordDiagnostic("page_load_timeout: " + safeUrl(url));
                retrySite();
            }
        }, PAGE_LOAD_TIMEOUT_MS);
    }

    private void scheduleNextHealthCheck() {
        healthHandler.removeCallbacks(healthCheckRunnable);
        if (appInForeground && !recoveringWebView) {
            healthHandler.postDelayed(healthCheckRunnable, HEALTH_INTERVAL_MS);
        }
    }

    private void triggerWebViewRecovery(String reason, boolean rendererGone) {
        if (recoveringWebView || isFinishing()) return;
        recoveringWebView = true;
        recordDiagnostic("recover: " + reason);
        prefs.edit()
                .putBoolean(KEY_FORCE_RELOAD, true)
                .putString(KEY_LAST_RECOVERY_REASON, reason)
                .putLong(KEY_LAST_RECOVERY_MS, System.currentTimeMillis())
                .putString(KEY_LAST_URL, lastGoodUrl)
                .apply();

        retryHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);

        if (customView != null) {
            try {
                FrameLayout root = findViewById(R.id.root);
                root.removeView(customView);
            } catch (Throwable ignored) {
            }
            customView = null;
            customViewCallback = null;
        }

        WebView failed = webView;
        webView = null;
        if (failed != null) {
            try {
                ViewGroup parent = (ViewGroup) failed.getParent();
                if (parent != null) parent.removeView(failed);
            } catch (Throwable ignored) {
            }
            if (!rendererGone) {
                try {
                    failed.stopLoading();
                } catch (Throwable ignored) {
                }
            }
            try {
                failed.destroy();
            } catch (Throwable ignored) {
            }
        }

        retryHandler.postDelayed(() -> {
            if (!isFinishing()) {
                try {
                    recreate();
                } catch (Throwable t) {
                    recordDiagnostic("activity_recreate_failed: " + t.getClass().getSimpleName());
                    Intent restart = new Intent(MainActivity.this, MainActivity.class);
                    restart.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(restart);
                    finish();
                }
            }
        }, 350L);
    }

    private void recordDiagnostic(String message) {
        Log.w(TAG, message);
        try {
            File file = new File(getFilesDir(), DIAGNOSTIC_FILE);
            if (file.exists() && file.length() > 128L * 1024L) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(stamp + " | " + message + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    private String safeUrl(String url) {
        if (url == null) return "null";
        return url.length() > 180 ? url.substring(0, 180) : url;
    }

    private void startPlaybackKeepAliveService() {
        Intent serviceIntent = new Intent(this, JukeboxPlaybackService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return false;

        if ("jukebox".equalsIgnoreCase(uri.getScheme()) && "retry".equalsIgnoreCase(uri.getHost())) {
            retrySite();
            return true;
        }

        String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase(START_HOST) || host.endsWith("." + START_HOST))) {
            return false;
        }

        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
            }
            return true;
        }

        return false;
    }

    private boolean isJukeboxUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            return START_HOST.equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showConnectionError() {
        if (webView == null || isFinishing() || recoveringWebView) return;

        retryHandler.removeCallbacks(retryRunnable);
        showingErrorPage = true;
        pageLoadGeneration++;
        try {
            webView.stopLoading();
            webView.loadDataWithBaseURL(
                    "about:blank",
                    ERROR_HTML,
                    "text/html",
                    "UTF-8",
                    null
            );
        } catch (Throwable t) {
            triggerWebViewRecovery("connection_error_webview_failure", false);
            return;
        }
        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void retrySite() {
        if (webView == null || isFinishing() || recoveringWebView) return;

        retryHandler.removeCallbacks(retryRunnable);
        showingErrorPage = false;
        try {
            webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            webView.loadUrl(lastGoodUrl);
            webView.postDelayed(() -> {
                if (webView != null && !recoveringWebView) {
                    webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
                }
            }, 5000L);
        } catch (Throwable t) {
            triggerWebViewRecovery("retry_failed: " + t.getClass().getSimpleName(), false);
        }
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                return caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                );
            }

            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception ignored) {
            return true;
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        FrameLayout root = findViewById(R.id.root);
        root.removeView(customView);
        customView = null;
        if (webView != null) webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInForeground = true;
        enterImmersiveMode();

        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_ACTIVE, 0L);

        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();

            if (showingErrorPage) {
                retryHandler.postDelayed(retryRunnable, 500L);
            } else if (last > 0L && now - last >= REFRESH_AFTER_MS) {
                retrySite();
            }
        }

        prefs.edit().putLong(KEY_LAST_ACTIVE, now).apply();
        scheduleNextHealthCheck();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPictureInPictureIfPossible();
    }

    private void enterPictureInPictureIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (isFinishing() || showingErrorPage || recoveringWebView || isInPictureInPictureMode()) return;

        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        appInForeground = false;
        retryHandler.removeCallbacks(retryRunnable);
        healthHandler.removeCallbacks(healthCheckRunnable);
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        appInForeground = false;
        retryHandler.removeCallbacksAndMessages(null);
        healthHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null && !showingErrorPage && !recoveringWebView) {
            try {
                webView.saveState(outState);
            } catch (Throwable ignored) {
            }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (webView != null && webView.canGoBack() && !showingErrorPage) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
