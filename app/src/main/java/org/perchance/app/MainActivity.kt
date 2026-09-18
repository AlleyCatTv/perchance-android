package org.perchance.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOME_URL = "https://perchance.org/"

/**
 * Let the WebView algorithmically darken web pages that declare no colour
 * scheme of their own. Perchance's own light/dark styling is unaffected.
 * Flip to false if you prefer pages to always keep the site author's colours.
 */
private const val ALLOW_ALGORITHMIC_DARKENING = true

private const val TAG = "PerchanceApp"

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: View
    private lateinit var contentRoot: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webViewHolder: FrameLayout
    private lateinit var errorView: View
    private lateinit var errorDetail: TextView
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var webView: WebView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var currentUrl: String? = null
    private var exitArmed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val cameraUri = cameraOutputUri
        cameraOutputUri = null
        val clip = result.data?.clipData
        val singleUri = result.data?.data
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                singleUri != null -> arrayOf(singleUri)
                cameraUri != null -> arrayOf(cameraUri)
                else -> null
            }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        contentRoot = findViewById(R.id.contentRoot)
        toolbar = findViewById(R.id.toolbar)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webViewHolder = findViewById(R.id.webViewHolder)
        errorView = findViewById(R.id.errorView)
        errorDetail = findViewById(R.id.errorDetail)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        applyWindowInsets()
        applyBarAppearance()

        toolbar.setNavigationOnClickListener { webView.loadUrl(HOME_URL) }
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item -> handleMenuItem(item.itemId) }

        findViewById<MaterialButton>(R.id.retryBtn).setOnClickListener {
            hideError()
            webView.loadUrl(currentUrl ?: HOME_URL)
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> webView.canScrollVertically(-1) }

        newWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            webView.loadUrl(intentUrl() ?: HOME_URL)
        }
    }

    // ------------------------------------------------------------------ WebView

    private fun newWebView() {
        webView = WebView(this)
        webViewHolder.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        configureWebView()
    }

    /** Rebuilds the WebView from scratch - used when the renderer process dies. */
    private fun rebuildWebView(url: String?) {
        val old = webView
        webViewHolder.removeView(old)
        try {
            old.stopLoading()
            old.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to destroy the dead WebView", e)
        }
        newWebView()
        webView.loadUrl(url ?: HOME_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            // With multiple windows disabled, window.open()/target=_blank links
            // are followed inside this WebView instead of spawning a new one.
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                webView.settings,
                ALLOW_ALGORITHMIC_DARKENING
            )
        }

        // Perchance generators run inside an iframe served from the generator's
        // own subdomain, so "third party" cookies/storage must be allowed or
        // saved generators, accounts and galleries stop working.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
            onDownloadRequested(url, contentDisposition, mimeType)
        })

        webView.webViewClient = PerchanceWebViewClient()
        webView.webChromeClient = PerchanceChromeClient()
        webView.addJavascriptInterface(WebBridge(), "WebBridge")
    }

    // ------------------------------------------------------------- window chrome

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyBarAppearance() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
    }

    private fun handleMenuItem(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_refresh -> {
                if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
                webView.reload()
                true
            }
            R.id.action_home -> {
                webView.loadUrl(HOME_URL)
                true
            }
            R.id.action_share -> {
                shareCurrentPage()
                true
            }
            R.id.action_open_in_browser -> {
                openCurrentPageExternally()
                true
            }
            R.id.action_clear_data -> {
                confirmClearData()
                true
            }
            else -> false
        }
    }

    private fun shareCurrentPage() {
        val url = currentUrl ?: HOME_URL
        val title = webView.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        startActivity(Intent.createChooser(share, getString(R.string.share)))
    }

    private fun openCurrentPageExternally() {
        openExternally(Uri.parse(currentUrl ?: HOME_URL))
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_for_link)
        }
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_data)
            .setMessage(R.string.clear_data_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_data_confirm) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
                toast(R.string.clear_data_done)
                webView.loadUrl(HOME_URL)
            }
            .show()
    }

    private fun handleBack() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        if (currentUrl != null && !isHomeUrl(currentUrl!!)) {
            webView.loadUrl(HOME_URL)
            return
        }
        if (exitArmed) {
            finish()
            return
        }
        exitArmed = true
        Snackbar.make(rootLayout, R.string.press_back_again, Snackbar.LENGTH_SHORT).show()
        mainHandler.postDelayed({ exitArmed = false }, 2000L)
    }

    // ------------------------------------------------------------------ errors

    private fun showError(message: String?) {
        hideProgress()
        swipeRefresh.isRefreshing = false
        errorDetail.text = message ?: getString(R.string.error_detail)
        if (errorView.visibility != View.VISIBLE) {
            errorView.visibility = View.VISIBLE
        }
        errorView.alpha = 0f
        errorView.animate().alpha(1f).setDuration(150L).start()
    }

    private fun hideError() {
        errorView.visibility = View.GONE
    }

    private fun showProgress() {
        progressBar.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
    }

    // ------------------------------------------------------------- fullscreen

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.visibility = View.VISIBLE
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ------------------------------------------------------------- downloads

    private fun onDownloadRequested(url: String, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            saveInPageGeneratedFile(url)
            return
        }
        if (!URLUtil.isNetworkUrl(url)) {
            openExternally(Uri.parse(url))
            return
        }
        val name = sanitizeFileName(
            URLUtil.guessFileName(url, contentDisposition, mimeType),
            mimeType
        )
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", webView.settings.userAgentString)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    setDestinationInExternalFilesDir(
                        this@MainActivity,
                        Environment.DIRECTORY_DOWNLOADS,
                        name
                    )
                }
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast(R.string.download_started)
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            toast(R.string.download_failed)
        }
    }

    /**
     * Generators that build output in the browser (text exports, canvas images,
     * audio recordings...) hand the download to us as a blob:/data: URL, which
     * DownloadManager cannot fetch. Read it in the page and pass the bytes over
     * the JS bridge instead.
     */
    private fun saveInPageGeneratedFile(url: String) {
        val js = """
            (function () {
              var u = ${JSONObject.quote(url)};
              function post(base64, mime, name) {
                try { window.WebBridge.saveBase64(name || 'download', mime || 'application/octet-stream', base64); } catch (e) {}
              }
              try {
                if (u.indexOf('data:') === 0) {
                  var comma = u.indexOf(',');
                  var meta = u.substring(5, comma);
                  var payload = u.substring(comma + 1);
                  var mime = meta.split(';')[0] || 'application/octet-stream';
                  var b64 = meta.indexOf(';base64') !== -1
                    ? payload
                    : btoa(unescape(encodeURIComponent(decodeURIComponent(payload))));
                  post(b64, mime, window.__perchanceDownloadName);
                  return;
                }
                fetch(u).then(function (r) {
                  var mime = r.headers.get('content-type') || 'application/octet-stream';
                  return r.blob().then(function (b) {
                    var reader = new FileReader();
                    reader.onload = function () {
                      var s = String(reader.result);
                      post(s.substring(s.indexOf(',') + 1), mime, window.__perchanceDownloadName);
                    };
                    reader.readAsDataURL(b);
                  });
                }).catch(function () {});
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun interface SaveResultCallback {
        fun onSaved(uri: Uri?, displayName: String)
    }

    private fun saveBytes(
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        callback: SaveResultCallback
    ) {
        var viewableUri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    viewableUri = uri
                }
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                if (!dir.exists()) dir.mkdirs()
                val file = uniqueFile(dir, displayName)
                file.outputStream().use { it.write(bytes) }
                viewableUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not save $displayName", e)
        }
        mainHandler.post { callback.onSaved(viewableUri, displayName) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (file.exists() && i < 1000) {
            file = File(dir, "$stem ($i)$ext")
            i++
        }
        return file
    }

    private fun sanitizeFileName(name: String?, mimeType: String?): String {
        var clean = (name ?: "").trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim('.')
        if (clean.isEmpty() || clean.equals("download", ignoreCase = true)) {
            clean = "perchance_download"
        }
        if (!clean.contains('.')) {
            val ext = mimeType
                ?.substringBefore(';')
                ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (!ext.isNullOrEmpty()) clean = "$clean.$ext"
        }
        if (clean.length > 100) {
            val dot = clean.lastIndexOf('.')
            clean = if (dot > 0 && clean.length - dot <= 10) {
                clean.substring(0, 90) + clean.substring(dot)
            } else {
                clean.substring(0, 100)
            }
        }
        return clean
    }

    // ------------------------------------------------------------- utilities

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private val shownWebPermissionWarning = arrayOf(false)

    private fun onWebPermissionRequested(request: PermissionRequest) {
        // The app deliberately never grants camera/microphone access to page
        // content; deny the request and let the user know why.
        request.deny()
        if (!shownWebPermissionWarning[0]) {
            shownWebPermissionWarning[0] = true
            toast(R.string.web_permission_denied)
        }
    }

    private fun onShowFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        cameraOutputUri = null

        val accepted = params.acceptTypes.filter { it.isNotBlank() }
        val allowsMultiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accepted.isEmpty()) "*/*" else accepted.first().substringBefore(';')
            if (accepted.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowsMultiple)
        }

        val chooser = Intent.createChooser(contentIntent, getString(R.string.choose_file))
        if (!allowsMultiple && acceptsImages(accepted)) {
            createCameraIntent()?.let {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it))
            }
        }

        return try {
            fileChooserLauncher.launch(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            filePathCallback = null
            cameraOutputUri = null
            false
        }
    }

    private fun acceptsImages(accepted: List<String>): Boolean {
        if (accepted.isEmpty()) return true
        return accepted.any { it.startsWith("image/") || it == "*/*" }
    }

    private fun createCameraIntent(): Intent? {
        return try {
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "photo_$stamp.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraOutputUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No camera intent available", e)
            null
        }
    }

    private fun intentUrl(): String? {
        val data = intent?.data ?: return null
        val scheme = data.scheme?.lowercase(Locale.ROOT) ?: return null
        return if (scheme == "http" || scheme == "https") {
            if (isPerchanceUrl(data.toString())) data.toString() else null
        } else {
            null
        }
    }

    private fun isHomeUrl(url: String): Boolean {
        return url.trimEnd('/') == HOME_URL.trimEnd('/')
    }

    private fun isPerchanceUrl(url: String): Boolean {
        val host = try {
            Uri.parse(url).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        } ?: return false
        return host == "perchance.org" ||
            host == "www.perchance.org" ||
            host.endsWith(".perchance.org")
    }

    // ------------------------------------------------------------- lifecycle

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrl()?.let { webView.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            webView.saveState(outState)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not save WebView state", e)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBarAppearance()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------- inner classes

    private inner class PerchanceWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            return when (scheme) {
                "http", "https", "about", "blob", "data", "javascript", "file", "content" -> false
                "intent" -> {
                    try {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        toast(R.string.no_app_for_link)
                        true
                    }
                }
                else -> {
                    openExternally(uri)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            currentUrl = url
            showProgress()
            swipeRefresh.isRefreshing = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            hideProgress()
            swipeRefresh.isRefreshing = false
            toolbar.title = view.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
            view.evaluateJavascript(HOOK_DOWNLOAD_NAMES_JS, null)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            toolbar.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                error.description?.toString()
            } else {
                null
            }
            showError(description)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail
        ): Boolean {
            val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) detail.didCrash() else false
            Log.e(TAG, "WebView renderer gone (crashed=$crashed)")
            val url = currentUrl
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.renderer_gone_title)
                .setMessage(R.string.renderer_gone_message)
                .setCancelable(false)
                .setPositiveButton(R.string.reload) { _, _ -> rebuildWebView(url) }
                .setNegativeButton(R.string.close_app) { _, _ -> finish() }
                .show()
            return true
        }
    }

    private inner class PerchanceChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress in 1..99) {
                progressBar.visibility = View.VISIBLE
                progressBar.setProgressCompat(newProgress, true)
            } else {
                hideProgress()
            }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            toolbar.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            return this@MainActivity.onShowFileChooser(filePathCallback, fileChooserParams)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            this@MainActivity.onWebPermissionRequested(request)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            exitFullscreen()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            return false
        }
    }

    private inner class WebBridge {

        @JavascriptInterface
        fun saveBase64(name: String?, mimeType: String?, base64: String) {
            val pageUrl = currentUrl
            if (pageUrl == null || !isPerchanceUrl(pageUrl)) {
                Log.w(TAG, "Refused a file save from an untrusted page")
                return
            }
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                mainHandler.post { toast(R.string.download_failed) }
                return
            }
            val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val displayName = sanitizeFileName(name, mime)
            saveBytes(displayName, mime, bytes) { uri, savedName ->
                if (uri == null) {
                    toast(R.string.download_failed)
                } else {
                    val snackbar = Snackbar.make(
                        rootLayout,
                        getString(R.string.download_saved, savedName),
                        Snackbar.LENGTH_LONG
                    )
                    snackbar.setAction(R.string.open) {
                        val open = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(open)
                        } catch (e: ActivityNotFoundException) {
                            toast(R.string.no_app_for_link)
                        }
                    }
                    snackbar.show()
                }
            }
        }
    }

    private companion object {
        /**
         * `download` attributes on anchors are the only hint we get about the
         * intended filename for blob:/data: downloads, so remember the most
         * recent one that was clicked.
         */
        val HOOK_DOWNLOAD_NAMES_JS = """
            (function () {
              if (window.__perchanceDownloadHook) return;
              window.__perchanceDownloadHook = true;
              document.addEventListener('click', function (e) {
                var el = e.target;
                while (el && el.getAttribute) {
                  if (el.getAttribute('download')) {
                    window.__perchanceDownloadName = el.getAttribute('download');
                    return;
                  }
                  el = el.parentElement;
                }
              }, true);
            })();
        """.trimIndent()
    }
}
