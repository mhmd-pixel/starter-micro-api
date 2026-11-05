package com.optoflow.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.optoflow.app.web.Downloads
import com.optoflow.app.web.FileChooser
import com.optoflow.app.web.NativeBridge
import com.optoflow.app.web.UrlPolicy

class MainActivity : AppCompatActivity(), NativeBridge.Host {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offlineView: View
    private lateinit var bridge: NativeBridge
    private lateinit var fileChooser: FileChooser
    private lateinit var downloads: Downloads

    private var firstPageShown = false
    private var webViewGone = false
    private var mainFrameFailed = false
    private var pendingWebPermission: PermissionRequest? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = webView.goBack()
    }

    private val cameraForWeb = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { if (offlineView.isVisible) reload() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val launchedAt = System.currentTimeMillis()
        splash.setKeepOnScreenCondition { !firstPageShown && System.currentTimeMillis() - launchedAt < 4000 }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webview)
        progress = findViewById(R.id.progress)
        offlineView = findViewById(R.id.offline)
        findViewById<View>(R.id.retry).setOnClickListener { reload() }
        applyInsets(findViewById(R.id.root))

        fileChooser = FileChooser(this)
        downloads = Downloads(this)
        configureWebView()
        bridge = NativeBridge(webView, this).also { it.install() }
        onBackPressedDispatcher.addCallback(this, backCallback)

        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkCallback,
        )

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(deepLink(intent) ?: BuildConfig.START_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink(intent)?.let { webView.loadUrl(it) }
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

    private fun deepLink(intent: Intent?): String? {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
        return uri.toString().takeIf { UrlPolicy.isInApp(uri) }
    }

    /** Keep web content clear of the status bar, navigation bar, cutouts and keyboard. */
    private fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString OptoflowAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (UrlPolicy.isInApp(uri)) return false
                openOutside(uri)
                return true
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
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                mainFrameFailed = true
                offlineView.isVisible = true
                firstPageShown = true
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
                backCallback.isEnabled = view.canGoBack()
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

            override fun onPermissionRequest(request: PermissionRequest) {
                val trusted = UrlPolicy.isInApp(request.origin)
                val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                when {
                    !trusted || !wantsCamera -> request.deny()
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED -> request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    else -> {
                        pendingWebPermission?.deny()
                        pendingWebPermission = request
                        cameraForWeb.launch(Manifest.permission.CAMERA)
                    }
                }
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

    private fun reload() {
        offlineView.isVisible = false
        val current = webView.url
        if (current.isNullOrEmpty() || current == "about:blank") webView.loadUrl(BuildConfig.START_URL) else webView.reload()
    }

    private fun openOutside(uri: Uri) {
        try {
            when (uri.scheme) {
                "http", "https" -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
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

    private fun snack(text: String, action: Pair<String, () -> Unit>? = null) {
        Snackbar.make(webView, text, Snackbar.LENGTH_LONG).apply {
            action?.let { (label, block) -> setAction(label) { block() } }
        }.show()
    }

    // --- NativeBridge.Host ---------------------------------------------------

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
        snack(getString(R.string.download_saved, name), "Open" to {
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
}
