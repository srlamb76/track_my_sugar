package com.trackmysugar

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * Hosts the existing HTML/JS front end in a WebView. app.js calls
 * `TmsNative.request(...)` instead of fetch() when running here, and the
 * request is answered from the on-device SQLite database.
 */
class MainActivity : Activity() {

    internal lateinit var webView: WebView
    private lateinit var api: Api

    /** Exposed to JavaScript as `TmsNative`. */
    private inner class Bridge {
        @JavascriptInterface
        fun request(method: String, path: String, body: String?): String =
            api.handle(method, path, body).toJson()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = Api(Database(this).writableDatabase)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Needed so confirm() in app.js shows a dialog.
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView, request: android.webkit.WebResourceRequest
                ) = assetLoader.shouldInterceptRequest(request.url)
            }
            addJavascriptInterface(Bridge(), "TmsNative")
        }
        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl("https://appassets.androidplatform.net/assets/public/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
