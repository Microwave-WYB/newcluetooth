package edu.ucsd.sysnet.cluetoothscanner.utils

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "cluetooth_prefs"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at_ms"
    private const val KEY_WIFI_ONLY_UPLOAD = "wifi_only_upload"
    private const val KEY_CODED_PHY = "scan_coded_phy"
    private const val KEY_AUTO_RECORD = "auto_record"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_HIDE_UNNAMED_APPLE = "hide_unnamed_apple"

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

    /** Scan the LE Coded PHY where the controller supports it (default on). */
    fun isCodedPhyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CODED_PHY, true)
    }

    /** Takes effect at the next scan (re)start, which happens at least every minute. */
    fun setCodedPhyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CODED_PHY, enabled)
            .apply()
    }

    /** Start a recording when the app opens (off by default); nothing records in the background or at boot. */
    fun isAutoRecord(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_RECORD, false)

    fun setAutoRecord(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()
    }

    /** Display filter (on by default): screens leave out unnamed Apple devices; nothing else reads it. */
    fun isHideUnnamedApple(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_HIDE_UNNAMED_APPLE, true)

    fun setHideUnnamedApple(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_UNNAMED_APPLE, enabled).apply()
    }

    /** First-launch onboarding finished (permissions, Bluetooth, user ID, auto-record). */
    fun isOnboarded(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONBOARDED, true).apply()
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
