package edu.ucsd.sysnet.cluetoothscanner.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * What the UI reads. Every query is a cold [Flow]: it emits the current result
 * on collection and again after the underlying data changed (writes are batched
 * about once a second), so collect it from a lifecycle-aware scope. Queries run
 * off the main thread; collecting on the main thread is fine.
 *
 * Queries are shared between collectors of the same key and only re-run when a
 * table they read changed, at most once a second (heavy ones once every 5 s).
 *
 * Nothing here uploads anything. Sighting history and routes are only written
 * while a recording (scan session) is active; the seen-payload store is also
 * fed by plain scanning.
 *
 * The implementation is [LocalDataHub]; previews and tests can use any fake of this interface.
 */
interface LocalData {
    /** The wiki bundle (pages, categories, Chinese labels); null until the asset is loaded. */
    val catalog: StateFlow<WikiCatalog?>

    /**
     * Whether the layer works. When it does not, every query below emits empty data
     * (and never throws), and this says why; show it, don't crash on it.
     */
    val status: StateFlow<LocalDataStatus> get() = READY_STATUS_FLOW

    // Nearby --------------------------------------------------------------------------

    /** Products among the devices heard in the last [windowMs]; refreshed about once a second. */
    fun productsInRange(windowMs: Long = DEFAULT_IN_RANGE_WINDOW_MS): Flow<InRangeSnapshot>

    /** Every product ever seen, grouped by category (pages in several categories appear in each). */
    fun previouslySeenProducts(): Flow<List<CategoryProducts>>

    /** Products ever seen in one category (a [WikiCategory.id]), most recently seen first. */
    fun previouslySeenProducts(categoryId: String): Flow<List<SeenProduct>>

    /**
     * Unidentified devices heard in the last [windowMs], grouped by coarse structure
     * ([StructureKey]); strongest current RSSI first. Refreshed about once a second.
     */
    fun unknownStructuresInRange(windowMs: Long = DEFAULT_IN_RANGE_WINDOW_MS): Flow<List<UnknownInRange>> = flowOf(emptyList())

    /**
     * Unidentified advertisements stored on the phone (heard in the last 30 days), grouped by coarse
     * structure, most recently heard first. A group disappears when a bundle update identifies all of
     * its advertisements; unlike [previouslySeenProducts] it does not outlive the payload store.
     */
    fun previouslySeenUnknownStructures(): Flow<List<SeenUnknown>> = flowOf(emptyList())

    // Recordings ----------------------------------------------------------------------

    /** Newest first. Includes the active recording (its numbers grow as it runs). */
    fun recordings(): Flow<List<Recording>>

    fun recording(recordingId: String): Flow<Recording?>

    /** Devices heard during a recording, strongest first. */
    fun devicesInRecording(recordingId: String): Flow<List<RecordedDevice>>

    /** The recording's route, oldest first. */
    fun route(recordingId: String): Flow<List<RoutePoint>>

    /** The user's note on a recording ("" when none); emits again after [setRecordingNote]. */
    fun observeRecordingNote(recordingId: String): Flow<String> = flowOf("")

    /** The note as it is now ("" when none, or when the layer is unavailable). */
    suspend fun getRecordingNote(recordingId: String): String = ""

    /**
     * Saves the note (blank deletes it); returns once it is on disk. Notes are kept as long as the
     * recording. Throws [IllegalStateException] when the database is unavailable, so a note is never
     * lost silently.
     */
    suspend fun setRecordingNote(recordingId: String, note: String) = Unit

    // History for maps ------------------------------------------------------------------

    /**
     * Every minute row of one recording, newest first, at most [limit] (after [bounds] is applied).
     * With [bounds] only rows whose best sample was located inside the rectangle are returned; without,
     * rows without a fix are included ([Sighting.location] null). For the map's density layer and the
     * "only this area" filter. Heavy: shared per (recording, bounds, limit) and re-run at most every 5 s.
     */
    fun sightingsInRecording(
        recordingId: String,
        bounds: GeoBounds? = null,
        limit: Int = DEFAULT_RECORDING_SIGHTING_LIMIT,
    ): Flow<List<Sighting>> = flowOf(emptyList())

    /** Minute rows of one device across recordings, newest first, at most [limit]. */
    fun sightingsOfDevice(addr: String, limit: Int = DEFAULT_SIGHTING_LIMIT): Flow<List<Sighting>>

    /** Minute rows whose advertisement matched the page, newest first, at most [limit]. */
    fun sightingsOfProduct(pageId: String, limit: Int = DEFAULT_SIGHTING_LIMIT): Flow<List<Sighting>>

    // Bundle updates ----------------------------------------------------------------------

    /** The newest re-match that identified something and was not acknowledged; null when none. */
    fun pendingRematchReport(): Flow<RematchReport?>

    suspend fun acknowledgeRematch(reportId: Long)

    companion object {
        private val READY_STATUS_FLOW: StateFlow<LocalDataStatus> = MutableStateFlow(LocalDataStatus.READY_STATUS)
        const val DEFAULT_IN_RANGE_WINDOW_MS = 30_000L
        const val DEFAULT_SIGHTING_LIMIT = 2_000
        const val DEFAULT_RECORDING_SIGHTING_LIMIT = 50_000
    }
}
