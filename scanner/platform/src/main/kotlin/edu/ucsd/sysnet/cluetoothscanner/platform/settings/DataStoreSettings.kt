package edu.ucsd.sysnet.cluetoothscanner.platform.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.api.platform.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, V3_PREFS, setOf(USER_ID.name, WIFI_ONLY.name, CODED_PHY.name))),
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(FILE) },
    ),
) : SettingsStore {
    override val settings: StateFlow<Settings> =
        store.data.map(::toSettings).stateIn(scope, SharingStarted.Eagerly, Settings.defaults(appVersion))

    /** The stored settings, after the first read (and the one-time migration). */
    suspend fun loaded(): Settings = toSettings(store.data.first())

    /** A user id that breaks the O-2 rule is refused ([IllegalArgumentException]); nothing is written then. */
    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val next = transform(toSettings(prefs))
            next.userId?.let { require(Settings.isValidUserId(it)) { "User ID must be lowercase letters and digits, 1-9" } }
            if (next.userId == null) prefs.remove(USER_ID) else prefs[USER_ID] = next.userId!!
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
