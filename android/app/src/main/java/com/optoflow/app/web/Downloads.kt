package com.optoflow.app.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil

/** Saves files the web app downloads into the device's Downloads folder. */
class Downloads(private val context: Context) {

    /** Regular http(s) downloads, sent with the user's session cookies. Returns the file name. */
    fun enqueue(url: String, userAgent: String, contentDisposition: String?, mimeType: String?): String {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        return name
    }

    /** Bytes produced inside the page (blob:/data: URLs). Returns the saved file's URI. */
    fun save(name: String, mimeType: String, bytes: ByteArray): Uri {
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
}
