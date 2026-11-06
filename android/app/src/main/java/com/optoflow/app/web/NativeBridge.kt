package com.optoflow.app.web

import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.optoflow.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-way channel between the Optoflow web app and native code.
 *
 * The web app sees `window.OptoflowNative` (only on allowed origins). Every
 * method returns a Promise. In a normal browser the object doesn't exist, so
 * web code should feature-detect: `if (window.OptoflowNative) { ... }`.
 */
class NativeBridge(
    private val webView: WebView,
    private val host: Host,
) {
    interface Host {
        fun share(title: String?, text: String?, url: String?)
        fun openExternal(uri: Uri)
        fun print(jobName: String)
        fun saveFile(name: String, mimeType: String, bytes: ByteArray)
        fun hasSharedItems(): Boolean
        /** Called off the main thread. */
        fun takeSharedItems(): JSONObject
        fun appLockState(): JSONObject
        fun setAppLock(enabled: Boolean?, timeoutMinutes: Int?)
        fun lockNow()
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val originRules = UrlPolicy.bridgeOriginRules()

    /** Whether document-start injection is available; otherwise the shim is injected on page finish. */
    var injectsAtDocumentStart = false
        private set

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(webView, CHANNEL, originRules) { _, message, sourceOrigin, isMainFrame, reply ->
            if (isMainFrame && UrlPolicy.isInApp(sourceOrigin)) handle(message, reply)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, SHIM, originRules)
            injectsAtDocumentStart = true
        }
    }

    /** Fallback for older WebView versions; the shim is idempotent. */
    fun injectShimIfNeeded(url: String?) {
        if (injectsAtDocumentStart || url == null || !UrlPolicy.isInApp(Uri.parse(url))) return
        webView.evaluateJavascript(SHIM, null)
    }

    private fun handle(message: WebMessageCompat, reply: JavaScriptReplyProxy) {
        val req = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return
        val id = req.opt("id")
        val params = req.optJSONObject("params") ?: JSONObject()
        val response = JSONObject().put("id", id)
        if (req.optString("method") == "takeSharedItems") {
            // Encoding large files can take a moment; keep the UI responsive.
            worker.execute {
                val r = runCatching { host.takeSharedItems() }
                webView.post {
                    r.fold(
                        { response.put("ok", true).put("result", it) },
                        { response.put("ok", false).put("error", it.message ?: "Failed") },
                    )
                    reply.postMessage(response.toString())
                }
            }
            return
        }
        try {
            val result: Any? = when (val method = req.optString("method")) {
                "getAppInfo" -> appInfo()
                "share" -> {
                    host.share(params.optStringOrNull("title"), params.optStringOrNull("text"), params.optStringOrNull("url"))
                    null
                }
                "openExternal" -> {
                    val uri = Uri.parse(params.getString("url"))
                    require(uri.scheme == "https" || uri.scheme == "http" || uri.scheme == "mailto" || uri.scheme == "tel") {
                        "Unsupported URL scheme"
                    }
                    host.openExternal(uri)
                    null
                }
                "print" -> {
                    host.print(params.optStringOrNull("jobName") ?: webView.title ?: "Optoflow")
                    null
                }
                "saveFile" -> {
                    val bytes = Base64.decode(params.getString("base64"), Base64.DEFAULT)
                    host.saveFile(
                        params.optStringOrNull("name") ?: "download",
                        params.optStringOrNull("mimeType") ?: "application/octet-stream",
                        bytes,
                    )
                    null
                }
                "hasSharedItems" -> host.hasSharedItems()
                "getAppLock" -> host.appLockState()
                "setAppLock" -> {
                    host.setAppLock(
                        if (params.has("enabled")) params.getBoolean("enabled") else null,
                        if (params.has("timeoutMinutes")) params.getInt("timeoutMinutes") else null,
                    )
                    host.appLockState()
                }
                "lockNow" -> {
                    host.lockNow()
                    null
                }
                else -> throw IllegalArgumentException("Unknown method: $method")
            }
            response.put("ok", true).put("result", result ?: JSONObject.NULL)
        } catch (e: Exception) {
            response.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        reply.postMessage(response.toString())
    }

    private fun appInfo() = JSONObject()
        .put("platform", "android")
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("appBuild", BuildConfig.VERSION_CODE)
        .put("osVersion", Build.VERSION.RELEASE)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("features", JSONArray(FEATURES))

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    companion object {
        private const val CHANNEL = "OptoflowNativeChannel"
        private val FEATURES = listOf("share", "openExternal", "print", "saveFile", "sharedItems", "appLock")

        /** Evaluated in the page to save a blob:/data: URL through the bridge. */
        fun saveUrlScript(url: String, fileName: String?): String =
            "window.__optoflowSaveUrl && window.__optoflowSaveUrl(${JSONObject.quote(url)}, ${
                if (fileName == null) "null" else JSONObject.quote(fileName)
            });"

        private val SHIM = """
            (function () {
              if (window.OptoflowNative || !window.$CHANNEL) return;
              var ch = window.$CHANNEL, seq = 0, pending = {};
              ch.onmessage = function (e) {
                var m; try { m = JSON.parse(e.data); } catch (_) { return; }
                var p = pending[m.id]; if (!p) return; delete pending[m.id];
                if (m.ok) p.resolve(m.result); else p.reject(new Error(m.error));
              };
              function call(method, params) {
                return new Promise(function (resolve, reject) {
                  var id = ++seq; pending[id] = { resolve: resolve, reject: reject };
                  ch.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
                });
              }
              function saveUrl(url, name) {
                return fetch(url).then(function (r) { return r.blob(); }).then(function (blob) {
                  return new Promise(function (resolve, reject) {
                    var fr = new FileReader();
                    fr.onload = function () {
                      var s = String(fr.result), i = s.indexOf(',');
                      resolve(call('saveFile', { name: name || 'download', mimeType: blob.type || 'application/octet-stream', base64: s.slice(i + 1) }));
                    };
                    fr.onerror = function () { reject(fr.error); };
                    fr.readAsDataURL(blob);
                  });
                });
              }
              var api = Object.freeze({
                isNative: true,
                platform: 'android',
                call: call,
                getAppInfo: function () { return call('getAppInfo'); },
                share: function (data) { return call('share', data); },
                openExternal: function (url) { return call('openExternal', { url: url }); },
                print: function (jobName) { return call('print', { jobName: jobName }); },
                saveFile: function (opts) { return call('saveFile', opts); },
                hasSharedItems: function () { return call('hasSharedItems'); },
                takeSharedItems: function () { return call('takeSharedItems'); },
                getAppLock: function () { return call('getAppLock'); },
                setAppLock: function (opts) { return call('setAppLock', opts); },
                lockNow: function () { return call('lockNow'); }
              });
              Object.defineProperty(window, 'OptoflowNative', { value: api });
              Object.defineProperty(window, '__optoflowSaveUrl', { value: saveUrl });
              // WebView ignores window.print(); route it to Android printing.
              window.print = function () { api.print(document.title); };
              // WebView can't download blob:/data: links itself; save them via the bridge.
              function interceptAnchor(a) {
                if (!a || !a.hasAttribute || !a.hasAttribute('download')) return false;
                var href = a.href || '';
                if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return false;
                saveUrl(href, a.getAttribute('download') || null).catch(function (e) { console.error('Optoflow save failed', e); });
                return true;
              }
              document.addEventListener('click', function (e) {
                var a = e.target && e.target.closest ? e.target.closest('a') : null;
                if (interceptAnchor(a)) e.preventDefault();
              }, true);
              var nativeClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (!this.isConnected && interceptAnchor(this)) return;
                return nativeClick.apply(this, arguments);
              };
              window.dispatchEvent(new Event('optoflownativeready'));
            })();
        """.trimIndent()
    }
}
