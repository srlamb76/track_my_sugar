package com.trackmysugar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

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

        /**
         * Saves an exported file to the public Downloads folder. WebView
         * can't download blob: URLs, so app.js hands the file over here.
         * Returns {"ok":true,"name":...} or {"ok":false,"error":...}; the
         * name can differ from the request when the file already exists.
         */
        @JavascriptInterface
        fun saveFile(filename: String, mime: String, content: String): String =
            try {
                JSONObject().put("ok", true).put("name", saveToDownloads(filename, mime, content))
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Could not save the file")
            }.toString()
    }

    private fun saveToDownloads(filename: String, mime: String, content: String): String {
        // Keep only the last path segment so a name can't escape Downloads.
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "export" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)  // hidden until fully written
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)  // don't leave a half-written file behind
            throw e
        }
        // MediaStore renames on a clash ("name (1).csv"), so report the stored name.
        return resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: safeName
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
