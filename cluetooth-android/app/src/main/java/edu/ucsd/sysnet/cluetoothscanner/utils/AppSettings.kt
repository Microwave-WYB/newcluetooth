package edu.ucsd.sysnet.cluetoothscanner.utils

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "cluetooth_prefs"
    private const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"

    fun isAutoUploadEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPLOAD_ENABLED, true)
    }

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPLOAD_ENABLED, enabled)
            .apply()
    }
}
