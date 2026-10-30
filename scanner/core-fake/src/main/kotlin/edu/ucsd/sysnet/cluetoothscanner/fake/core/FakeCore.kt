package edu.ucsd.sysnet.cluetoothscanner.fake.core

import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeleteOutcome
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceLive
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryBin
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryRate
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryRun
import edu.ucsd.sysnet.cluetoothscanner.api.core.DiscoveryUpdate
import edu.ucsd.sysnet.cluetoothscanner.api.core.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.FixSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GeoPoint
import edu.ucsd.sysnet.cluetoothscanner.api.core.Identification
import edu.ucsd.sysnet.cluetoothscanner.api.core.IngestAck
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapData
import edu.ucsd.sysnet.cluetoothscanner.api.core.MapMode
import edu.ucsd.sysnet.cluetoothscanner.api.core.PendingUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedUpload
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductCard
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.Reclaimable
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionScope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionView
import edu.ucsd.sysnet.cluetoothscanner.api.core.Sighting
import edu.ucsd.sysnet.cluetoothscanner.api.core.StorageStats
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadState
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ln
import kotlin.math.roundToInt

/** The prototype's demo states (`?state=1|2|3|4|first`), as the fake's starting point. */
enum class FakeDemo {
    /** 1: opened, not recording; All sessions. */
    AllSessions,
    /** 2: recording. */
    Recording,
    /** 3: after Stop, the session just recorded (frozen). */
    AfterStop,
    /** 4: a history session ("r1"). */
    History,
    /** A brand-new user: no recordings. */
    FirstRun,
}

/** Legacy names from docs/v2-api.md §6.3: lane B develops against these. */
typealias FakeCoreIngest = FakeCore
typealias FakeCoreStorage = FakeCore

/**
 * A deterministic, in-memory core behind [CoreQueries], [CoreStorage] and [CoreIngest]: the data.js world, the
 * §2.1 scope rules, the §2.2 filters and faceted counts, the display filter (G-12), and a live simulator at 1 Hz with
 * staleness after [staleAfterMs] and an appended device every 5 s (§2.6).
 *
 * @param scope runs the live simulator; use a `TestScope` with [clock] on its virtual time in tests.
 * @param clock epoch ms; the default starts at the prototype's "now" (2026-10-28 7:24 PM PDT) and follows real time.
 * @param simulate false: no synthetic RSSI or appends; the live session changes only through [ingestScans].
 * @param bulkDevices extra devices in one more stored session ("bulk"), for map Points ↔ Cells tests (lane E).
 */
