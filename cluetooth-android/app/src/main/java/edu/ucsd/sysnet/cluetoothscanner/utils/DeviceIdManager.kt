package edu.ucsd.sysnet.cluetoothscanner.utils

import android.content.Context
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.tasks.await

object DeviceIdManager {
    private const val PREFS_NAME = "cluetooth_prefs"
    private const val KEY_USER_ID = "user_id"

    private val USER_ID_REGEX = Regex("^[a-z0-9]{1,9}$")

    fun isValidUserId(id: String): Boolean = USER_ID_REGEX.matches(id)

    fun getUserId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_ID, null)
    }

    fun setUserId(context: Context, userId: String) {
        require(isValidUserId(userId)) { "User ID must be lowercase alphanumeric, 1-9 chars" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    suspend fun getDeviceId(context: Context): String {
        val userId = getUserId(context) ?: "anonymous"
        val installationId = FirebaseInstallations.getInstance().id.await()
        return "$userId$installationId"
    }
}
