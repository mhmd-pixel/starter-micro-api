package com.optoflow.app.web

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.optoflow.app.R
import java.io.File

/** Handles `<input type="file">`, including taking a photo with the camera. */
class FileChooser(private val activity: ComponentActivity) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var params: FileChooserParams? = null
    private var cameraUri: Uri? = null

    private val pickLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) collectUris(result.data) else null
        callback?.onReceiveValue(uris)
        callback = null
    }

    private val cameraPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launch(withCamera = granted)
    }

    fun show(cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
        callback?.onReceiveValue(null)
        callback = cb
        params = p
        val wantsImages = p.acceptTypes.all { it.isBlank() } || p.acceptTypes.any { it.startsWith("image/") || it == "*/*" }
        when {
            !wantsImages -> launch(withCamera = false)
            hasCameraPermission() -> launch(withCamera = true)
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
        return true
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun launch(withCamera: Boolean) {
        val p = params ?: return
        val camera = if (withCamera) cameraIntent() else null
        val intent = if (camera != null && p.isCaptureEnabled) {
            camera
        } else {
            val pick = p.createIntent().apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                if (p.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            Intent.createChooser(pick, activity.getString(R.string.choose_file)).apply {
                if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            }
        }
        try {
            pickLauncher.launch(intent)
        } catch (e: Exception) {
            callback?.onReceiveValue(null)
            callback = null
        }
    }

    private fun cameraIntent(): Intent? {
        val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        cameraUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun collectUris(data: Intent?): Array<Uri>? {
        data?.clipData?.let { clip ->
            return Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        data?.data?.let { return arrayOf(it) }
        val photo = cameraUri ?: return null
        val size = runCatching {
            activity.contentResolver.openAssetFileDescriptor(photo, "r")?.use { it.length }
        }.getOrNull() ?: 0L
        return if (size > 0) arrayOf(photo) else null
    }
}
