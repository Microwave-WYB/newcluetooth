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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOGGER: Logger = Logger.getLogger("LocalDataHub")
private const val MINUTE_MS = 60_000L
private const val FRESH_FIX_MS = 5_000L
private const val MAX_QUEUED_ADVERTISEMENTS = 8_192
private const val PAYLOAD_CACHE_SIZE = 20_000
private const val IN_RANGE_KEEP_MS = 10 * MINUTE_MS
private const val ROUTE_MIN_STEP_M = 5.0
private const val ROUTE_MAX_GAP_MS = 30_000L
private const val ROUTE_MAX_ACCURACY_M = 100.0
private const val DEVICE_COUNT_REFRESH_MS = 5_000L

/**
 * The phone's local data layer: the [LocalDataSink] the scan pipeline feeds and the
 * [LocalData] the UI reads.
 *
 * Ingest is a single consumer coroutine behind an event queue, so one thread owns all
 * buffered state; the scan callback only enqueues. Advertisements are hashed, matched
 * once per distinct payload, aggregated per (address, minute, payload) in memory and
 * written about once a second ([flushIntervalMs]) in one transaction.
 *
 * What is kept, and when (decisions, see docs/local-data.md):
 *  * every distinct payload heard while the app scans, recording or not;
 *  * sighting history and the route only while a recording is active.
 */
