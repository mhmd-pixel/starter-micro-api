package com.optoflow.app

import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity

/**
 * Locks the app with the phone's fingerprint/face/PIN when it comes back after
 * [timeoutMinutes] in the background, and on a cold start. Clinic phones and
 * tablets are shared and show patient data. Does nothing if the phone has no
 * screen lock set up.
 */
class AppLock(private val activity: FragmentActivity, private val overlay: View) {
    private val prefs = activity.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private var backgroundedAt: Long? = null
    private var prompting = false
    var isLocked = false
        private set

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit { putBoolean("enabled", v) }

    var timeoutMinutes: Int
        get() = prefs.getInt("timeout_minutes", 5)
        set(v) = prefs.edit { putInt("timeout_minutes", v.coerceIn(0, 24 * 60)) }

    val isAvailable: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun onStart() {
        if (prompting) return
        val away = backgroundedAt?.let { SystemClock.elapsedRealtime() - it }
        val shouldLock = away == null || away >= timeoutMinutes * 60_000L
        if (shouldLock) lock()
    }

    fun onStop() {
        if (!prompting && !isLocked) backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun lock() {
        if (!enabled || !isAvailable) return
        isLocked = true
        overlay.isVisible = true
        prompt()
    }

    fun prompt() {
        if (!isLocked || prompting) return
        prompting = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setSubtitle(activity.getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                isLocked = false
                backgroundedAt = SystemClock.elapsedRealtime()
                overlay.isVisible = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or failed: stay locked; the overlay's button asks again.
                prompting = false
            }
        }).authenticate(info)
    }
}
