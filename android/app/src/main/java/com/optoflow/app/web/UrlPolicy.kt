package com.optoflow.app.web

import android.net.Uri
import com.optoflow.app.BuildConfig

/** Decides which URLs stay inside the app and which go to the browser or other apps. */
object UrlPolicy {
    private val startHost: String = Uri.parse(BuildConfig.START_URL).host.orEmpty().lowercase()
    private val hosts: List<String> =
        (BuildConfig.ALLOWED_HOSTS.map { it.lowercase() } + startHost).filter { it.isNotEmpty() }.distinct()

    fun isInApp(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    /** Origin patterns for the JS bridge: only these pages can talk to native code. */
    fun bridgeOriginRules(): Set<String> =
        hosts.flatMap { listOf("https://$it", "https://*.$it") }.toSet()
}
