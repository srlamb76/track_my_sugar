package com.trackmysugar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Hosts the existing HTML/JS front end in a WebView. app.js calls
 * `TmsNative.request(...)` instead of fetch() when running here, and the
 * request is answered from the on-device SQLite database.
 *
 * Security notes: the page is loaded only from the app's own assets over the
 * virtual https://appassets.androidplatform.net origin; every other URL is
 * blocked (see [GuardedClient]), the app declares no INTERNET permission, and
 * index.html carries a Content-Security-Policy that forbids remote scripts and
 * frames. So nothing but this app's own page can reach the `TmsNative` bridge.
 */
class MainActivity : Activity() {

    internal lateinit var webView: WebView
    private lateinit var database: Database
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

    /** Lets only the app's own origin load; everything else is refused. */
    private inner class GuardedClient(private val assetLoader: WebViewAssetLoader) : WebViewClient() {
        private fun isOurs(request: WebResourceRequest) =
            request.url.scheme == "https" && request.url.host == ASSET_HOST

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (isOurs(request)) assetLoader.shouldInterceptRequest(request.url)
            else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        // true = handled (i.e. the navigation is cancelled).
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !isOurs(request)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = Database(this)
        api = Api(database.writableDatabase)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true  // the front end is JavaScript
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.setGeolocationEnabled(false)
            // Needed so confirm() in app.js shows a dialog.
            webChromeClient = WebChromeClient()
            webViewClient = GuardedClient(assetLoader)
            addJavascriptInterface(Bridge(), "TmsNative")
        }
        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl("https://$ASSET_HOST/assets/public/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /** Releases the WebView's native memory and the database as soon as the activity ends. */
    override fun onDestroy() {
        webView.removeJavascriptInterface("TmsNative")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        database.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * Writes an export into Downloads via MediaStore. Only the two export
     * types are accepted, the name is reduced to a safe file name with the
     * matching extension, and the size is capped.
     */
    private fun saveToDownloads(filename: String, mime: String, content: String): String {
        val extension = EXPORT_TYPES[mime] ?: error("Unsupported file type")
        if (content.length > MAX_EXPORT_CHARS) error("Export is too large")
        // Last path segment only, safe characters only, correct extension.
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "export" }
        val safeName = if (base.endsWith(".$extension")) base else "$base.$extension"

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

    private companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val MAX_EXPORT_CHARS = 10_000_000
        val EXPORT_TYPES = mapOf("text/csv" to "csv", "application/json" to "json")
    }
}
