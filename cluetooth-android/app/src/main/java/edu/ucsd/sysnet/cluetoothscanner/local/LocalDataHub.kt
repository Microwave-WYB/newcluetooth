package edu.ucsd.sysnet.cluetoothscanner.local

import edu.ucsd.sysnet.cluetoothscanner.core.CoreLocationInput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOGGER: Logger = Logger.getLogger("LocalDataHub")
private const val MINUTE_MS = 60_000L
private const val FRESH_FIX_MS = 5_000L
private const val MAX_QUEUED_ADVERTISEMENTS = 8_192
private const val PAYLOAD_CACHE_SIZE = 20_000
private const val IN_RANGE_KEEP_MS = 10 * MINUTE_MS
private const val IN_RANGE_PAYLOADS_PER_DEVICE = 8
private const val ROUTE_MIN_STEP_M = 5.0
private const val ROUTE_MAX_GAP_MS = 30_000L
private const val ROUTE_MAX_ACCURACY_M = 100.0
private const val DEVICE_COUNT_REFRESH_MS = 5_000L
private const val IN_RANGE_REFRESH_MS = 1_000L
private const val SHARED_QUERIES = 64
private const val HOUR_MS = 60L * 60 * 1000
private const val MAX_INIT_RETRY_MS = 5L * 60 * 1000
private const val MAX_REMATCH_RETRY_MS = 10L * 60 * 1000