class LocalDataHub(
    private val openDatabase: () -> SqlDatabase,
    private val loadBundleJson: () -> String,
    private val createMatcher: (bundleJson: String) -> AdvertisementMatcher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val flushIntervalMs: Long = 1_000L,
) : LocalData, LocalDataSink, AutoCloseable {

    private sealed interface Event {
        class Advertisement(val addr: String, val rssi: Int?, val raw: ByteArray, val timeMs: Long) : Event
        class Location(val fix: CoreLocationInput) : Event
        data object LocationCleared : Event
        class RecordingStarted(val id: String, val atMs: Long) : Event
        class RecordingFinished(val id: String, val atMs: Long) : Event
        class Flush(val done: CompletableDeferred<Unit>?) : Event
    }

    private class DeviceNow(val lastSeenMs: Long, val rssi: Int?, val pageIds: List<String>)

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val queuedAdvertisements = AtomicInteger(0)
    private val ready = CompletableDeferred<Unit>()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _catalog = MutableStateFlow<WikiCatalog?>(null)
    override val catalog: StateFlow<WikiCatalog?> = _catalog.asStateFlow()

    private lateinit var db: SqlDatabase
    private var ticker: Job? = null
    private lateinit var store: LocalStore
    private var matcher: AdvertisementMatcher? = null
    private val inRange = ConcurrentHashMap<String, DeviceNow>()

    // Consumer-thread state.
    private val payloadCache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > PAYLOAD_CACHE_SIZE
    }
    private val payloadIds = HashMap<String, Long>()
    private var pendingPayloads = HashMap<String, PayloadDelta>()
    private var pendingMinutes = HashMap<MinuteKey, MinuteDelta>()
    private var pendingRoute = ArrayList<RoutePoint>()
    private var recordingId: String? = null
    private var recordingDistance = 0.0
    private var lastRoutePoint: RoutePoint? = null
    private var lastDeviceCountRefreshMs = 0L
    private var lastFix: CoreLocationInput? = null
    private val droppedAdvertisements = AtomicInteger(0)

    /** Opens the database, loads the bundle, prunes and re-matches, then starts ingest. Call once. */
    fun start() {
        scope.launch(ioDispatcher) {
            try {
                initialize()
                ready.complete(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LOGGER.log(Level.SEVERE, "Local data unavailable", error)
                ready.completeExceptionally(error)
                return@launch
            }
            ticker = launch { while (true) { delay(flushIntervalMs); events.trySend(Event.Flush(null)) } }
            consume()
        }
    }

    private fun initialize() {
        db = openDatabase()
        store = LocalStore(db)
        val now = clock()
        store.closeOrphanRecordings()
        store.pruneExpired(now)
        try {
            val json = loadBundleJson()
            val catalog = WikiCatalog.parse(json)
            val m = createMatcher(json)
            check(m.bundleVersion == catalog.version) { "matcher and catalog come from different bundles" }
            store.rematchIfNeeded(m, now)
            _catalog.value = catalog
            matcher = m
        } catch (error: Exception) {
            // The history still records; products simply stay unidentified until a bundle loads.
            LOGGER.log(Level.WARNING, "Wiki bundle unavailable; products are not identified", error)
        }
    }

    /** Writes everything buffered now and returns; mainly for tests and orderly shutdown. */
    suspend fun flushNow() {
        val done = CompletableDeferred<Unit>()
        events.send(Event.Flush(done))
        done.await()
    }

    override fun close() {
        ticker?.cancel()
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
        events.trySend(Event.Advertisement(addr, rssi, raw, timeMs))
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
            }
        }
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Advertisement -> {
                queuedAdvertisements.decrementAndGet()
                onHeard(event)
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
                changes.tryEmit(Unit)
            }
            is Event.RecordingFinished -> {
                if (recordingId == event.id) {
                    flush()
                    store.finishRecording(event.id, event.atMs, recordingDistance)
                    recordingId = null
                    lastRoutePoint = null
                    changes.tryEmit(Unit)
                }
            }
            is Event.Flush -> {
                flush()
                event.done?.complete(Unit)
            }
        }
    }

    /** Payload-level pages (no address), cached per distinct payload; these are what the store keeps. */
    private fun payloadPages(hash: String, raw: ByteArray): List<String> {
        val m = matcher ?: return emptyList()
        return payloadCache.getOrPut(hash) { m.match(raw) }
    }

    private fun onHeard(e: Event.Advertisement) {
        val hash = sha256Hex(e.raw)
        payloadPages(hash, e.raw)
        // The live view also lets address rules speak; the address type is not known here.
        val pages = matcher?.match(e.raw, e.addr, null).orEmpty()
        pendingPayloads.getOrPut(hash) { PayloadDelta(e.raw, e.timeMs, e.timeMs, 0) }.apply {
            firstSeenMs = minOf(firstSeenMs, e.timeMs)
            lastSeenMs = maxOf(lastSeenMs, e.timeMs)
            count++
        }
        inRange[e.addr] = DeviceNow(e.timeMs, e.rssi, pages)
        if (recordingId == null) return
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
        val refreshCount = recordingId != null && now - lastDeviceCountRefreshMs >= DEVICE_COUNT_REFRESH_MS
        val batch = FlushBatch(pendingPayloads, pendingMinutes, recordingId, pendingRoute, recordingDistance, refreshCount)
        val version = matcher?.bundleVersion ?: ""
        try {
            store.flush(batch, payloadIds, version) { hash, raw -> payloadCache[hash] ?: matcher?.match(raw).orEmpty() }
        } finally {
            // a failed batch is dropped rather than retried forever; the next one starts clean
            pendingPayloads = HashMap()
            pendingMinutes = HashMap()
            pendingRoute = ArrayList()
        }
        if (refreshCount) lastDeviceCountRefreshMs = now
        if (payloadIds.size > PAYLOAD_CACHE_SIZE) payloadIds.clear()
        changes.tryEmit(Unit)
    }

    // LocalData ---------------------------------------------------------------------------------

    private fun <T> observe(query: () -> T): Flow<T> = flow {
        ready.await()
        emitAll(
            changes.onStart { emit(Unit) }
                .map { withContext(ioDispatcher) { query() } }
                .distinctUntilChanged(),
        )
    }

    override fun productsInRange(windowMs: Long): Flow<InRangeSnapshot> = flow {
        ready.await()
        while (true) {
            emit(inRangeSnapshot(clock(), windowMs))
            delay(IN_RANGE_REFRESH_MS)
        }
    }.distinctUntilChanged { a, b -> a.copy(atMs = 0) == b.copy(atMs = 0) }

    internal fun inRangeSnapshot(nowMs: Long, windowMs: Long): InRangeSnapshot {
        val catalog = _catalog.value
        val devices = inRange.values.filter { nowMs - it.lastSeenMs <= windowMs }
        class Acc(var devices: Int = 0, var rssi: Int? = null, var last: Long = 0)
        val byPage = LinkedHashMap<String, Acc>()
        var unidentified = 0
        for (d in devices) {
            val known = d.pageIds.filter { catalog?.page(it) != null }
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

    override fun previouslySeenProducts(): Flow<List<CategoryProducts>> = observe {
        val catalog = _catalog.value ?: return@observe emptyList()
        val seen = store.seenProducts(catalog).sortedByDescending { it.lastSeenMs }
        val groups = catalog.categories.mapNotNull { category ->
            seen.filter { category.id in it.page.categories }.takeIf { it.isNotEmpty() }
                ?.let { CategoryProducts(category, it) }
        }
        val uncategorized = seen.filter { p -> p.page.categories.none { catalog.category(it) != null } }
        groups + listOfNotNull(uncategorized.takeIf { it.isNotEmpty() }?.let { CategoryProducts(null, it) })
    }

    override fun previouslySeenProducts(categoryId: String): Flow<List<SeenProduct>> = observe {
        val catalog = _catalog.value ?: return@observe emptyList()
        store.seenProducts(catalog).filter { categoryId in it.page.categories }.sortedByDescending { it.lastSeenMs }
    }

    override fun recordings(): Flow<List<Recording>> = observe { store.recordings(clock()) }

    override fun recording(recordingId: String): Flow<Recording?> = observe { store.recording(recordingId, clock()) }

    override fun devicesInRecording(recordingId: String): Flow<List<RecordedDevice>> =
        observe { store.devicesInRecording(recordingId) }

    override fun route(recordingId: String): Flow<List<RoutePoint>> = observe { store.route(recordingId) }

    override fun sightingsOfDevice(addr: String, limit: Int): Flow<List<Sighting>> =
        observe { store.sightingsOfDevice(addr, limit) }

    override fun sightingsOfProduct(pageId: String, limit: Int): Flow<List<Sighting>> =
        observe { store.sightingsOfProduct(pageId, limit) }

    override fun pendingRematchReport(): Flow<RematchReport?> = observe { store.pendingRematchReport() }

    override suspend fun acknowledgeRematch(reportId: Long) {
        ready.await()
        withContext(ioDispatcher) { store.acknowledgeRematch(reportId) }
        changes.tryEmit(Unit)
    }

    private companion object {
        const val IN_RANGE_REFRESH_MS = 1_000L
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
