package com.optoflow.app.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/**
 * Saves files the web app downloads. Android 10+ uses the shared Downloads
 * folder; Android 8–9 uses the app's own Downloads folder (no storage permission needed).
 */
class Downloads(private val context: Context) {

    /** Regular http(s) downloads, sent with the user's session cookies. Returns the file name. */
    fun enqueue(url: String, userAgent: String, contentDisposition: String?, mimeType: String?): String {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        }
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        return name
    }

    /** Bytes produced inside the page (blob:/data: URLs). Returns a URI that can be opened. */
    fun save(name: String, mimeType: String, bytes: ByteArray): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(name, mimeType, bytes) else saveToAppDir(name, bytes)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(name: String, mimeType: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create file")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Couldn't open file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun saveToAppDir(name: String, bytes: ByteArray): Uri {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("No storage")
        dir.mkdirs()
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        var file = File(dir, safe)
        var n = 1
        while (file.exists()) {
            file = File(dir, "${safe.substringBeforeLast('.')} ($n)${safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }}")
            n++
        }
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