/**
 * The phone's local data layer: the [LocalDataSink] the scan pipeline feeds and the
 * [LocalData] the UI reads.
 *
 * **Ingest.** One consumer coroutine behind an event queue owns all buffered state; the scan
 * callback only enqueues. Advertisements are hashed, matched once per distinct payload,
 * aggregated per (address, minute, payload) in memory and written about once a second in one
 * transaction. The same consumer runs expiry (hourly, in short steps) and a bundle's re-match
 * (in batches, after startup), so there is exactly one writer.
 *
 * **Queries.** Each is a cold flow that re-runs only when a table group it reads changed
 * (version counters per group, replayed to new collectors), at most once per
 * [lightQueryIntervalMs] (heavy aggregates: [heavyQueryIntervalMs]), always on [ioDispatcher],
 * and shared between collectors of the same key. While the layer cannot start, every query emits
 * empty data (and picks up real data once it opens) and [status] says why.
 *
 * What is kept, and when (see docs/local-data.md):
 *  * every distinct payload heard while the app scans, recording or not, for 30 days after it
 *    was last heard, plus a per-page summary forever;
 *  * sighting history and the route only while a recording (a core scan session) is active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataHub(
    private val openDatabase: () -> SqlDatabase,
    private val loadBundleJson: () -> String,
    private val createMatcher: (bundleJson: String) -> AdvertisementMatcher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val flushIntervalMs: Long = 1_000L,
    private val lightQueryIntervalMs: Long = 1_000L,
    private val heavyQueryIntervalMs: Long = 5_000L,
    private val shareLingerMs: Long = 5_000L,
    private val pruneIntervalMs: Long = HOUR_MS,
    /** First wait before opening the database again after a failure; doubles up to 5 minutes. */
    private val retryInitialMs: Long = 5_000L,
    /** First wait before re-queueing a failed re-match batch; doubles up to 10 minutes. */
    private val rematchRetryMs: Long = 5_000L,
) : LocalData, LocalDataSink, AutoCloseable {

    private sealed interface Event {
        class Advertisement(val addr: String, val rssi: Int?, val raw: ByteArray, val timeMs: Long) : Event
        class Recorded(val addr: String, val rssi: Int?, val raw: ByteArray, val timeMs: Long) : Event
        class Location(val fix: CoreLocationInput) : Event
        data object LocationCleared : Event
        class RecordingStarted(val id: String, val atMs: Long) : Event
        class RecordingFinished(val id: String, val atMs: Long) : Event
        class Flush(val done: CompletableDeferred<Unit>?) : Event
        data object Prune : Event
        data object Rematch : Event
        data object StructureKeys : Event
        class SetNote(val recordingId: String, val note: String, val done: CompletableDeferred<Unit>) : Event
        class Acknowledge(val reportId: Long, val done: CompletableDeferred<Unit>) : Event
    }

    /** Table groups a query can depend on; each has a version that writers bump. */
    private enum class Group { PAYLOADS, RECORDINGS, SIGHTINGS, ROUTE, REMATCH, NOTES }

    private class PayloadSeen(val atMs: Long, val pageIds: List<String>, val structure: StructureKey)

    /** A device in range: its latest sample, and the pages of its recent payloads. */
    private class DeviceNow(val lastSeenMs: Long, val rssi: Int?, val recent: Map<String, PayloadSeen>)

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val queuedAdvertisements = AtomicInteger(0)

    /** null while starting, false while the database is unavailable (retrying), true once it works. */
    private val availability = MutableStateFlow<Boolean?>(null)
    private val versions = Group.entries.associateWith { MutableStateFlow(0L) }
    private val _catalog = MutableStateFlow<WikiCatalog?>(null)
    override val catalog: StateFlow<WikiCatalog?> = _catalog.asStateFlow()
    private val _status = MutableStateFlow(LocalDataStatus(LocalDataStatus.State.STARTING))
    override val status: StateFlow<LocalDataStatus> = _status.asStateFlow()

    private lateinit var db: SqlDatabase
    private lateinit var store: LocalStore
    @Volatile
    private var matcher: AdvertisementMatcher? = null
    private var rulesVersion: String = ""
    private val jobs = ArrayList<Job>()
    private val inRange = ConcurrentHashMap<String, DeviceNow>()
    private val sharedQueries = object : LinkedHashMap<String, Flow<*>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Flow<*>>) = size > SHARED_QUERIES
    }

    // Consumer-thread state.
    private val payloadCache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > PAYLOAD_CACHE_SIZE
    }
    private val structureCache = object : LinkedHashMap<String, StructureKey>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StructureKey>) = size > PAYLOAD_CACHE_SIZE
    }
    private val payloadIds = HashMap<String, Long>()
    private var pendingPayloads = LinkedHashMap<String, PayloadDelta>()
    private var pendingMinutes = HashMap<MinuteKey, MinuteDelta>()
    private var pendingRoute = ArrayList<RoutePoint>()
    private var recordingId: String? = null
    private var recordingDistance = 0.0
    private var lastRoutePoint: RoutePoint? = null
    private var lastDeviceCountRefreshMs = 0L
    private var lastFix: CoreLocationInput? = null
    private var rematchRun: Long? = null
    private var rematchBackoffMs = rematchRetryMs
    private var structureKeyCursor = 0L
    private var structureKeyBackoffMs = rematchRetryMs
    private val droppedAdvertisements = AtomicInteger(0)

    /**
     * Opens the database, loads the bundle, then starts ingest, expiry and the re-match. Call once.
     * A database that cannot be opened (full disk, storage not ready, newer schema) is reported in
     * [status] and retried with a growing wait; events are queued meanwhile (advertisements up to a cap)
     * and nothing on disk is touched.
     */
    fun start() {
        scope.launch(ioDispatcher) {
            var wait = retryInitialMs
            while (true) {
                try {
                    initialize()
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    LOGGER.log(Level.SEVERE, "Local data unavailable; retrying in ${wait}ms", error)
                    runCatching { if (::db.isInitialized) db.close() }
                    _status.value = LocalDataStatus(LocalDataStatus.State.UNAVAILABLE, error.message ?: error::class.java.simpleName)
                    availability.value = false
                    delay(wait)
                    wait = minOf(wait * 2, MAX_INIT_RETRY_MS)
                }
            }
            availability.value = true
            jobs += launch { while (true) { delay(flushIntervalMs); events.trySend(Event.Flush(null)) } }
            jobs += launch { while (true) { events.trySend(Event.Prune); delay(pruneIntervalMs) } }
            if (matcher != null) events.trySend(Event.Rematch)
            events.trySend(Event.StructureKeys)
            consume()
        }
    }

    private fun initialize() {
        db = openDatabase()
        store = LocalStore(db)
        store.closeOrphanRecordings()
        try {
            val json = loadBundleJson()
            val catalog = WikiCatalog.parse(json)
            val m = createMatcher(json)
            check(m.bundleVersion == catalog.version) { "matcher and catalog come from different bundles" }
            m.ruleErrors.forEach { LOGGER.warning("Wiki rule skipped: $it") }
            matcher = m
            // stored matches are re-done when the rules or the engine that evaluates them change
            rulesVersion = (catalog.rulesVersion ?: catalog.version) + "+m" + m.engineVersion
            _catalog.value = catalog
            _status.value = LocalDataStatus.READY_STATUS
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Includes UnsatisfiedLinkError: the history still records, products stay unidentified.
            LOGGER.log(Level.WARNING, "Wiki bundle or native matcher unavailable; products are not identified", error)
            _status.value = LocalDataStatus(
                LocalDataStatus.State.IDENTIFICATION_UNAVAILABLE,
                error.message ?: error::class.java.simpleName,
            )
        }
    }

    /** Writes everything buffered now and returns; mainly for tests and orderly shutdown. */
    suspend fun flushNow() {
        if (availability.filterNotNull().first() != true) return // unavailable: nothing can be written now
        val done = CompletableDeferred<Unit>()
        if (events.trySend(Event.Flush(done)).isFailure) return
        done.await()
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        events.close()
        runCatching { if (::db.isInitialized) db.close() }
    }

    // Sink: callbacks, never blocking ---------------------------------------------------------

    override fun onAdvertisement(addr: String, rssi: Int?, raw: ByteArray, timeMs: Long) {
        if (queuedAdvertisements.incrementAndGet() > MAX_QUEUED_ADVERTISEMENTS) {
            queuedAdvertisements.decrementAndGet()
            droppedAdvertisements.incrementAndGet()
            return
        }
        if (events.trySend(Event.Advertisement(addr, rssi, raw, timeMs)).isFailure) {
            queuedAdvertisements.decrementAndGet()
        }
    }

    override fun onRecordedAdvertisement(addr: String, rssi: Int?, raw: ByteArray, timeMs: Long) {
        if (queuedAdvertisements.incrementAndGet() > MAX_QUEUED_ADVERTISEMENTS) {
            queuedAdvertisements.decrementAndGet()
            droppedAdvertisements.incrementAndGet()
            return
        }
        if (events.trySend(Event.Recorded(addr, rssi, raw, timeMs)).isFailure) {
            queuedAdvertisements.decrementAndGet()
        }
    }

    override fun onLocation(fix: CoreLocationInput) {
        events.trySend(Event.Location(fix))
    }

    override fun onLocationCleared() {
        events.trySend(Event.LocationCleared)
    }

    override fun onRecordingStarted(recordingId: String, startedAtMs: Long) {
        events.trySend(Event.RecordingStarted(recordingId, startedAtMs))
    }

    override fun onRecordingFinished(recordingId: String, endedAtMs: Long) {
        events.trySend(Event.RecordingFinished(recordingId, endedAtMs))
    }

    // Consumer ----------------------------------------------------------------------------------

    private suspend fun consume() {
        for (event in events) {
            try {
                handle(event)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LOGGER.log(Level.WARNING, "Local data write failed", error)
                (event as? Event.Flush)?.done?.complete(Unit)
                (event as? Event.Acknowledge)?.done?.complete(Unit)
                (event as? Event.SetNote)?.done?.completeExceptionally(error) // a note is never dropped silently
            }
        }
    }

    private fun bump(vararg groups: Group) {
        groups.forEach { versions.getValue(it).update { v -> v + 1 } }
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Advertisement -> {
                queuedAdvertisements.decrementAndGet()
                onHeard(event)
            }
            is Event.Recorded -> {
                queuedAdvertisements.decrementAndGet()
                onRecorded(event)
            }
            is Event.Location -> onFix(event.fix)
            Event.LocationCleared -> lastFix = null
            is Event.RecordingStarted -> if (recordingId != event.id) {
                flush()
                recordingId = event.id
                recordingDistance = 0.0
                lastRoutePoint = null
                lastDeviceCountRefreshMs = 0L
                store.startRecording(event.id, event.atMs)
                bump(Group.RECORDINGS)
            }
            is Event.RecordingFinished -> if (recordingId == event.id) {
                flush()
                store.finishRecording(event.id, event.atMs, recordingDistance)
                recordingId = null
                lastRoutePoint = null
                bump(Group.RECORDINGS)
            }
            is Event.Flush -> {
                flush()
                event.done?.complete(Unit)
            }
            Event.Prune -> {
                val more = store.pruneStep(clock())
                if (store.lastPruneDeleted > 0) {
                    payloadIds.clear() // expired payloads must not be referenced by cached ids
                    bump(*Group.entries.toTypedArray())
                }
                if (more) events.trySend(Event.Prune)
            }
            Event.Rematch -> rematchStep()
            Event.StructureKeys -> structureKeysStep()
            is Event.SetNote -> {
                store.setRecordingNote(event.recordingId, event.note, clock())
                bump(Group.NOTES)
                event.done.complete(Unit)
            }
            is Event.Acknowledge -> {
                store.acknowledgeRematch(event.reportId)
                bump(Group.REMATCH)
                event.done.complete(Unit)
            }
        }
    }

    /**
     * One batch of the re-match; queues itself again until the run is done, so ingest interleaves. A failed
     * batch (nothing was written) is re-queued after a growing wait instead of stalling until restart.
     */
    private fun rematchStep() {
        val m = matcher ?: return
        try {
            val run = rematchRun ?: store.beginRematch(rulesVersion, clock())?.also { rematchRun = it } ?: return
            val more = store.rematchBatch(m, rulesVersion, run, clock())
            rematchBackoffMs = rematchRetryMs
            bump(Group.PAYLOADS, Group.REMATCH)
            if (more) events.trySend(Event.Rematch) else rematchRun = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val wait = rematchBackoffMs
            rematchBackoffMs = minOf(wait * 2, MAX_REMATCH_RETRY_MS)
            LOGGER.log(Level.WARNING, "Re-match batch failed; retrying in ${wait}ms", error)
            jobs += scope.launch { delay(wait); events.trySend(Event.Rematch) }
        }
    }

    /**
     * The pages one advertisement matches, for a detail page. Safe to call from any thread (the
     * matcher is immutable once loaded); it does not touch the store's caches. Empty while the
     * bundle is not loaded.
     */
    fun identify(raw: ByteArray, addr: String?): List<String> = matcher?.match(raw, addr).orEmpty()

    /** Gives payloads from before schema version 3 their structure key, one batch per turn of the queue. */
    private fun structureKeysStep() {
        try {
            val last = store.backfillStructureKeys(structureKeyCursor) ?: return
            structureKeyCursor = last
            structureKeyBackoffMs = rematchRetryMs
            bump(Group.PAYLOADS)
            events.trySend(Event.StructureKeys)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val wait = structureKeyBackoffMs
            structureKeyBackoffMs = minOf(wait * 2, MAX_REMATCH_RETRY_MS)
            LOGGER.log(Level.WARNING, "Structure key backfill failed; retrying in ${wait}ms", error)
            jobs += scope.launch { delay(wait); events.trySend(Event.StructureKeys) }
        }
    }

    private fun structureOf(hash: String, raw: ByteArray): StructureKey =
        structureCache.getOrPut(hash) { StructureKey.of(raw) }

    /** Payload-level pages (no address), cached per distinct payload; these are what the store keeps. */
    private fun payloadPages(m: AdvertisementMatcher, hash: String, raw: ByteArray): List<String> =
        payloadCache.getOrPut(hash) { m.match(raw) }

    private fun onHeard(e: Event.Advertisement) {
        val hash = sha256Hex(e.raw)
        val m = matcher
        val payloadPages = m?.let { payloadPages(it, hash, e.raw) }.orEmpty()
        // The live view lets address rules speak too (type unknown); without any, the payload's result is the answer.
        val pages = if (m != null && m.usesAddressRules) m.match(e.raw, e.addr, null) else payloadPages
        pendingPayloads.getOrPut(hash) { PayloadDelta(e.raw, e.timeMs, e.timeMs, 0) }.apply {
            firstSeenMs = minOf(firstSeenMs, e.timeMs)
            lastSeenMs = maxOf(lastSeenMs, e.timeMs)
            count++
        }
        // Keep the union of pages over the device's recent payloads: one that alternates
        // advertisement types must not flicker between products.
        val recent = HashMap<String, PayloadSeen>(inRange[e.addr]?.recent.orEmpty())
        recent[hash] = PayloadSeen(e.timeMs, pages, structureOf(hash, e.raw))
        if (recent.size > IN_RANGE_PAYLOADS_PER_DEVICE) {
            recent.entries.minByOrNull { it.value.atMs }?.let { recent.remove(it.key) }
        }
        inRange[e.addr] = DeviceNow(e.timeMs, e.rssi, recent)
    }

    /** A row the core admitted for the active recording: the minute history, and only while one is active. */
    private fun onRecorded(e: Event.Recorded) {
        if (recordingId == null) return
        val hash = sha256Hex(e.raw)
        matcher?.let { payloadPages(it, hash, e.raw) }
        // Normally the live event delivered the payload already; if it was dropped, still make sure the row exists.
        pendingPayloads.getOrPut(hash) { PayloadDelta(e.raw, e.timeMs, e.timeMs, 0) }
        val fix = lastFix?.takeIf { e.timeMs - it.observedAtMs <= FRESH_FIX_MS }
        val location = fix?.let { GeoPoint(it.lat, it.lon, it.accuracyMeters) }
        pendingMinutes.getOrPut(MinuteKey(e.addr, e.timeMs / MINUTE_MS, hash)) {
            MinuteDelta(e.timeMs, e.timeMs, 0, MinuteBest(null, null))
        }.add(e.timeMs, e.rssi, location)
    }

    private fun onFix(fix: CoreLocationInput) {
        lastFix = fix
        if (recordingId == null || fix.accuracyMeters > ROUTE_MAX_ACCURACY_M) return
        val point = RoutePoint(fix.observedAtMs, fix.lat, fix.lon, fix.accuracyMeters)
        val last = lastRoutePoint
        if (last != null) {
            val step = distanceMeters(last.lat, last.lon, point.lat, point.lon)
            if (step < ROUTE_MIN_STEP_M && point.timeMs - last.timeMs < ROUTE_MAX_GAP_MS) return
            // a stationary phone drifts by metres: only real steps add distance
            if (step >= ROUTE_MIN_STEP_M) recordingDistance += step
        }
        lastRoutePoint = point
        pendingRoute += point
    }

    private fun flush() {
        val now = clock()
        droppedAdvertisements.getAndSet(0).takeIf { it > 0 }?.let {
            LOGGER.warning("Local data queue full: dropped $it advertisements")
        }
        inRange.entries.removeIf { now - it.value.lastSeenMs > IN_RANGE_KEEP_MS }
        if (pendingPayloads.isEmpty() && pendingMinutes.isEmpty() && pendingRoute.isEmpty()) return
        val recording = recordingId
        val batch = FlushBatch(pendingPayloads, pendingMinutes, recording, pendingRoute, recordingDistance)
        try {
            store.flush(batch, payloadIds, rulesVersion) { hash, raw -> payloadCache[hash] ?: matcher?.match(raw).orEmpty() }
        } finally {
            // a failed batch is dropped rather than retried forever; the next one starts clean
            pendingPayloads = LinkedHashMap()
            pendingMinutes = HashMap()
            pendingRoute = ArrayList()
        }
        if (batch.payloads.isNotEmpty()) bump(Group.PAYLOADS)
        if (recording != null) {
            if (batch.minutes.isNotEmpty()) bump(Group.SIGHTINGS)
            if (batch.route.isNotEmpty()) bump(Group.ROUTE)
            if (now - lastDeviceCountRefreshMs >= DEVICE_COUNT_REFRESH_MS) {
                store.refreshDeviceCount(recording) // after the commit, never inside it
                lastDeviceCountRefreshMs = now
            }
            bump(Group.RECORDINGS)
        }
        if (payloadIds.size > PAYLOAD_CACHE_SIZE) payloadIds.clear()
    }

    // LocalData ---------------------------------------------------------------------------------

    /**
     * A flow that runs [query] on [ioDispatcher] when it starts and whenever one of [groups] changes,
     * at most every [intervalMs]. When the layer is unavailable, or the query fails, it emits [fallback].
     */
    private fun <T> observe(groups: List<Group>, intervalMs: Long, fallback: T, query: () -> T): Flow<T> =
        availability.filterNotNull().transformLatest { available ->
            if (!available) {
                emit(fallback) // until the database opens; then this restarts with the real thing
                return@transformLatest
            }
            // StateFlow versions replay: a change between collecting and the first query is not lost
            val changed = combine(groups.map { versions.getValue(it) }) { }.conflate()
            emitAll(
                changed.transform {
                    val value = try {
                        query()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        LOGGER.log(Level.WARNING, "Local data query failed", error)
                        fallback
                    }
                    emit(value)
                    if (intervalMs > 0) delay(intervalMs)
                },
            )
        }.flowOn(ioDispatcher).distinctUntilChanged()

    /** [observe], shared between collectors of the same [key] (kept while someone collects, plus the linger). */
    @Suppress("UNCHECKED_CAST")
    private fun <T> query(key: String, groups: List<Group>, intervalMs: Long, fallback: T, query: () -> T): Flow<T> =
        if (shareLingerMs <= 0) {
            observe(groups, intervalMs, fallback, query) // not shared: each collector queries (tests)
        } else synchronized(sharedQueries) {
            sharedQueries.getOrPut(key) {
                observe(groups, intervalMs, fallback, query).shareIn(
                    scope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = shareLingerMs, replayExpirationMillis = shareLingerMs),
                    replay = 1,
                )
            } as Flow<T>
        }

    /** Re-computes [compute] once a second (cheap, in memory); [empty] while the database is unavailable. */
    private fun <T> ticking(empty: (Long) -> T, same: (T, T) -> Boolean, compute: (Long) -> T): Flow<T> =
        availability.filterNotNull().transformLatest { available ->
            if (!available) {
                emit(empty(clock()))
                return@transformLatest
            }
            while (true) {
                emit(compute(clock()))
                delay(IN_RANGE_REFRESH_MS)
            }
        }.flowOn(ioDispatcher).distinctUntilChanged(same)

    override fun productsInRange(windowMs: Long): Flow<InRangeSnapshot> =
        ticking({ InRangeSnapshot(it, windowMs, emptyList(), 0, 0) }, { a, b -> a.copy(atMs = 0) == b.copy(atMs = 0) }) {
            inRangeSnapshot(it, windowMs)
        }

    override fun unknownStructuresInRange(windowMs: Long): Flow<List<UnknownInRange>> =
        ticking({ emptyList() }, { a, b -> a == b }) { unknownInRange(it, windowMs) }

    /** Unidentified devices of the window, grouped by the structure of their recent unidentified payloads. */
    internal fun unknownInRange(nowMs: Long, windowMs: Long): List<UnknownInRange> {
        val catalog = _catalog.value
        class Acc(var devices: Int = 0, var rssi: Int? = null, var last: Long = 0)
        val byKey = LinkedHashMap<StructureKey, Acc>()
        for (d in inRange.values) {
            if (nowMs - d.lastSeenMs > windowMs) continue
            val recent = d.recent.values.filter { nowMs - it.atMs <= windowMs }
            if (recent.any { p -> p.pageIds.any { catalog?.page(it) != null } }) continue // identified
            for (key in recent.map { it.structure }.toSet()) {
                val acc = byKey.getOrPut(key) { Acc() }
                acc.devices++
                if (d.rssi != null && (acc.rssi == null || d.rssi > acc.rssi!!)) acc.rssi = d.rssi
                acc.last = maxOf(acc.last, d.lastSeenMs)
            }
        }
        return byKey.map { (key, a) -> UnknownInRange(key, a.devices, a.rssi, a.last) }
            .sortedWith(compareByDescending<UnknownInRange> { it.strongestRssi ?: Int.MIN_VALUE }.thenByDescending { it.deviceCount }.thenBy { it.structure.key })
    }

    internal fun inRangeSnapshot(nowMs: Long, windowMs: Long): InRangeSnapshot {
        val catalog = _catalog.value
        val devices = inRange.values.filter { nowMs - it.lastSeenMs <= windowMs }
        class Acc(var devices: Int = 0, var rssi: Int? = null, var last: Long = 0)
        val byPage = LinkedHashMap<String, Acc>()
        var unidentified = 0
        for (d in devices) {
            val pages = d.recent.values.filter { nowMs - it.atMs <= windowMs }.flatMap { it.pageIds }.toSet()
            val known = pages.filter { catalog?.page(it) != null }
            if (known.isEmpty()) unidentified++
            for (page in known) {
                val acc = byPage.getOrPut(page) { Acc() }
                acc.devices++
                if (d.rssi != null && (acc.rssi == null || d.rssi > acc.rssi!!)) acc.rssi = d.rssi
                acc.last = maxOf(acc.last, d.lastSeenMs)
            }
        }
        val products = byPage.map { (id, a) -> ProductInRange(catalog!!.page(id)!!, a.devices, a.rssi, a.last) }
            .sortedWith(compareByDescending<ProductInRange> { it.strongestRssi ?: Int.MIN_VALUE }.thenBy { it.page.title })
        return InRangeSnapshot(nowMs, windowMs, products, unidentified, devices.size)
    }

    override fun previouslySeenProducts(): Flow<List<CategoryProducts>> =
        query("seen", listOf(Group.PAYLOADS), lightQueryIntervalMs, emptyList()) {
            val catalog = _catalog.value ?: return@query emptyList()
            val seen = store.seenProducts(catalog).sortedByDescending { it.lastSeenMs }
            val groups = catalog.categories.mapNotNull { category ->
                seen.filter { category.id in it.page.categories }.takeIf { it.isNotEmpty() }
                    ?.let { CategoryProducts(category, it) }
            }
            val uncategorized = seen.filter { p -> p.page.categories.none { catalog.category(it) != null } }
            groups + listOfNotNull(uncategorized.takeIf { it.isNotEmpty() }?.let { CategoryProducts(null, it) })
        }

    override fun previouslySeenProducts(categoryId: String): Flow<List<SeenProduct>> =
        query("seen:$categoryId", listOf(Group.PAYLOADS), lightQueryIntervalMs, emptyList()) {
            val catalog = _catalog.value ?: return@query emptyList()
            store.seenProducts(catalog).filter { categoryId in it.page.categories }.sortedByDescending { it.lastSeenMs }
        }

    override fun previouslySeenUnknownStructures(): Flow<List<SeenUnknown>> =
        query("unknown", listOf(Group.PAYLOADS, Group.REMATCH), heavyQueryIntervalMs, emptyList()) { store.seenUnknownStructures() }

    override fun recordings(): Flow<List<Recording>> =
        query("recordings", listOf(Group.RECORDINGS), lightQueryIntervalMs, emptyList()) { store.recordings(clock()) }

    override fun recording(recordingId: String): Flow<Recording?> =
        query("recording:$recordingId", listOf(Group.RECORDINGS), lightQueryIntervalMs, null) {
            store.recording(recordingId, clock())
        }

    override fun devicesInRecording(recordingId: String): Flow<List<RecordedDevice>> =
        query("devices:$recordingId", listOf(Group.SIGHTINGS, Group.REMATCH), heavyQueryIntervalMs, emptyList()) {
            store.devicesInRecording(recordingId)
        }

    override fun route(recordingId: String): Flow<List<RoutePoint>> =
        query("route:$recordingId", listOf(Group.ROUTE), lightQueryIntervalMs, emptyList()) { store.route(recordingId) }

    override fun sightingsInRecording(recordingId: String, bounds: GeoBounds?, limit: Int): Flow<List<Sighting>> =
        query("recording-sightings:$recordingId:$bounds:$limit", listOf(Group.SIGHTINGS, Group.REMATCH), heavyQueryIntervalMs, emptyList()) {
            store.sightingsInRecording(recordingId, bounds, limit)
        }

    override fun observeRecordingNote(recordingId: String): Flow<String> =
        query("note:$recordingId", listOf(Group.NOTES), lightQueryIntervalMs, "") { store.recordingNote(recordingId) }

    override suspend fun getRecordingNote(recordingId: String): String {
        if (availability.filterNotNull().first() != true) return ""
        return withContext(ioDispatcher) { store.recordingNote(recordingId) }
    }

    override suspend fun setRecordingNote(recordingId: String, note: String) {
        check(availability.filterNotNull().first() == true) { "Local data is unavailable: the note was not saved" }
        val done = CompletableDeferred<Unit>()
        // through the consumer, the only writer; after a RecordingStarted queued earlier
        check(events.trySend(Event.SetNote(recordingId, note, done)).isSuccess) { "Local data is closed: the note was not saved" }
        done.await()
    }

    override fun sightingsOfDevice(addr: String, limit: Int): Flow<List<Sighting>> =
        query("device:$addr:$limit", listOf(Group.SIGHTINGS, Group.REMATCH), heavyQueryIntervalMs, emptyList()) {
            store.sightingsOfDevice(addr, limit)
        }

    override fun sightingsOfProduct(pageId: String, limit: Int): Flow<List<Sighting>> =
        query("product:$pageId:$limit", listOf(Group.SIGHTINGS, Group.REMATCH), heavyQueryIntervalMs, emptyList()) {
            store.sightingsOfProduct(pageId, limit)
        }

    override fun pendingRematchReport(): Flow<RematchReport?> =
        query("rematch", listOf(Group.REMATCH), lightQueryIntervalMs, null) { store.pendingRematchReport() }

    override suspend fun acknowledgeRematch(reportId: Long) {
        if (availability.filterNotNull().first() != true) return
        val done = CompletableDeferred<Unit>()
        // through the consumer, the only writer
        if (events.trySend(Event.Acknowledge(reportId, done)).isFailure) return
        done.await()
    }
}

internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}
