package com.optivault.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
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

    // Held while we wait on a runtime storage-permission result (API <= 28 only)
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadUserAgent: String? = null
    private var pendingDownloadContentDisposition: String? = null
    private var pendingDownloadMimeType: String? = null

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
            startDownload(
                pendingDownloadUrl,
                pendingDownloadUserAgent,
                pendingDownloadContentDisposition,
                pendingDownloadMimeType
            )
        } else {
            Toast.makeText(
                this,
                "Storage permission is required to download files",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingDownloadUrl = null
        pendingDownloadUserAgent = null
        pendingDownloadContentDisposition = null
        pendingDownloadMimeType = null
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url

                // Route known download file types straight to DownloadManager,
                // even when they're on the same host as the site.
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

        // Catches downloads triggered via JS, redirects, blob URLs, or any
        // response the WebView itself can't render (Content-Disposition:
        // attachment, unknown mimetype, etc.)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        swipeRefresh.setOnRefreshListener { loadSite() }
    }

    private fun isDownloadableUrl(url: Uri): Boolean {
        val path = url.path ?: return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in downloadableExtensions
    }

    private fun startDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url == null) return

        // Android 9 (API 28) and below require the runtime permission to
        // write into the public Downloads folder. API 29+ does not.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            pendingDownloadUserAgent = userAgent
            pendingDownloadContentDisposition = contentDisposition
            pendingDownloadMimeType = mimeType
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
