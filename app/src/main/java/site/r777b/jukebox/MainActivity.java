package site.r777b.jukebox;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String START_URL = "https://teste.r777b.site/jukebox";
    private static final String START_HOST = "teste.r777b.site";
    private static final long REFRESH_AFTER_MS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_DELAY_MS = 4000L;
    private static final String PREFS = "jukebox_app";
    private static final String KEY_LAST_ACTIVE = "last_active_ms";

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
    private boolean showingErrorPage = false;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (isNetworkAvailable()) {
                retrySite();
            } else {
                retryHandler.postDelayed(this, RETRY_DELAY_MS);
            }
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
        startPlaybackKeepAliveService();
        webView = findViewById(R.id.webView);

        WebView.setWebContentsDebuggingEnabled(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings s = webView.getSettings();
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
        s.setUserAgentString(s.getUserAgentString() + " JukeboxAndroid/1.1");

        webView.setWebViewClient(new WebViewClient() {
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
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (isJukeboxUrl(url)) {
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
                showConnectionError();
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
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                FrameLayout root = findViewById(R.id.root);
                root.addView(view, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));
                webView.setVisibility(View.GONE);
                enterImmersiveMode();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
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
        if (webView == null || isFinishing()) return;

        retryHandler.removeCallbacks(retryRunnable);
        showingErrorPage = true;
        webView.stopLoading();
        webView.loadDataWithBaseURL(
                "about:blank",
                ERROR_HTML,
                "text/html",
                "UTF-8",
                null
        );
        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void retrySite() {
        if (webView == null || isFinishing()) return;

        retryHandler.removeCallbacks(retryRunnable);
        showingErrorPage = false;
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.loadUrl(START_URL);
        webView.postDelayed(() -> {
            if (webView != null) webView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        }, 5000L);
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
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        enterImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
    }

    @Override
    protected void onPause() {
        retryHandler.removeCallbacks(retryRunnable);
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply();
        // Mantém o WebView ativo no segundo plano para preservar a reprodução.
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        retryHandler.removeCallbacks(retryRunnable);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null && !showingErrorPage) webView.saveState(outState);
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
