package edu.ucsd.sysnet.cluetoothscanner.platform.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.api.platform.SettingsStore
import edu.ucsd.sysnet.cluetoothscanner.platform.failureHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * §4.5: [SettingsStore] in DataStore `cluetooth_settings`. The first read migrates the v3 `cluetooth_prefs` keys
 * `user_id`, `wifi_only_upload` and `scan_coded_phy` once (and removes them there); every other field starts at
 * [Settings.defaults]. `onboardingDone` is not migrated, so a v3 upgrader sees the v2 onboarding once (O-2).
 *
 * [settings] starts at the defaults until the file is read; code that decides on a setting at launch (V-6 "Record
 * when the app opens") awaits [loaded] first.
 */
class DataStoreSettings(
    context: Context,
    scope: CoroutineScope,
    private val appVersion: String,
) : SettingsStore {
    // Private: no DataStore type is part of the public API, so callers need no DataStore dependency.
    private val store: DataStore<Preferences> = open(context, scope)

    /** A file that cannot be read (corrupt, I/O) reads as the defaults instead of failing the flow. */
    private val data = store.data.catch { failure ->
        runCatching { Log.w(TAG, "settings unreadable; using the defaults", failure) }
        emit(emptyPreferences())
    }

    override val settings: StateFlow<Settings> =
        data.map(::toSettings).stateIn(scope + failureHandler(TAG), SharingStarted.Eagerly, Settings.defaults(appVersion))

    /** The stored settings, after the first read (and the one-time migration). */
    suspend fun loaded(): Settings = toSettings(data.first())

    /** A user id that breaks the O-2 rule is refused ([IllegalArgumentException]); nothing is written then. */
    override suspend fun update(transform: (Settings) -> Settings) {
        try {
            write(transform)
        } catch (refused: IllegalArgumentException) {
            throw refused
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A failed write (I/O) leaves the stored settings, and so the shown ones, unchanged.
            runCatching { Log.w(TAG, "settings not written", failure) }
        }
    }

    private suspend fun write(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val next = transform(toSettings(prefs))
            val userId = next.userId
            // The API's documented refusal: the caller (the O-2 form) shows the rule; nothing is written.
            if (userId != null && !Settings.isValidUserId(userId)) {
                throw IllegalArgumentException("User ID must be lowercase letters and digits, 1-9")
            }
            if (userId == null) prefs.remove(USER_ID) else prefs[USER_ID] = userId
            prefs[RECORD_ON_OPEN] = next.recordOnOpen
            prefs[WIFI_ONLY] = next.wifiOnlyUpload
            prefs[CODED_PHY] = next.scanCodedPhy
            prefs[HIDE_UNNAMED_APPLE] = next.hideUnnamedApple
            prefs[ONBOARDING_DONE] = next.onboardingDone
        }
    }

    private fun toSettings(prefs: Preferences): Settings {
        val defaults = Settings.defaults(appVersion)
        return Settings(
            userId = prefs[USER_ID]?.takeIf(Settings::isValidUserId),
            recordOnOpen = prefs[RECORD_ON_OPEN] ?: defaults.recordOnOpen,
            wifiOnlyUpload = prefs[WIFI_ONLY] ?: defaults.wifiOnlyUpload,
            scanCodedPhy = prefs[CODED_PHY] ?: defaults.scanCodedPhy,
            hideUnnamedApple = prefs[HIDE_UNNAMED_APPLE] ?: defaults.hideUnnamedApple,
            appVersion = appVersion,
            onboardingDone = prefs[ONBOARDING_DONE] ?: defaults.onboardingDone,
        )
    }

    companion object {
        private const val TAG = "DataStoreSettings"

        private fun open(context: Context, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            migrations = listOf(SharedPreferencesMigration(context, V3_PREFS, setOf(USER_ID.name, WIFI_ONLY.name, CODED_PHY.name))),
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(FILE) },
        )

        const val FILE = "cluetooth_settings"
        const val V3_PREFS = "cluetooth_prefs"

        // The v3 key names, so SharedPreferencesMigration copies them as they are.
        private val USER_ID = stringPreferencesKey("user_id")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only_upload")
        private val CODED_PHY = booleanPreferencesKey("scan_coded_phy")
        private val RECORD_ON_OPEN = booleanPreferencesKey("record_on_open")
        private val HIDE_UNNAMED_APPLE = booleanPreferencesKey("hide_unnamed_apple")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }
}
