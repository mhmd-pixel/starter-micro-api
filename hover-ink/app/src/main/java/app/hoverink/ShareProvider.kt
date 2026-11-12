package app.hoverink

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Serves the one image being shared from the app's cache. */
class ShareProvider : ContentProvider() {
    private fun fileFor(uri: Uri): File {
        val dir = File(context!!.cacheDir, DIR)
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = File(dir, name)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val DIR = "share"
        const val AUTHORITY = "app.hoverink.share"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
