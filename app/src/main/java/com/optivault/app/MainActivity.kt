package com.optivault.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val siteUrl = "https://optivault.ai.studio/"
    private val siteHost = Uri.parse(siteUrl).host

    // File extensions that should be handed to DownloadManager instead of
    // being loaded inside the WebView (mod files, archives, etc.)
    private val downloadableExtensions = setOf(
        "jar", "zip", "rar", "7z", "mcpack", "mcworld", "mcaddon", "apk"
    )

    // Pending request info, used only while waiting on a runtime storage
    // permission result (API 28 and below — API 29+ never needs this).
    private var pendingIsBlob = false
    private var pendingUrl: String? = null
    private var pendingUserAgent: String? = null
    private var pendingContentDisposition: String? = null
    private var pendingMimeType: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
            val clipData = data.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        } else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingIsBlob) {
                pendingUrl?.let { handleBlobDownload(it, pendingContentDisposition, pendingMimeType) }
            } else {
                startDownload(pendingUrl, pendingUserAgent, pendingContentDisposition, pendingMimeType)
            }
        } else {
            Toast.makeText(
                this,
                "Storage permission is required to download files",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingIsBlob = false
        pendingUrl = null
        pendingUserAgent = null
        pendingContentDisposition = null
        pendingMimeType = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorLayout = findViewById(R.id.errorLayout)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        findViewById<Button>(R.id.retryButton).setOnClickListener { loadSite() }

        setupWebView()
        loadSite()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        // Bridge used to pull blob: data out of the page and save it natively.
        webView.addJavascriptInterface(BlobDownloadInterface(), "AndroidBlobDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url

                // Client-generated files (e.g. mods.zip built in JS) arrive as blob: URLs.
                if (url.scheme == "blob") {
                    handleBlobDownload(url.toString(), null, null)
                    return true
                }

                // Known download file types, even on the same host as the site.
                if (isDownloadableUrl(url)) {
                    startDownload(url.toString(), null, null, null)
                    return true
                }

                return if (url.host == siteHost) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }

        // Catches downloads triggered via JS/redirects that don't go through
        // shouldOverrideUrlLoading (including blob: URLs on some Android versions).
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                handleBlobDownload(url, contentDisposition, mimeType)
            } else {
                startDownload(url, userAgent, contentDisposition, mimeType)
            }
        }

        swipeRefresh.setOnRefreshListener { loadSite() }
    }

    private fun isDownloadableUrl(url: Uri): Boolean {
        val path = url.path ?: return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in downloadableExtensions
    }

    // --- Normal http(s) downloads (unchanged behavior) ---

    private fun startDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url == null) return

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingIsBlob = false
            pendingUrl = url
            pendingUserAgent = userAgent
            pendingContentDisposition = contentDisposition
            pendingMimeType = mimeType
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fileName.substringAfterLast('.', ""))
                ?: "application/octet-stream"

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrEmpty()) addRequestHeader("Cookie", cookie)
                if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
                setMimeType(resolvedMimeType)
                setTitle(fileName)
                setDescription("Downloading $fileName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- blob: downloads (the fix) ---

    private fun handleBlobDownload(url: String, contentDisposition: String?, mimeType: String?) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingIsBlob = true
            pendingUrl = url
            pendingContentDisposition = contentDisposition
            pendingMimeType = mimeType
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: "application/zip"
        val fileName = URLUtil.guessFileName(url, contentDisposition, resolvedMimeType)

        val escapedUrl = JSONObject.quote(url)
        val escapedFileName = JSONObject.quote(fileName)
        val escapedMimeType = JSONObject.quote(resolvedMimeType)

        // Reads the blob inside the page and hands the bytes to Kotlin as Base64,
        // since DownloadManager cannot fetch blob: URLs itself.
        val js = """
            (function() {
                try {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', $escapedUrl, true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidBlobDownloader.saveBase64File(base64, $escapedFileName, $escapedMimeType);
                        };
                        reader.onerror = function() {
                            AndroidBlobDownloader.onError('Could not read file data');
                        };
                        reader.readAsDataURL(xhr.response);
                    };
                    xhr.onerror = function() {
                        AndroidBlobDownloader.onError('Network error reading file');
                    };
                    xhr.send();
                } catch (e) {
                    AndroidBlobDownloader.onError(e.message);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Toast.makeText(this, "Preparing $fileName…", Toast.LENGTH_SHORT).show()
    }

    private fun saveBytesToDownloads(fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                true
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                FileOutputStream(File(downloadsDir, fileName)).use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private inner class BlobDownloadInterface {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String, mimeType: String) {
            val saved = try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                saveBytesToDownloads(fileName, mimeType, bytes)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (saved) "Saved $fileName to Downloads" else "Failed to save $fileName",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        @JavascriptInterface
        fun onError(message: String?) {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Download failed: ${message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // --- Unchanged from before ---

    private fun loadSite() {
        if (isNetworkAvailable()) {
            errorLayout.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(siteUrl)
        } else {
            swipeRefresh.isRefreshing = false
            showError()
        }
    }

    private fun showError() {
        errorLayout.visibility = View.VISIBLE
        webView.visibility = View.GONE
        progressBar.visibility = View.GONE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
