package edu.ucsd.sysnet.cluetoothscanner.utils

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "cluetooth_prefs"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at_ms"
    private const val KEY_WIFI_ONLY_UPLOAD = "wifi_only_upload"

    fun isWifiOnlyUpload(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY_UPLOAD, false)
    }

    fun setWifiOnlyUpload(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY_UPLOAD, enabled)
            .apply()
    }

    fun lastUploadAtMs(context: Context): Long? {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_UPLOAD_AT, 0L)
        return value.takeIf { it > 0L }
    }

    fun setLastUploadAtMs(context: Context, timeMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_UPLOAD_AT, timeMs)
            .apply()
    }
}
