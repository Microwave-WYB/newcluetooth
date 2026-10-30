package edu.ucsd.sysnet.cluetoothscanner.v2

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import edu.ucsd.sysnet.cluetoothscanner.sheets.DiscoverView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * D-1b: what the app remembers of how the user left its UI. It is not a setting (never on the Settings page, nothing in
 * `:platform`'s settings): only :app reads and writes it. A failed read reaches the reducer as `MainEvent.Failed`
 * (Categories stays, no notice); a failed write is only logged, a deliberate exception for pure UI state.
 */
interface UiStateStore {
    /** The remembered Discover view; Categories until the user first switches (first launch). */
    suspend fun discoverView(): UiStateResult<DiscoverView>

    suspend fun saveDiscoverView(view: DiscoverView): UiStateResult<Unit>
}

/** A UI-state read or write: its value, or why it failed (it never throws). */
sealed interface UiStateResult<out T> {
    data class Ok<T>(val value: T) : UiStateResult<T>
    data class Err(val message: String) : UiStateResult<Nothing>
}

/** [UiStateStore] in its own DataStore file, [FILE], apart from the settings' `cluetooth_settings`. */
class DataStoreUiState(private val store: DataStore<Preferences>) : UiStateStore {
    override suspend fun discoverView(): UiStateResult<DiscoverView> = attempt {
        val saved = store.data.first()[DISCOVER_VIEW]
        DiscoverView.entries.firstOrNull { it.name == saved } ?: DiscoverView.Categories
    }

    override suspend fun saveDiscoverView(view: DiscoverView): UiStateResult<Unit> = attempt {
        store.edit { it[DISCOVER_VIEW] = view.name }
        Unit
    }

    companion object {
        const val FILE = "cluetooth_ui_state"
        private val DISCOVER_VIEW = stringPreferencesKey("discover_view")

        fun of(context: Context, scope: CoroutineScope) =
            DataStoreUiState(PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile(FILE) })
    }
}

/** The store's own boundary: an exception (I/O, a corrupt file) becomes [UiStateResult.Err]. */
private suspend fun <T> attempt(block: suspend () -> T): UiStateResult<T> = try {
    UiStateResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    UiStateResult.Err(e.message ?: e::class.simpleName.orEmpty())
}
