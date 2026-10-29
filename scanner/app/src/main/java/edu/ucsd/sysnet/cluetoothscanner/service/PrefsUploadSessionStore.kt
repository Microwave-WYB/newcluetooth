package edu.ucsd.sysnet.cluetoothscanner.service

import android.content.Context

internal class PrefsUploadSessionStore(
    context: Context,
    name: String = "cluetooth_upload_sessions",
) : UploadSessionStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(payloadId: String): UploadSession? =
        prefs.getString(payloadId, null)?.split('\n', limit = 2)?.takeIf { it.size == 2 }
            ?.let { UploadSession(it[0], it[1]) }

    override fun put(payloadId: String, session: UploadSession) {
        prefs.edit().putString(payloadId, "${session.uri}\n${session.cachePath}").apply()
    }

    override fun remove(payloadId: String) {
        prefs.edit().remove(payloadId).apply()
    }

    override fun payloadIds(): Set<String> = prefs.all.keys.toSet()
}
