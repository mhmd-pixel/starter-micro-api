package com.optoflow.app.web

import android.net.Uri
import com.optoflow.app.BuildConfig

/** Decides which URLs stay inside the app and which go to the browser or other apps. */
object UrlPolicy {
    private val startHost: String = Uri.parse(BuildConfig.START_URL).host.orEmpty().lowercase()
    private val hosts: List<String> =
        (BuildConfig.ALLOWED_HOSTS.map { it.lowercase() } + startHost).filter { it.isNotEmpty() }.distinct()

    /** Patient portal links are for patients' own browsers, not the staff app. */
    private const val PORTAL_PREFIX = "/p/"

    /** Wipes every record in the signed-in clinic. Never opened without explicit confirmation. */
    private const val DEMO_RESET_PATH = "/reset-db-with-demo"

    fun isOptoflowHost(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    fun isInApp(uri: Uri): Boolean =
        isOptoflowHost(uri) && !(uri.path.orEmpty().startsWith(PORTAL_PREFIX))

    fun isDemoReset(uri: Uri): Boolean {
        val path = uri.path.orEmpty().trimEnd('/')
        return isOptoflowHost(uri) && (path == DEMO_RESET_PATH || path.startsWith("$DEMO_RESET_PATH/"))
    }

    /** scheme://host of an in-app URL, used to reopen the user's clinic subdomain. */
    fun origin(uri: Uri): String? = if (isOptoflowHost(uri)) "https://${uri.host}" else null

    /** Origin patterns for the JS bridge: only these pages can talk to native code. */
    fun bridgeOriginRules(): Set<String> =
        hosts.flatMap { listOf("https://$it", "https://*.$it") }.toSet()
}
