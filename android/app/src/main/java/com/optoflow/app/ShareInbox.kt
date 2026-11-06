package com.optoflow.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.IntentCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds files and text shared into the app ("Share → Optoflow") until the web
 * app collects them with `OptoflowNative.takeSharedItems()`. Files are copied
 * into the app's cache straight away because the sender's permission to read
 * them doesn't last.
 */
class ShareInbox(private val context: Context) {
    private val dir = File(context.cacheDir, "shared")
    private var text: String? = null
    private var subject: String? = null
    private val files = mutableListOf<Pair<File, String>>() // file, mime type

    class TooLargeException : Exception("Shared files are too large")

    @Synchronized
    fun hasItems() = text != null || files.isNotEmpty()

    /** Copies the shared content. Call off the main thread. Returns false if the intent isn't a share. */
    @Synchronized
    fun accept(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return false
        clear()
        dir.mkdirs()
        text = intent.getStringExtra(Intent.EXTRA_TEXT)
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        var total = 0L
        uris.forEachIndexed { i, uri ->
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: intent.type ?: "application/octet-stream"
            val name = displayName(uri) ?: "shared_$i"
            val out = File(dir, "${i}_${name.replace(Regex("[/\\\\]"), "_")}")
            resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            total += out.length()
            if (total > MAX_TOTAL_BYTES) {
                clear()
                throw TooLargeException()
            }
            files += out to mime
        }
        return true
    }

    /** Returns everything shared and empties the inbox. Call off the main thread. */
    @Synchronized
    fun take(): JSONObject {
        val result = JSONObject()
            .put("text", text ?: JSONObject.NULL)
            .put("subject", subject ?: JSONObject.NULL)
            .put("files", JSONArray(files.map { (file, mime) ->
                JSONObject()
                    .put("name", file.name.substringAfter('_'))
                    .put("mimeType", mime)
                    .put("size", file.length())
                    .put("base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }))
        clear()
        return result
    }

    @Synchronized
    private fun clear() {
        text = null
        subject = null
        files.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val MAX_TOTAL_BYTES = 30L * 1024 * 1024
    }
}
