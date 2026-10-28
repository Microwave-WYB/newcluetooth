package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Free-text notes on recordings, kept on the phone only (the local stores have no field for them). */
class RecordingNotes(context: Context) {
    private val prefs = context.getSharedPreferences("v3-recording-notes", Context.MODE_PRIVATE)
    private val _notes = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())
    val notes: StateFlow<Map<String, String>> = _notes.asStateFlow()

    fun set(recordingId: String, note: String) {
        val trimmed = note.trim()
        prefs.edit().apply { if (trimmed.isEmpty()) remove(recordingId) else putString(recordingId, trimmed) }.apply()
        _notes.value = if (trimmed.isEmpty()) _notes.value - recordingId else _notes.value + (recordingId to trimmed)
    }
}
