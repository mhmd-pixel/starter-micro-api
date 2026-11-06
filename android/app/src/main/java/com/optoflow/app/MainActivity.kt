package com.optoflow.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.webkit.WebViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.optoflow.app.web.Downloads
import com.optoflow.app.web.FileChooser
import com.optoflow.app.web.NativeBridge
import com.optoflow.app.web.UrlPolicy
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), NativeBridge.Host {

    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offlineView: View
    private lateinit var bridge: NativeBridge
    private lateinit var fileChooser: FileChooser
    private lateinit var downloads: Downloads
    private lateinit var appLock: AppLock
    private lateinit var shareInbox: ShareInbox

    private val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()

    private var firstPageShown = false
    private var mainFrameFailed = false
    private var webViewGone = false
    private var lastBackPress = 0L
    private var pendingWebPermission: PermissionRequest? = null

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (appLock.isLocked) {
                moveTaskToBack(true)
                return
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackPress < 2000) {
                finish()
            } else {
                lastBackPress = now
                Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val webPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingWebPermission?.let { answerWebPermission(it) }
        pendingWebPermission = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (offlineView.isVisible) reload() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val launchedAt = SystemClock.elapsedRealtime()
        splash.setKeepOnScreenCondition { !firstPageShown && SystemClock.elapsedRealtime() - launchedAt < 4000 }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        offlineView = findViewById(R.id.offline)
        findViewById<View>(R.id.retry).setOnClickListener { reload() }
        applyInsets(root)
        // Keep patient data out of the recent-apps thumbnail.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        appLock = AppLock(this, findViewById(R.id.lock))
        findViewById<View>(R.id.unlock).setOnClickListener { appLock.prompt() }
        shareInbox = ShareInbox(this)
        fileChooser = FileChooser(this)
        downloads = Downloads(this)
        configureWebView()
        bridge = NativeBridge(webView, this).also { it.install() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback,
        )

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored && !receiveShare(intent)) webView.loadUrl(deepLink(intent) ?: homeUrl())
        warnIfWebViewOutdated()
    }

    override fun onStart() {
        super.onStart()
        appLock.onStart()
    }

    override fun onStop() {
        appLock.onStop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!receiveShare(intent)) deepLink(intent)?.let { webView.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewGone) webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        if (!webViewGone) webView.destroy()
        super.onDestroy()
    }

    // --- Navigation --------------------------------------------------------------

    /** The clinic subdomain the user was last on, or the configured start page. */
    private fun homeUrl(): String = prefs.getString(KEY_LAST_ORIGIN, null)?.let { "$it/" } ?: BuildConfig.START_URL

    private fun deepLink(intent: Intent?): String? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        return uri.toString().takeIf { UrlPolicy.isInApp(uri) && !UrlPolicy.isDemoReset(uri) }
    }

    /** Copies shared files, then opens the web page that hands them to Hamyar. */
    private fun receiveShare(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return false
        io.execute {
            val error = runCatching { shareInbox.accept(intent) }.exceptionOrNull()
            runOnUiThread {
                when (error) {
                    null -> webView.loadUrl(homeUrl().trimEnd('/') + BuildConfig.SHARE_PATH)
                    is ShareInbox.TooLargeException -> snack(getString(R.string.share_too_large))
                    else -> snack(getString(R.string.share_failed))
                }
                if (error != null && webView.url == null) webView.loadUrl(homeUrl())
            }
        }
        return true
    }

    private fun reload() {
        offlineView.isVisible = false
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") webView.loadUrl(homeUrl()) else webView.reload()
    }

    private fun confirmDemoReset(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reset_title)
            .setMessage(R.string.reset_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_confirm) { _, _ -> webView.loadUrl(uri.toString()) }
            .show()
    }

    private fun openOutside(uri: Uri) {
        try {
            when (uri.scheme?.lowercase()) {
                "http", "https" -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
                "tel" -> startActivity(Intent(Intent.ACTION_DIAL, uri))
                "sms", "smsto", "mms", "mmsto" -> startActivity(smsIntent(uri))
                "mailto" -> startActivity(Intent(Intent.ACTION_SENDTO, uri))
                "intent" -> {
                    val target = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                    startActivity(target)
                }
                else -> startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            }
        } catch (e: ActivityNotFoundException) {
            snack(getString(R.string.no_app_for_link))
        }
    }

    /**
     * `sms:0912...?body=...` (also `&body=` and comma-separated recipients) → the
     * messaging app with the text filled in. `sms_body` is what Samsung Messages reads.
     */
    private fun smsIntent(uri: Uri): Intent {
        val ssp = uri.schemeSpecificPart.orEmpty()
        val split = ssp.indexOfFirst { it == '?' || it == '&' }
        val recipients = if (split >= 0) ssp.substring(0, split) else ssp
        val query = if (split >= 0) ssp.substring(split + 1) else ""
        val body = query.split('&').firstNotNullOfOrNull { part ->
            val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k.equals("body", ignoreCase = true)) runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v) else null
        }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(recipients, ",+"))).apply {
            if (body != null) {
                putExtra("sms_body", body)
                putExtra(Intent.EXTRA_TEXT, body)
            }
        }
    }

    // --- Look ----------------------------------------------------------------------

    /** Keep web content clear of the status bar, navigation bar, cutouts and keyboard. */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Colour the status and navigation bars to match the page: its
     * `<meta name="theme-color">`, else the page background.
     */
    private fun syncBarColor() {
        webView.evaluateJavascript(THEME_COLOR_JS) { raw ->
            val color = parseCssColor(raw?.trim('"').orEmpty()) ?: Color.WHITE
            root.setBackgroundColor(color)
            val light = ColorUtils.calculateLuminance(color) > 0.5
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    private fun warnIfWebViewOutdated() {
        val pkg = WebViewCompat.getCurrentWebViewPackage(this) ?: return
        val version = pkg.versionName ?: return
        val major = version.substringBefore('.').toIntOrNull() ?: return
        if (major >= BuildConfig.MIN_WEBVIEW_MAJOR || prefs.getString(KEY_WARNED_WEBVIEW, null) == version) return
        prefs.edit { putString(KEY_WARNED_WEBVIEW, version) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.webview_old_title)
            .setMessage(getString(R.string.webview_old_body, version))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // --- WebView ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            // The UI is designed for its own font sizes; system font scaling breaks layouts.
            textZoom = 100
            userAgentString = "$userAgentString OptoflowAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (UrlPolicy.isDemoReset(uri)) {
                    if (request.isForMainFrame) confirmDemoReset(uri)
                    return true
                }
                if (UrlPolicy.isInApp(uri)) return false
                openOutside(uri)
                return true
            }

            /**
             * Blocks background requests (Next.js prefetches and client-side
             * navigation) to the demo-reset page. Next then falls back to a full
             * page load, which goes through the confirmation above.
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && UrlPolicy.isDemoReset(request.url)) {
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mainFrameFailed = false
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (!mainFrameFailed) firstPageShown = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                bridge.injectShimIfNeeded(url)
                if (!mainFrameFailed) {
                    offlineView.isVisible = false
                    firstPageShown = true
                    syncBarColor()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                mainFrameFailed = true
                offlineView.isVisible = true
                firstPageShown = true
                root.setBackgroundColor(Color.WHITE)
            }

            /** The web renderer crashed or was killed for memory: rebuild instead of crashing the app. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                webViewGone = true
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                recreate()
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val uri = url?.let(Uri::parse) ?: return
                UrlPolicy.origin(uri)?.let { prefs.edit { putString(KEY_LAST_ORIGIN, it) } }
                // Client-side navigation: let the new screen render before sampling its colour.
                view.postDelayed({ if (!webViewGone) syncBarColor() }, 300)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.isVisible = newProgress < 100
                progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean = fileChooser.show(callback, params)

            /** Camera (in-page scanning/photos) and microphone (Hamyar voice input). */
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!UrlPolicy.isOptoflowHost(request.origin)) {
                    request.deny()
                    return
                }
                val missing = androidPermissionsFor(request).filter {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    answerWebPermission(request)
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    webPermissions.launch(missing.toTypedArray())
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission == request) pendingWebPermission = null
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            when {
                url.startsWith("blob:") || url.startsWith("data:") ->
                    webView.evaluateJavascript(NativeBridge.saveUrlScript(url, null), null)
                else -> try {
                    val name = downloads.enqueue(url, userAgent, contentDisposition, mimeType)
                    snack(getString(R.string.download_started, name))
                } catch (e: Exception) {
                    snack(getString(R.string.download_failed))
                }
            }
        }
    }

    private fun androidPermissionsFor(request: PermissionRequest): List<String> = request.resources.mapNotNull {
        when (it) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
            else -> null
        }
    }

    /** Grants whichever requested resources the user allowed; denies if none. */
    private fun answerWebPermission(request: PermissionRequest) {
        val granted = request.resources.filter {
            val perm = when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> return@filter false
            }
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun snack(text: String, action: Pair<String, () -> Unit>? = null) {
        Snackbar.make(webView, text, Snackbar.LENGTH_LONG).apply {
            action?.let { (label, block) -> setAction(label) { block() } }
        }.show()
    }

    // --- NativeBridge.Host -------------------------------------------------------------

    override fun share(title: String?, text: String?, url: String?) {
        val body = listOfNotNull(text, url).joinToString("\n").ifEmpty { webView.url.orEmpty() }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        startActivity(Intent.createChooser(send, title))
    }

    override fun openExternal(uri: Uri) = openOutside(uri)

    override fun print(jobName: String) {
        val adapter = webView.createPrintDocumentAdapter(jobName)
        getSystemService(PrintManager::class.java).print(jobName, adapter, PrintAttributes.Builder().build())
    }

    override fun saveFile(name: String, mimeType: String, bytes: ByteArray) {
        val uri = downloads.save(name, mimeType, bytes)
        snack(getString(R.string.download_saved, name), getString(R.string.open) to {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            } catch (e: ActivityNotFoundException) {
                snack(getString(R.string.no_app_for_link))
            }
        })
    }

    override fun hasSharedItems(): Boolean = shareInbox.hasItems()

    override fun takeSharedItems(): JSONObject = shareInbox.take()

    override fun appLockState(): JSONObject = JSONObject()
        .put("available", appLock.isAvailable)
        .put("enabled", appLock.enabled)
        .put("timeoutMinutes", appLock.timeoutMinutes)

    override fun setAppLock(enabled: Boolean?, timeoutMinutes: Int?) {
        enabled?.let { appLock.enabled = it }
        timeoutMinutes?.let { appLock.timeoutMinutes = it }
    }

    override fun lockNow() = appLock.lock()

    companion object {
        private const val KEY_LAST_ORIGIN = "last_origin"
        private const val KEY_WARNED_WEBVIEW = "warned_webview_version"

        private const val THEME_COLOR_JS = """(function () {
            var m = document.querySelector('meta[name="theme-color"]');
            if (m && m.content) return m.content;
            var c = getComputedStyle(document.body || document.documentElement).backgroundColor;
            if (!c || c === 'transparent' || c === 'rgba(0, 0, 0, 0)') c = getComputedStyle(document.documentElement).backgroundColor;
            return c;
        })()"""

        /** Parses `#rgb`, `#rrggbb`, `rgb(...)` and `rgba(...)`. Transparent → null. */
        fun parseCssColor(css: String): Int? {
            val s = css.trim().lowercase()
            if (s.startsWith("#")) {
                val hex = s.drop(1)
                val full = when (hex.length) {
                    3 -> hex.map { "$it$it" }.joinToString("")
                    6 -> hex
                    8 -> hex.take(6)
                    else -> return null
                }
                return full.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            }
            val m = Regex("""rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)""").find(s) ?: return null
            val (r, g, b) = m.destructured
            val alpha = m.groupValues[4]
            if (alpha.isNotEmpty() && (alpha == "0" || alpha == "0%" || alpha.toDoubleOrNull() == 0.0)) return null
            return Color.rgb(r.toDouble().toInt(), g.toDouble().toInt(), b.toDouble().toInt())
        }
    }
}