class FakeCore(
    private val scope: CoroutineScope,
    private val clock: () -> Long = demoClock(),
    demo: FakeDemo = FakeDemo.AllSessions,
    bulkDevices: Int = 0,
    private val simulate: Boolean = true,
    private val staleAfterMs: Long = 20_000,
    private val liveIntervalMs: Long = 1_000,
) : CoreQueries, CoreStorage, CoreIngest {

    companion object {
        /** The prototype's clock origin. */
        val DEMO_NOW_MS: Long = FakeData.DEMO_NOW_MS
        const val HISTORY_SESSION = "r1"
        const val MAX_ROWS_PER_CALL = 200
        const val POINTS_CAP = 10_000
        /** SPEC R-10: the core's free-space floor. */
        const val STORAGE_FLOOR_BYTES = 200_000_000L
        /** The fake's local.db share of one finished session. */
        const val FAKE_SESSION_DB_BYTES = 180_000L

        fun demoClock(): () -> Long {
            val origin = System.currentTimeMillis()
            return { DEMO_NOW_MS + System.currentTimeMillis() - origin }
        }

        /** A ScanEnvironment for demos and tests. */
        val DEMO_ENVIRONMENT = ScanEnvironment("Pixel 8", "15", 35, "0.1.0", true, true, 1650, "all_supported", false)
    }

    // ---- static device facts ----

    private class Dev(
        val key: Long, val addr: String, var name: String?, var page: FakeCatalog.Page?, var company: Int?,
        val connectable: Boolean?, val legacy: Boolean?, var structures: List<FakeData.Structure>, val txPower: Int?,
        val service: String?,
    ) {
        val apple get() = company == 76
    }

    /** A device in one session: its strongest located point (with that sighting's RSSI) and, separately, its
     *  strongest RSSI, which may come from an unlocated sighting (§2.1). */
    private class SPoint(val key: Long, val best: GeoPoint?, val bestRssi: Int?, val rssi: Int?, val lastSeenMs: Long)

    private class Sess(
        val id: String, val startedMs: Long, val endedMs: Long?, val activeMs: Long, val distanceM: Double,
        val route: List<GeoPoint>, val points: LinkedHashMap<Long, SPoint>, var status: SessionStatus,
    )

    private class LiveDev(val dev: Dev, val index: Int, val base: Int, val initiallyStale: Boolean) {
        var rssi: Int? = null
        var lastHeardMs: Long = Long.MIN_VALUE / 2
        var best: GeoPoint? = null
        var bestRssi: Int? = null
        /** The strongest RSSI heard, located or not. */
        var maxRssi: Int? = null
        /** Epoch second → max RSSI that second, last 60 s. */
        val seconds = HashMap<Long, Int>()
        var sentRssi: Int? = null
        var sentInRange = false
        var sentName: String? = null
        var sentBest: GeoPoint? = null
        var sent = false

        /** R-4a: the first sighting in this recording. */
        var firstHeardMs: Long? = null

        fun hear(rssi: Int, atMs: Long, position: GeoPoint?) {
            if (firstHeardMs == null) firstHeardMs = atMs
            this.rssi = rssi
            maxRssi = maxOf(maxRssi ?: Int.MIN_VALUE, rssi)
            lastHeardMs = atMs
            val sec = Math.floorDiv(atMs, 1000L)
            seconds[sec] = maxOf(seconds[sec] ?: Int.MIN_VALUE, rssi)
            seconds.keys.removeAll { it < sec - 120 }
            if (position != null && (bestRssi == null || rssi > bestRssi!!)) {
                best = position
                bestRssi = rssi
            }
        }
    }

    private class Live(val id: String, val startedMs: Long, val simOriginMs: Long) {
        var phase = RecordingPhase.Recording
        /** R-4a: the running intervals; the last is open while recording. */
        val runs = mutableListOf(DiscoveryRun(startedMs, null))
        val sentBins = HashMap<Long, Int>()
        var sentRuns: List<DiscoveryRun> = emptyList()

        fun closeRun(now: Long) {
            val last = runs.lastOrNull() ?: return
            if (last.endMs == null) runs[runs.size - 1] = last.copy(endMs = now)
        }
        /** SPEC R-10: paused by [simulateStorageFull]. */
        var pausedForStorage = false
        var activeBeforeMs = 0L
        var runningSinceMs: Long? = startedMs
        val devices = LinkedHashMap<Long, LiveDev>()
        val route = mutableListOf<GeoPoint>()
        var sentRoute = 0
        var distanceM = 0.0
        var seq = 0L
        var position: GeoPoint? = null
        var positionAccuracy = 8.0
        var positionMs = 0L
        var nextPool = 0
        var routeK = 40
        val sentCards = HashMap<String, ProductCard>()
        var env: ScanEnvironment? = null
    }

    private val lock = Any()
    private val world = FakeData.build()
    private val devs = LinkedHashMap<Long, Dev>()
    private val sessions = mutableListOf<Sess>()
    private var rec: Live? = null
    private var liveJob: Job? = null
    private var runs = 0
    private var displayFilter = DisplayFilter(hideUnnamedApple = true)
    private var focus: Long? = null
    private val gatt = HashMap<Long, GattView>()
    private val poolRnd = FakeData.Lcg(7)
    private var uuidCounter = 0L
    private var lastFix: LocationFix? = null

    private class Upload(val upload: PendingUpload, val sessionId: String?, var failed: Boolean = false)
    private val uploads = mutableListOf<Upload>()
    /** R-4a: the runs and first sightings of the recordings stopped here. */
    private val finishedDiscovery = HashMap<String, Pair<List<DiscoveryRun>, List<Long>>>()
    private var lastUploadError: String? = null
    private val tempRoot: File by lazy { Files.createTempDirectory("cluetooth-core-fake").toFile() }

    private val liveFlow = MutableSharedFlow<LiveDelta>(extraBufferCapacity = 4)
    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 16)
    override val live: Flow<LiveDelta> = liveFlow.asSharedFlow()
    override val events: Flow<CoreEvent> = eventFlow.asSharedFlow()

    /** Test hooks. */
    var backpressure = false
    /** SPEC R-10: the simulated free space and uploaded raw files (see [simulateStorageFull]). */
    var freeBytes = 32_000_000_000L
    var uploadedRawBytes = 12_000_000L
    var queryDelayMs = 0L
    val ingested = mutableListOf<ScanObservationInput>()
    val locationFixes = mutableListOf<LocationFix>()

    /** The scope the UI opens for [demo] (V-6); null on the first run. */
    val initialScope: Scope?

    init {
        for (d in world.byAddr.values) devs[DeviceKeyCodec.key(d.addr)] = d.toDev()
        if (demo != FakeDemo.FirstRun) {
            for (r in world.recordings) if (!r.current) sessions += r.toSess()
            if (bulkDevices > 0) sessions += bulkSession(bulkDevices)
            val r1 = "r1"
            uploads += (1..3).map { n ->
                Upload(PendingUpload("019a2b00-0000-7000-8000-00000000000$n",
                    "pending/scans/v3/2026-10-29/$r1/019a2b00-0000-7000-8000-00000000000$n.parquet",
                    "scans/v3/2026-10-29/019a2b00-0000-7000-8000-00000000000$n.parquet",
                    1_000L * n, listOf(4_300_000L, 4_100_000L, 4_000_000L)[n - 1]), r1)
            }
            world.byAddr.values.firstOrNull { it.name == "JBL Flip 6" }?.let {
                val key = DeviceKeyCodec.key(it.addr)
                gatt[key] = FakeGatt.jblView(key, DEMO_NOW_MS - 30 * 60_000)
            }
        }
        initialScope = when (demo) {
            FakeDemo.AllSessions -> Scope.All
            FakeDemo.FirstRun -> null
            FakeDemo.History -> Scope.session(HISTORY_SESSION)
            FakeDemo.Recording -> Scope.session(startRecording(DEMO_ENVIRONMENT))
            FakeDemo.AfterStop -> { startRecording(DEMO_ENVIRONMENT); Scope.session(stopRecording()) }
        }
    }

    private fun FakeData.Device.toDev(): Dev {
        val page = FakeCatalog.pageOfProduct(product)
        return Dev(DeviceKeyCodec.key(addr), addr, name, page, company, connectable, legacy, structures, txPower,
            product?.let { FakeData.PRODUCT.getValue(it).service })
    }

    private fun FakeData.Recording.toSess(): Sess {
        val ended = startedMs + minutes * 60_000L
        val pts = LinkedHashMap<Long, SPoint>()
        for (p in points) {
            val key = DeviceKeyCodec.key(p.addr)
            val prev = pts[key]
            if (prev == null || (p.rssi > (prev.rssi ?: Int.MIN_VALUE))) pts[key] = SPoint(key, GeoPoint(p.lon, p.lat), p.rssi, p.rssi, ended)
        }
        return Sess(id, startedMs, ended, minutes * 60_000L, meters.toDouble(), route.map { GeoPoint(it[0], it[1]) }, pts,
            SessionStatus.Completed)
    }

    private fun bulkSession(n: Int): Sess {
        val rnd = FakeData.Lcg(99)
        val started = FakeData.epochMs("2026-10-20", "10:00 AM")
        val pts = LinkedHashMap<Long, SPoint>()
        for (i in 0 until n) {
            val d = FakeData.synthetic(rnd, i).toDev()
            if (d.key in devs) continue
            devs[d.key] = d
            val p = GeoPoint(FakeData.fixed6(FakeData.CENTER_LON + (rnd.next() - 0.5) * 0.6),
                FakeData.fixed6(FakeData.CENTER_LAT + (rnd.next() - 0.5) * 0.4))
            val rssi = -50 - (rnd.next() * 45).toInt()
            pts[d.key] = SPoint(d.key, p, rssi, rssi, started + 3_600_000)
        }
        return Sess("bulk", started, started + 3_600_000, 3_600_000, 25_000.0,
            listOf(GeoPoint(FakeData.CENTER_LON - 0.2, FakeData.CENTER_LAT - 0.1), GeoPoint(FakeData.CENTER_LON + 0.2, FakeData.CENTER_LAT + 0.1)),
            pts, SessionStatus.Completed)
    }

    // ---- scope resolution (§2.1) ----

    private class Row(val dev: Dev, val rssi: Int?, val inRange: Boolean, val spark: ByteArray?, val best: GeoPoint?,
                      val lastSeenMs: Long)

    private class Relation(val rows: List<Row>, val live: Boolean, val seq: Long, val sessions: List<String>)

    private fun now() = clock()

    private fun hasData(s: Sess) = s.points.isNotEmpty()

    private fun currentAsSess(l: Live, now: Long): Sess {
        val pts = LinkedHashMap<Long, SPoint>()
        for (d in l.devices.values) if (d.rssi != null) pts[d.dev.key] = SPoint(d.dev.key, d.best, d.bestRssi, d.maxRssi, d.lastHeardMs)
        return Sess(l.id, l.startedMs, null, activeMs(l, now), l.distanceM, l.route.toList(), pts,
            if (l.phase == RecordingPhase.Paused) SessionStatus.Paused else SessionStatus.Recording)
    }

    private fun activeMs(l: Live, now: Long) = l.activeBeforeMs + (l.runningSinceMs?.let { now - it } ?: 0)

    /**
     * The resolved sessions of [scope] (`sessions ∩ filter`), the current one included. Empty sessions never take
     * part (§2.1), except the live scope itself, which is the current session even before its first device, and
     * exactly one session asked for by id without a filter (V-3: its route and frame still show).
     */
    private fun resolve(scope: Scope, now: Long): List<Sess> {
        val l = rec
        if (l != null && isLive(scope)) return listOf(currentAsSess(l, now))
        val all = (if (l != null) listOf(currentAsSess(l, now)) else emptyList()) + sessions
        val picked = when (val s = scope.sessions) {
            SessionScope.All -> all
            is SessionScope.Sessions -> all.filter { it.id in s.ids }
        }
        val f = scope.filter
        val from = f?.startedFromMs
        val to = f?.startedToMs
        val exactlyOne = f == null && (scope.sessions as? SessionScope.Sessions)?.ids?.size == 1
        return picked.filter { exactlyOne || hasData(it) }.filter { s ->
            (from == null || s.startedMs >= from) && (to == null || s.startedMs <= to)
        }
    }

    private fun isLive(scope: Scope): Boolean {
        val l = rec ?: return false
        val s = scope.sessions
        return scope.filter == null && s is SessionScope.Sessions && s.ids == listOf(l.id)
    }

    private fun shown(d: Dev) = !(displayFilter.hideUnnamedApple && d.apple && d.name == null)

    private fun relation(scope: Scope, now: Long): Relation {
        val l = rec
        if (l != null && isLive(scope)) {
            val rows = l.devices.values.filter { it.rssi != null && shown(it.dev) }.map {
                // C-5/C-6 (ab39eb5): rows carry no sparkline; the device sheet's chart (D-10) has the last minute
                Row(it.dev, it.rssi, now - it.lastHeardMs < staleAfterMs, null, it.best, it.lastHeardMs)
            }
            return Relation(rows, true, l.seq, listOf(l.id))
        }
        val resolved = resolve(scope, now)
        // §2.1 union: the strongest RSSI and the strongest located point are kept apart (first session wins ties).
        val rssi = LinkedHashMap<Long, Int?>()
        val best = HashMap<Long, SPoint>()
        val last = HashMap<Long, Long>()
        for (s in resolved) for (p in s.points.values) {
            if (p.key !in rssi || (p.rssi ?: Int.MIN_VALUE) > (rssi[p.key] ?: Int.MIN_VALUE)) rssi[p.key] = p.rssi
            val prev = best[p.key]
            if (p.best != null && (prev == null || (p.bestRssi ?: Int.MIN_VALUE) > (prev.bestRssi ?: Int.MIN_VALUE))) {
                best[p.key] = p
            }
            last[p.key] = maxOf(last[p.key] ?: Long.MIN_VALUE, p.lastSeenMs)
        }
        val rows = rssi.mapNotNull { (key, max) ->
            val d = devs.getValue(key)
            if (!shown(d)) null else Row(d, max, true, null, best[key]?.best, last.getValue(key))
        }
        return Relation(rows, false, 0, resolved.map { it.id })
    }

    private val order = compareByDescending<Row> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.dev.key }

    // ---- filters (§2.2, §3.6) ----

    private fun passes(r: Row, f: DeviceFilter, categories: Boolean = true): Boolean {
        val page = r.dev.page
        val area = f.area
        val query = f.query
        if (area != null && (r.best == null || !Geo.inside(r.best, area))) return false
        when (f.identification) {
            Identification.Any -> Unit
            Identification.Identified -> if (page == null) return false
            Identification.Unidentified -> if (page != null) return false
        }
        if (categories && f.categories.isNotEmpty() && (page == null || page.categories.none { it in f.categories })) return false
        if (f.readable && page?.readable != true) return false
        if (f.trackers && page?.tracker != true) return false
        if (f.vulnerable && page?.vulnerable != true) return false
        if (f.inRangeOnly && !r.inRange) return false
        if (f.page != null && page?.id != f.page) return false
        if (query != null && !MatchDevice.matches(query, r.dev.name, r.dev.addr)) return false
        return true
    }

    private suspend fun <T> query(block: (Long) -> T): T {
        if (queryDelayMs > 0) delay(queryDelayMs)
        return synchronized(lock) { block(now()) }
    }

    private fun keys(rows: List<Row>, seq: Long) = DeviceKeys(rows.map { it.dev.key }.toLongArray(), seq)

    // ---- CoreQueries ----

    override suspend fun sessions(): List<SessionSummary> = query { now ->
        sessions.filter(::hasData).sortedByDescending { it.startedMs }.map { summary(it) }
    }

    override suspend fun currentSession(): SessionSummary? = query { now -> rec?.let { summary(currentAsSess(it, now)) } }

    private fun summary(s: Sess): SessionSummary {
        val ups = uploads.filter { it.sessionId == s.id }
        val upload = when {
            s.points.isEmpty() -> UploadState.Empty
            ups.any { it.failed } -> UploadState.Failed
            ups.isNotEmpty() || s.status == SessionStatus.Recording || s.status == SessionStatus.Paused -> UploadState.Pending
            else -> UploadState.Uploaded
        }
        val count = s.points.keys.count { shown(devs.getValue(it)) }
        return SessionSummary(s.id, s.status, s.startedMs, s.endedMs, s.activeMs, s.distanceM, count, upload)
    }

    /**
     * R-4a, the core's rule: devices counted in the 30 s bin of their first sighting, per run from the run's own
     * start; sparse; devices the display filter hides are not counted. A data.js session has one run, and its
     * points' last-seen times stand in for first sightings.
     */
    override suspend fun discoveryRate(sessionId: String): CoreResult<DiscoveryRate> = CoreResult.Ok(query {
        val l = rec
        val finished = finishedDiscovery[sessionId]
        val stored = sessions.firstOrNull { it.id == sessionId }
        when {
            l != null && l.id == sessionId ->
                DiscoveryRate(l.seq, DiscoveryRate.BIN_MS, discoveryBins(l.runs, liveFirstSeen(l)), l.runs.toList())
            finished != null -> DiscoveryRate(0, DiscoveryRate.BIN_MS, discoveryBins(finished.first, finished.second), finished.first)
            stored != null -> {
                val runs = listOf(DiscoveryRun(stored.startedMs, stored.endedMs))
                val firstSeen = stored.points.values.filter { p -> devs[p.key]?.let(::shown) ?: true }.map { it.lastSeenMs }
                DiscoveryRate(0, DiscoveryRate.BIN_MS, discoveryBins(runs, firstSeen), runs)
            }
            else -> DiscoveryRate.Empty
        }
    })

    private fun liveFirstSeen(l: Live): List<Long> =
        l.devices.values.filter { it.rssi != null && shown(it.dev) }.map { it.firstHeardMs ?: it.lastHeardMs }

    private fun discoveryBins(runs: List<DiscoveryRun>, firstSeen: List<Long>): List<DiscoveryBin> {
        val counts = sortedMapOf<Long, Int>()
        for (t in firstSeen) {
            val run = runs.lastOrNull { it.startMs <= t } ?: runs.firstOrNull() ?: continue
            val clamped = run.endMs?.let { minOf(t, it - 1) } ?: t
            val start = run.startMs + maxOf(0L, clamped - run.startMs) / DiscoveryRate.BIN_MS * DiscoveryRate.BIN_MS
            counts[start] = (counts[start] ?: 0) + 1
        }
        return counts.map { (start, n) -> DiscoveryBin(start, n) }
    }

    override suspend fun allSessionsSummary(): AllSessionsSummary = query { now ->
        val resolved = resolve(Scope.All, now).filter(::hasData)
        val rel = relation(Scope.All, now)
        AllSessionsSummary(resolved.size, rel.rows.size, Geo.bboxOf(resolved.flatMap { it.route } + rel.rows.mapNotNull { it.best }))
    }

    override suspend fun sessionView(scope: Scope): SessionView = query { now ->
        val rel = relation(scope, now)
        val resolved = resolve(scope, now)
        val single = scope.sessions is SessionScope.Sessions && resolved.size == 1
        val route = if (single) resolved[0].route else emptyList()
        val frame = Geo.bboxOf((if (single) route else resolved.flatMap { it.route }) + rel.rows.mapNotNull { it.best })
        SessionView(scope, rel.live, resolved.map { it.id }, rel.rows.size, frame, route, !single, rel.seq)
    }

    override suspend fun routes(scope: Scope, viewport: Bbox?, zoom: Double): String = query { now ->
        val resolved = resolve(scope, now)
        val single = scope.sessions is SessionScope.Sessions && resolved.size == 1
        val lines = resolved.map { it.route }.filter { it.size > 1 }.filter { r ->
            single || viewport == null || Geo.bboxOf(r)!!.let { b ->
                b.west <= viewport.east && b.east >= viewport.west && b.south <= viewport.north && b.north >= viewport.south
            }
        }
        Geo.collection(lines.map(Geo::lineFeature))
    }

    override suspend fun deviceOrder(scope: Scope): DeviceKeys = query { now ->
        val rel = relation(scope, now)
        keys(rel.rows.sortedWith(order), rel.seq)
    }

    override suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys = query { now ->
        val rel = relation(scope, now)
        keys(rel.rows.filter { passes(it, filter) }.sortedWith(order), rel.seq)
    }

    override suspend fun deviceRows(scope: Scope, keys: LongArray): List<DeviceRow> = query { now ->
        require(keys.size <= MAX_ROWS_PER_CALL) { "at most $MAX_ROWS_PER_CALL keys per call" }
        val byKey = relation(scope, now).rows.associateBy { it.dev.key }
        keys.asList().mapNotNull { byKey[it]?.toDeviceRow() }
    }

    private fun Row.toDeviceRow(): DeviceRow {
        val p = dev.page
        return DeviceRow(dev.key, dev.addr, dev.name, p?.ref, p?.categories.orEmpty(), p?.traits.orEmpty(),
            p?.readable == true, p?.vulnerable == true, null, rssi, inRange, spark, best, lastSeenMs)
    }

    override suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount> = query { now ->
        val counts = HashMap<String, Int>()
        for (r in relation(scope, now).rows) {
            if (!passes(r, filter, categories = false)) continue
            for (c in r.dev.page?.categories.orEmpty().distinct()) counts[c] = (counts[c] ?: 0) + 1
        }
        counts.map { (c, n) -> CategoryCount(c, FakeCatalog.CATEGORY[c]?.title ?: c, n) }
            .sortedWith(compareByDescending<CategoryCount> { it.deviceCount }.thenBy { it.label })
    }

    private fun cards(rel: Relation, rows: List<Row>): Map<String, ProductCard> {
        val byPage = rows.groupBy { it.dev.page?.id ?: FakeCatalog.UNIDENTIFIED }
        return byPage.mapValues { (id, rs) ->
            val page = FakeCatalog.PAGE[id]
            ProductCard(id, page?.title ?: "Unidentified devices", page?.kind, null, rel.live, rs.size,
                rs.count { it.inRange }, rs.maxOf { it.lastSeenMs })
        }
    }

    override suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup> = query { now ->
        val rel = relation(scope, now)
        val f = filter.copy(page = null)
        val cards = cards(rel, rel.rows.filter { passes(it, f) })
        val groups = cards.values.filter { it.id != FakeCatalog.UNIDENTIFIED }
            .groupBy { FakeCatalog.PAGE.getValue(it.id).categories.firstOrNull() ?: FakeCatalog.UNIDENTIFIED }
            .map { (cat, cs) ->
                CategoryGroup(cat, FakeCatalog.CATEGORY[cat]?.title ?: cat, cs.sortedBy { FakeCatalog.PAGE_ORDER.getValue(it.id) })
            }
            .sortedWith(compareByDescending<CategoryGroup> { g -> g.products.sumOf { it.count } }
                .thenBy { g -> FakeCatalog.CATEGORIES.indexOfFirst { it.id == g.category } })
        groups + listOfNotNull(cards[FakeCatalog.UNIDENTIFIED]?.let {
            CategoryGroup(FakeCatalog.UNIDENTIFIED, "Unidentified", listOf(it))
        })
    }

    override suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail? = query { now ->
        val page = pageId?.let { FakeCatalog.PAGE[it] ?: return@query null }
        val rows = relation(scope, now).rows.filter { passes(it, filter.copy(page = null)) && it.dev.page?.id == pageId }
        ProductDetail(
            page = page?.ref, title = page?.title ?: "Unidentified devices",
            oneLine = page?.oneLine ?: "Devices no wiki family matches yet.",
            categories = page?.categories.orEmpty(), traits = page?.traits.orEmpty(), readable = page?.readable == true,
            vulnerabilities = page?.vulnerabilities.orEmpty().map { FakeCatalog.PAGE.getValue(it).ref },
            wikiUrl = page?.let { FakeCatalog.WIKI_URL + it.id }, icon = null, iconCredit = null,
            places = rows.mapNotNull { it.best }.map(Geo::tile17).distinct().size,
            lastSeenMs = rows.maxOfOrNull { it.lastSeenMs }, deviceCount = rows.size,
        )
    }

    override suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail? = query { now ->
        val rel = relation(scope, now)
        val r = rel.rows.firstOrNull { it.dev.key == key } ?: return@query null
        val d = r.dev
        val p = d.page
        DeviceDetail(
            key = d.key, addr = d.addr, name = d.name, page = p?.ref, categories = p?.categories.orEmpty(),
            traits = p?.traits.orEmpty(), readable = p?.readable == true, vulnerable = p?.vulnerable == true,
            vulnerabilities = p?.vulnerabilities.orEmpty().map { FakeCatalog.PAGE.getValue(it).ref },
            wikiUrl = p?.let { FakeCatalog.WIKI_URL + it.id }, icon = null, rssi = r.rssi, inRange = r.inRange,
            live = rel.live, connectable = d.connectable,
            format = when (d.legacy) { true -> AdvFormat.Legacy; false -> AdvFormat.Extended; null -> AdvFormat.Unknown },
            companyId = d.company, companyName = d.company?.let { FakeData.COMPANY_NAMES[it] },
            serviceUuid = d.service, txPower = d.txPower,
            structures = d.structures.map { AdStructure(it.length, it.type, it.data) }, tail = null, decoded = null,
            lastSeenMs = r.lastSeenMs,
        )
    }

    override suspend fun deviceSightings(scope: Scope, key: DeviceKey): List<Sighting> = query { now ->
        val r = relation(scope, now).rows.firstOrNull { it.dev.key == key } ?: return@query emptyList()
        val trail = FakeData.sightings(world, r.dev.addr)
        if (trail.isEmpty()) listOfNotNull(r.best?.let { Sighting(it, r.rssi ?: -100, r.lastSeenMs) })
        else trail.mapIndexed { k, p -> Sighting(GeoPoint(p.lon, p.lat), p.rssi, r.lastSeenMs - k * 4_000L) }
    }

    override suspend fun deviceChart(key: DeviceKey): LiveChart? = synchronized(lock) { chart(key, now()) }

    override suspend fun deviceGatt(key: DeviceKey): GattView? = query { gatt[key] }

    override suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys? {
        if (MatchDevice.isBlank(query)) return null
        return deviceKeys(scope, DeviceFilter(area = area, query = query))
    }

    override fun matchDevice(query: String, name: String?, addr: String) = MatchDevice.matches(query, name, addr)

    override suspend fun mapPoints(scope: Scope, filter: DeviceFilter, viewport: Bbox, zoom: Double): MapData = query { now ->
        val rel = relation(scope, now)
        val view = Geo.grow(viewport)
        val rows = rel.rows.filter { it.best != null && Geo.inside(it.best, view) && passes(it, filter) }
        if (rows.size <= POINTS_CAP) {
            val features = rows.sortedWith(order).map { r ->
                Geo.pointFeature(r.best!!, "\"addr\":${Geo.str(r.dev.addr)},\"rssi\":${r.rssi ?: "null"}")
            }
            MapData(MapMode.Points, rows.size, Geo.collection(features), rel.seq)
        } else {
            val width = (viewport.east - viewport.west).coerceAtLeast(1e-9)
            val z = (ln(360.0 * 16 / width) / ln(2.0)).toInt().coerceIn(0, 17)
            val cells = rows.groupBy { Geo.tileX(it.best!!.lon, z) to Geo.tileY(it.best.lat, z) }
            val features = cells.values.map { rs ->
                val at = GeoPoint(rs.sumOf { it.best!!.lon } / rs.size, rs.sumOf { it.best!!.lat } / rs.size)
                Geo.pointFeature(at, "\"point_count\":${rs.size},\"point_count_abbreviated\":${Geo.str(Geo.abbreviated(rs.size))}")
            }
            MapData(MapMode.Cells, rows.size, Geo.collection(features), rel.seq)
        }
    }

    override suspend fun setLiveFocus(key: DeviceKey?) { synchronized(lock) { focus = key } }

    // ---- live state (§2.6) ----

    private fun encode(rssi: Int?): Byte = if (rssi == null) 0 else (-rssi).coerceIn(30, 100).toByte()

    private fun chart(key: Long, now: Long): LiveChart? {
        val l = rec ?: return null
        val d = l.devices[key] ?: return null
        val sec = Math.floorDiv(now, 1000L)
        val samples = ByteArray(60) { j -> encode(d.seconds[sec - 59 + j]) }
        return LiveChart(key, now, samples, if (now - d.lastHeardMs < staleAfterMs) d.rssi else null)
    }

    private fun recordingState(l: Live?, now: Long): CoreRecordingState = if (l == null) {
        CoreRecordingState(RecordingPhase.Idle, null, null, 0, null, 0, 0.0)
    } else {
        CoreRecordingState(l.phase, l.id, l.startedMs, l.activeBeforeMs, l.runningSinceMs,
            l.devices.values.count { it.rssi != null && shown(it.dev) }, l.distanceM, l.pausedForStorage)
    }

    private fun seed(l: Live) {
        val now = l.simOriginMs
        val rec = world.recordings.first { it.current }
        val bySec = Math.floorDiv(now, 1000L)
        for ((i, p) in rec.points.withIndex()) {
            val src = world.byAddr.getValue(p.addr)
            val d = LiveDev(devs.getValue(DeviceKeyCodec.key(p.addr)), i, src.rssi, src.stale)
            src.history?.forEachIndexed { k, v -> if (v != null) d.seconds[bySec - (29 - k) * 2L] = v }
            d.rssi = src.rssi
            d.lastHeardMs = if (src.stale) now - 20_000 else now
            d.best = GeoPoint(p.lon, p.lat)
            d.bestRssi = p.rssi
            d.maxRssi = src.rssi  // data.js's point RSSI, so the frozen demo (state 3) matches the prototype
            l.devices[d.dev.key] = d
        }
        l.nextPool = rec.points.size
        rec.route.forEach { l.route += GeoPoint(it[0], it[1]) }
        l.distanceM = rec.meters.toDouble()
        l.position = l.route.lastOrNull()
        l.positionMs = now
    }

    private fun markSent(l: Live, now: Long) {
        for (d in l.devices.values) {
            d.sent = d.rssi != null
            d.sentRssi = d.rssi
            d.sentInRange = now - d.lastHeardMs < staleAfterMs
            d.sentName = d.dev.name
            d.sentBest = d.best
        }
        l.sentRoute = l.route.size
        l.sentCards.clear()
        l.sentCards.putAll(liveCards(l, now))
    }

    private fun liveCards(l: Live, now: Long): Map<String, ProductCard> {
        val rel = relation(Scope.session(l.id), now)
        return cards(rel, rel.rows)
    }

    private fun simulateStep(l: Live, now: Long) {
        val n = ((now - l.simOriginMs) / 1000).toInt()
        if (n % 2 == 0) {
            val p = FakeData.liveRoutePoint(l.routeK++)
            val next = GeoPoint(p[0], p[1])
            l.position?.let { l.distanceM += Geo.haversineM(it, next) }
            l.route += next
            l.position = next
            l.positionMs = now
        }
        for (d in l.devices.values) {
            if (d.base == Int.MIN_VALUE) continue
            val i = d.index
            val silent = (d.initiallyStale && n < 40) || (i % 5 == 3 && Math.floorMod(n + i * 11, 120) < 30)
            if (silent) continue
            val rssi = (d.base + 4 * StrictMath.sin(n * 0.35 + i) + 2 * StrictMath.sin(n * 1.1 + 2 * i)).roundToInt()
            d.hear(rssi.coerceIn(-100, -30), now, l.position)
        }
        if (n > 0 && n % 5 == 0) {
            val src = if (l.nextPool < world.devices.size) world.devices[l.nextPool]
            else FakeData.synthetic(poolRnd, l.nextPool)
            l.nextPool++
            val dev = devs.getOrPut(DeviceKeyCodec.key(src.addr)) { src.toDev() }
            if (dev.key !in l.devices) {
                val d = LiveDev(dev, l.devices.size, src.rssi, false)
                d.hear(src.rssi, now, l.position)
                l.devices[dev.key] = d
            }
        }
    }

    /** One delta from the changes since the last one, or null when nothing changed (unless [force]). */
    private fun buildDelta(l: Live, now: Long, force: Boolean): LiveDelta? {
        val changed = mutableListOf<DeviceLive>()
        val appended = mutableListOf<DeviceRow>()
        for (d in l.devices.values) {
            if (d.rssi == null || !shown(d.dev)) continue
            val inRange = now - d.lastHeardMs < staleAfterMs
            if (!d.sent) {
                appended += Row(d.dev, d.rssi, inRange, null, d.best, d.lastHeardMs).toDeviceRow()
            } else if (d.sentRssi != d.rssi || d.sentInRange != inRange ||
                d.sentName != d.dev.name || d.sentBest != d.best) {
                changed += DeviceLive(d.dev.key, d.rssi, inRange, ByteArray(0),
                    d.dev.name.takeIf { it != d.sentName }, null, d.best.takeIf { it != d.sentBest })
            }
        }
        val cards = liveCards(l, now)
        val products = cards.values.filter { l.sentCards[it.id] != it }
        val routeAppended = l.route.subList(l.sentRoute, l.route.size).toList()
        val bins = discoveryBins(l.runs, liveFirstSeen(l))
        val freshBins = bins.filter { l.sentBins[it.startMs] != it.newDevices }
        val discovery = if (freshBins.isEmpty() && l.runs == l.sentRuns) null else DiscoveryUpdate(freshBins, l.runs.toList())
        // the focused device's chart (D-10) rides on every delta, as in the core
        if (!force && focus == null && discovery == null && changed.isEmpty() && appended.isEmpty() &&
            products.isEmpty() && routeAppended.isEmpty()) return null
        for (b in freshBins) l.sentBins[b.startMs] = b.newDevices
        l.sentRuns = l.runs.toList()
        markSent(l, now)
        l.seq++
        val fix = l.position?.let { FixSummary(it.lat, it.lon, l.positionAccuracy, now - l.positionMs) }
        return LiveDelta(l.id, l.seq, now, recordingState(l, now), changed, appended, products, routeAppended, fix,
            focus?.let { chart(it, now) }, discovery)
    }

    private fun tick(now: Long): LiveDelta? {
        val l = rec ?: return null
        if (l.phase != RecordingPhase.Recording) return null
        if (simulate) simulateStep(l, now)
        return buildDelta(l, now, force = false)
    }

    private fun startSimulator() {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                delay(liveIntervalMs)
                val delta = synchronized(lock) { tick(now()) }
                if (delta != null) liveFlow.emit(delta)
            }
        }
    }

    private fun emit(event: CoreEvent) { scope.launch { eventFlow.emit(event) } }

    // ---- CoreIngest ----

    override fun startRecording(env: ScanEnvironment): String {
        val state = synchronized(lock) {
            if (rec != null) throw CoreException.AlreadyRecording()
            val now = now()
            val id = "live${++runs}"
            val l = if (simulate) Live(id, now - 12 * 60_000L, now).also(::seed) else Live(id, now, now)
            l.env = env
            rec = l
            markSent(l, now)
            recordingState(l, now)
        }
        startSimulator()
        emit(CoreEvent.RecordingChanged(state))
        return state.sessionId!!
    }

    override fun pauseRecording() {
        val (delta, state) = synchronized(lock) {
            val l = rec ?: throw CoreException.NotRecording()
            if (l.phase == RecordingPhase.Paused) return
            val now = now()
            l.activeBeforeMs += now - (l.runningSinceMs ?: now)
            l.runningSinceMs = null
            l.phase = RecordingPhase.Paused
            l.closeRun(now)
            buildDelta(l, now, force = true) to recordingState(l, now)
        }
        liveJob?.cancel()
        scope.launch { delta?.let { liveFlow.emit(it) } }
        emit(CoreEvent.RecordingChanged(state))
    }

    override fun resumeRecording() {
        val state = synchronized(lock) {
            val l = rec ?: throw CoreException.NotRecording()
            if (l.phase == RecordingPhase.Recording) return
            if (l.pausedForStorage && freeBytes < STORAGE_FLOOR_BYTES) throw CoreException.StorageFull(freeBytes)
            l.pausedForStorage = false
            val now = now()
            l.phase = RecordingPhase.Recording
            l.runningSinceMs = now
            l.runs += DiscoveryRun(now, null)
            recordingState(l, now)
        }
        startSimulator()
        emit(CoreEvent.RecordingChanged(state))
    }

    override fun stopRecording(): String {
        val id = synchronized(lock) {
            val l = rec ?: throw CoreException.NotRecording()
            val now = now()
            val s = currentAsSess(l, now)
            l.closeRun(now)
            finishedDiscovery[l.id] = l.runs.toList() to liveFirstSeen(l)
            sessions.add(0, Sess(s.id, s.startedMs, now, s.activeMs, s.distanceM, s.route, s.points, SessionStatus.Completed))
            val payload = uuid7(now)
            uploads += Upload(PendingUpload(payload, "pending/scans/v3/2026-10-29/${l.id}/$payload.parquet",
                "scans/v3/2026-10-29/$payload.parquet", s.points.size * 40L, s.points.size * 4_000L), l.id)
            rec = null
            l.id
        }
        liveJob?.cancel()
        liveJob = null
        emit(CoreEvent.RecordingChanged(recordingState()))
        emit(CoreEvent.SessionsChanged)
        emit(CoreEvent.UploadNeeded)
        return id
    }

    override fun configureScan(env: ScanEnvironment) {
        synchronized(lock) { (rec ?: throw CoreException.NotRecording()).env = env }
    }

    override fun recordingState(): CoreRecordingState = synchronized(lock) { recordingState(rec, now()) }

    override fun setDisplayFilter(filter: DisplayFilter) {
        synchronized(lock) { displayFilter = filter }
        emit(CoreEvent.DataInvalidated("display filter"))
    }

    override fun ingestScans(batch: List<ScanObservationInput>): IngestAck = synchronized(lock) {
        if (batch.size > 64) throw CoreException.InputBatchTooLarge("${batch.size} rows > 64")
        if (batch.any { it.raw.size > 1650 }) throw CoreException.InputBatchTooLarge("raw > 1650 bytes")
        if (backpressure) throw CoreException.Backpressure()
        if (rec?.pausedForStorage == true) throw CoreException.StorageFull(freeBytes)
        val l = rec
        if (l == null || l.phase != RecordingPhase.Recording) return@synchronized IngestAck(0, 0, batch.size)
        var rejected = 0
        for (o in batch) {
            val key = runCatching { DeviceKeyCodec.key(o.addr) }.getOrNull()
            if (key == null || o.addr.length != 17) { rejected++; continue }
            ingested += o
            val structures = split(o.raw)
            val company = structures.firstOrNull { it.type == 0xFF && it.dataHex.length >= 4 }?.let {
                it.dataHex.substring(0, 2).toInt(16) or (it.dataHex.substring(2, 4).toInt(16) shl 8)
            }
            val dev = devs.getOrPut(key) {
                Dev(key, o.addr.uppercase(), o.localName, null, company, o.isConnectable, o.isLegacy, structures, o.txPower, null)
            }
            if (o.localName != null) dev.name = o.localName
            if (structures.isNotEmpty()) dev.structures = structures
            if (company != null) dev.company = company
            val d = l.devices.getOrPut(key) { LiveDev(dev, l.devices.size, Int.MIN_VALUE, false) }
            o.rssi?.let { d.hear(it, now(), l.position) } ?: run { if (d.rssi == null) d.rssi = -100; d.lastHeardMs = now() }
        }
        IngestAck(batch.size - rejected, rejected, 0)
    }

    /** `ble_adv_split`: stop at a zero length or a truncated field. */
    private fun split(raw: ByteArray): List<FakeData.Structure> {
        val out = mutableListOf<FakeData.Structure>()
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xff
            if (len == 0 || i + 1 + len > raw.size) break
            out += FakeData.Structure(raw[i + 1].toInt() and 0xff,
                raw.copyOfRange(i + 2, i + 1 + len).joinToString("") { "%02x".format(it.toInt() and 0xff) })
            i += 1 + len
        }
        return out
    }

    override fun ingestLocation(fix: LocationFix) {
        synchronized(lock) {
            locationFixes += fix
            lastFix = fix
            val l = rec ?: return
            if (simulate || l.phase != RecordingPhase.Recording || fix.accuracyMeters > 100) return
            val p = GeoPoint(fix.lon, fix.lat)
            val last = l.route.lastOrNull()
            if (last == null || Geo.haversineM(last, p) >= 5 || fix.observedAtMs - l.positionMs >= 30_000) {
                last?.let { l.distanceM += Geo.haversineM(it, p) }
                l.route += p
            }
            l.position = p
            l.positionAccuracy = fix.accuracyMeters
            l.positionMs = fix.observedAtMs
        }
    }

    override fun clearLocation() {
        synchronized(lock) {
            lastFix = null
            rec?.position = null
        }
    }

    private fun uuid7(ms: Long): String {
        val c = ++uuidCounter
        val msb = (ms shl 16) or 0x7000L or (c and 0xfff)
        val lsb = Long.MIN_VALUE or (c and 0x3FFF_FFFF_FFFF_FFFFL)
        return UUID(msb, lsb).toString()
    }

    override fun newGattDiscoveryId(): String = synchronized(lock) { uuid7(now()) }

    override suspend fun gattTarget(key: DeviceKey): GattTarget? = query { now ->
        val d = devs[key] ?: return@query null
        val raw = d.structures.flatMap { s -> listOf(s.length.toByte(), s.type.toByte()) + s.data.toList() }.toByteArray()
        val l = rec
        val ld = l?.devices?.get(key)
        GattTarget(d.addr, null, d.name, raw, ld?.rssi, ld?.lastHeardMs ?: now, d.legacy, 1, if (d.legacy == false) 2 else null,
            if (d.legacy == false) 0 else null, l?.id)
    }

    override fun ingestGatt(gattV1Json: String): GattView {
        val (view, outcome) = FakeGatt.parse(gattV1Json)
        synchronized(lock) {
            if (outcome != "cancelled") gatt[view.key] = view  // D-13: a cancelled run is never shown
            uploads += Upload(PendingUpload(view.discoveryId, "pending/gatt/v1/${view.discoveryId}.json",
                "gatt/v1/${view.discoveryId}.json", 1, gattV1Json.length.toLong()), rec?.id)
        }
        emit(CoreEvent.UploadNeeded)
        return view
    }

    override fun uploadStatus(): UploadStatus = synchronized(lock) {
        UploadStatus(uploads.size, uploads.sumOf { it.upload.sizeBytes }, uploads.count { it.failed }, lastUploadError)
    }

    override fun pendingUploads(): List<PendingUpload> = synchronized(lock) { uploads.map { it.upload } }

    override fun prepareUpload(id: String): PreparedUpload = synchronized(lock) {
        val u = uploads.firstOrNull { it.upload.payloadId == id } ?: throw CoreException.UploadState("unknown upload $id")
        val file = File(tempRoot, "upload-cache/${u.upload.objectPath}.encrypted")
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(48) { it.toByte() })
        PreparedUpload(id, file.path, u.upload.objectPath, u.upload.sizeBytes, u.upload.sizeBytes + 48)
    }

    override fun markUploadSucceeded(id: String) {
        synchronized(lock) {
            val u = uploads.firstOrNull { it.upload.payloadId == id } ?: throw CoreException.UploadState("unknown upload $id")
            uploads -= u
            File(tempRoot, "upload-cache/${u.upload.objectPath}.encrypted").delete()
        }
        emit(CoreEvent.SessionsChanged)
    }

    override fun markUploadFailed(id: String, message: String) {
        synchronized(lock) {
            val u = uploads.firstOrNull { it.upload.payloadId == id } ?: throw CoreException.UploadState("unknown upload $id")
            u.failed = true
            lastUploadError = message
        }
    }

    // ---- CoreStorage ----

    override suspend fun storageStats(): StorageStats = query {
        val count = sessions.count(::hasData)
        StorageStats(databaseBytes = 2_000_000L + 180_000L * count, rawFilesBytes = 4_000_000L * count,
            pendingCount = uploads.size, pendingBytes = uploads.sumOf { it.upload.sizeBytes }, sessionCount = count)
    }

    override suspend fun prepareExport(scope: Scope, format: ExportFormat): PreparedExport = query { now ->
        val resolved = resolve(scope, now).map { it.id }
        val id = uuid7(now)
        val label = if (scope.sessions == SessionScope.All) "all" else resolved.joinToString("-")
        val ext = when (format) { ExportFormat.ParquetRaw -> "parquet"; ExportFormat.Csv -> "csv"; ExportFormat.SqliteSnapshot -> "sqlite" }
        val name = "cluetooth-$label-$ext.zip"
        val file = File(tempRoot, "export-temp/$id/$name")
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"$format","sessions":${resolved.joinToString(",", "[", "]") { Geo.str(it) }},"fake":true}""".toByteArray())
            zip.closeEntry()
        }
        PreparedExport(id, file.path, name, 1, file.length())
    }

    override suspend fun acknowledgeExport(id: String) { File(tempRoot, "export-temp/$id").deleteRecursively() }

    override suspend fun deleteLocalData(scope: Scope, includeUnuploaded: Boolean): DeleteOutcome {
        val outcome = query { now ->
            val l = rec
            val ids = resolve(scope, now).map { it.id }.toSet()
            if (l != null && (l.id in ids || scope.sessions == SessionScope.All)) {
                return@query DeleteOutcome.Refused("recording")
            }
            val removed = sessions.filter { it.id in ids || scope.sessions == SessionScope.All }
            sessions.removeAll(removed.toSet())
            val pending = uploads.filter { it.sessionId in removed.map(Sess::id) }
            if (includeUnuploaded) uploads.removeAll(pending.toSet())
            if (!includeUnuploaded && pending.isNotEmpty()) DeleteOutcome.DeletedPendingKept(pending.size)
            else DeleteOutcome.Deleted(removed.size)
        }
        if (outcome !is DeleteOutcome.Refused) {
            emit(CoreEvent.SessionsChanged)
            emit(CoreEvent.DataInvalidated("delete"))
        }
        return outcome
    }

    override suspend fun reclaimable(): CoreResult<Reclaimable> =
        CoreResult.Ok(query { Reclaimable(freeBytes, uploadedRawBytes, FAKE_SESSION_DB_BYTES * sessions.size) })

    override suspend fun deleteUploadedRaw(): CoreResult<Long> = CoreResult.Ok(query {
        val freed = uploadedRawBytes
        uploadedRawBytes = 0
        freeBytes += freed
        freed
    })

    /** Every finished session goes; the current recording and the pending uploads stay. */
    override suspend fun clearLocalDatabase(): CoreResult<Long> {
        val freed = query {
            val freed = FAKE_SESSION_DB_BYTES * sessions.size
            sessions.clear()
            freeBytes += freed
            freed
        }
        emit(CoreEvent.SessionsChanged)
        emit(CoreEvent.DataInvalidated("local database cleared"))
        return CoreResult.Ok(freed)
    }

    /**
     * SPEC R-10 for previews and tests: the free space drops to [free] and a recording pauses for storage, as the
     * real core does below its 200 MB floor.
     */
    fun simulateStorageFull(free: Long = 150_000_000L) {
        val (delta, state) = synchronized(lock) {
            freeBytes = free
            val l = rec ?: return
            if (l.phase != RecordingPhase.Recording) return
            val now = now()
            l.activeBeforeMs += now - (l.runningSinceMs ?: now)
            l.runningSinceMs = null
            l.phase = RecordingPhase.Paused
            l.closeRun(now)
            l.pausedForStorage = true
            buildDelta(l, now, force = true) to recordingState(l, now)
        }
        liveJob?.cancel()
        scope.launch { delta?.let { liveFlow.emit(it) } }
        emit(CoreEvent.RecordingChanged(state))
        emit(CoreEvent.StorageFull(free, 0))
    }

    /** Stops the simulator (tests and gallery teardown). */
    fun close() {
        liveJob?.cancel()
        tempRoot.deleteRecursively()
    }
}
