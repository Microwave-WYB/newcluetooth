package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.V3CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStartResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattUiState as EngineGattState
import edu.ucsd.sysnet.cluetoothscanner.gatt.startGattDiscovery
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataStatus
import edu.ucsd.sysnet.cluetoothscanner.local.CategoryProducts
import edu.ucsd.sysnet.cluetoothscanner.local.InRangeSnapshot
import edu.ucsd.sysnet.cluetoothscanner.local.GeoBounds as LocalBounds
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataHub
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCatalog
import edu.ucsd.sysnet.cluetoothscanner.local.RecordedDevice
import edu.ucsd.sysnet.cluetoothscanner.local.Sighting
import edu.ucsd.sysnet.cluetoothscanner.service.LiveScan
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Notices
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingOutcome
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDistance
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattPresenter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.NearbyMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ScanOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.estimatePosition
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.toRecorderUi
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AddressType
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ChartWindow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceSort
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.WikiUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.SavedRecording
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Per-map interaction state: sort, search, heatmap layer, panning. Reset on every navigation. */
data class MapLocal(
    val sort: DeviceSort = DeviceSort.Signal,
    val searching: Boolean = false,
    val query: String = "",
    val heat: Boolean = false,
    val panned: Boolean = false,
    val areaOnly: Boolean = false,
    val viewport: GeoBounds? = null,
)

/** What the stop snackbar reports, and which recording it is about. */
data class SavedInfo(val summary: SavedRecording, val recordingId: String?)

/**
 * Everything the v3 UI shows, built from the live scan stream, the recording controller, the
 * GATT manager and the local stores. Screens read the state flows and call the functions; nothing
 * here touches Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V3ViewModel(private val app: V3CluetoothApplication) : ViewModel() {
    private val localData = app.localData
    private val hub = app.localData as? LocalDataHub
    private val recording = app.recordingController
    private val coordinator = recording.coordinator
    private val gatt = app.gattManager

    /** Wiki pages per (address, payload), so the detail page does not run the matcher every second. */
    private val matchCache = object : LinkedHashMap<Long, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<String>>) = size > MATCH_CACHE_SIZE
    }

    private fun pagesFor(addr: String, raw: ByteArray): List<String> {
        val key = (addr.hashCode().toLong() shl 32) xor (raw.contentHashCode().toLong() and 0xFFFFFFFFL)
        synchronized(matchCache) { matchCache[key]?.let { return it } }
        val pages = hub?.identify(raw, addr).orEmpty()
        synchronized(matchCache) { matchCache[key] = pages }
        return pages
    }

    // ---- navigation -----------------------------------------------------------------------

    private val _stack = MutableStateFlow<List<Route>>(listOf(Route.Tab(AppTab.Scan)))
    val stack: StateFlow<List<Route>> = _stack

    private fun resetPageState() {
        _selected.value = null
        _mapLocal.value = MapLocal()
        _controlBar.value = false
    }

    fun open(route: Route) {
        resetPageState()
        _stack.update { it + route }
    }

    /** False when already at the root. */
    fun back(): Boolean {
        if (_stack.value.size <= 1) return false
        resetPageState()
        _stack.update { it.dropLast(1) }
        return true
    }

    fun selectTab(tab: AppTab) {
        resetPageState()
        _stack.value = listOf(Route.Tab(tab))
    }

    // ---- recorder -------------------------------------------------------------------------

    val recorder: StateFlow<RecorderUiState> =
        recording.state.map { it.toRecorderUi() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecorderUiState())

    private val _controlBar = MutableStateFlow(false)
    val controlBarOpen: StateFlow<Boolean> = _controlBar
    fun setControlBar(open: Boolean) { _controlBar.value = open }

    val recorderCallbacks = RecorderCallbacks(
        onStart = { startRecording() },
        onPause = { recording.pause() },
        onResume = { recording.resume() },
        onStop = { recording.stop(); _controlBar.value = false },
        onOpenControls = { _controlBar.value = !_controlBar.value },
    )

    /** Starting while the last recording could not be sealed does nothing in the core; say why and retry the seal. */
    private fun startRecording() {
        if (coordinator.state.value == ScanTransitionState.FINALIZATION_FAILED) {
            toasts.trySend("上一次录制还没有保存成功，正在重试保存；保存好后再开始新的录制")
            recording.stop()
            return
        }
        recording.start()
    }

    /** A recording that could not be saved or started, with a retry; null when all is well. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem
    fun retrySave() { recording.stop() }
    fun dismissProblem() { _problem.value = null }

    private val _saved = MutableStateFlow<SavedInfo?>(null)
    val saved: StateFlow<SavedInfo?> = _saved
    private var savedJob: Job? = null
    private var pendingRecordingId: String? = null

    private val _noteTarget = MutableStateFlow<String?>(null)
    val noteTarget: StateFlow<String?> = _noteTarget

    /**
     * The note being edited, read through the store's own flow. Null until it has emitted, so the editor
     * never starts from a placeholder "" that only means the database is not there yet.
     */
    val noteText: StateFlow<String?> = _noteTarget
        .flatMapLatest<String?, String?> { id -> if (id == null) flowOf(null) else localData.observeRecordingNote(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** False while the local database is unavailable: the editor is then read-only, as saving would fail. */
    val noteEditable: StateFlow<Boolean> = localData.status.map { it.state != LocalDataStatus.State.UNAVAILABLE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun addNote(recordingId: String?) { if (recordingId != null) _noteTarget.value = recordingId }
    fun dismissNote() { _noteTarget.value = null }
    fun saveNote(text: String) {
        val id = _noteTarget.value ?: return
        viewModelScope.launch {
            try {
                localData.setRecordingNote(id, text)
                _noteTarget.value = null
            } catch (error: IllegalStateException) {
                // The store refuses rather than losing the note; keep the editor open.
                toasts.trySend("备注没有保存：本地数据库不可用")
            }
        }
    }

    fun dismissSaved() { _saved.value = null }

    val toasts = Channel<String>(Channel.BUFFERED)
    val toastFlow: Flow<String> = toasts.receiveAsFlow()

    // ---- live scan ------------------------------------------------------------------------

    private val lock = Any()
    private val tracker = LiveDeviceTracker()
    private val order = ScanOrder()
    private val _scanRows = MutableStateFlow<List<DeviceRowState>>(emptyList())
    private val _tick = MutableStateFlow(System.currentTimeMillis())
    private val _refreshing = MutableStateFlow(false)

    /** Bumped whenever the list was re-sorted, so the page can scroll back to the top. */
    private val _sortVersion = MutableStateFlow(0)
    val sortVersion: StateFlow<Int> = _sortVersion
    val refreshing: StateFlow<Boolean> = _refreshing

    val scanState: StateFlow<ScanUiState> = combine(_scanRows, recorder) { rows, rec -> ScanUiState(rows, rec) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState(emptyList(), RecorderUiState()))

    // ---- recording lifecycle state (read by onTransition) ----
    private var previousTransition = ScanTransitionState.STOPPED
    private var recordingReached = false

    /** This ViewModel saw the start begin; a stop it only joined midway (a new ViewModel during sealing) is not a failed start. */
    private var startSeen = false
    private var pendingSaved: SavedRecording? = null

    private val gattResults = MutableStateFlow<Map<String, GattUiState.Done>>(emptyMap())

    /** The UI is on screen (STARTED). Live collection and the one-second publish loop run only then. */
    private val uiActive = MutableStateFlow(false)
    fun setUiActive(active: Boolean) { uiActive.value = active }

    /** The scan, data and location problems in one line under the page header; null when all is well. */
    val headerNotice: StateFlow<String?> = combine(
        app.bleScanService.scanStatus,
        recording.state.map { it.scannerDeferred to (it.mode != RecordingMode.IDLE) }.distinctUntilChanged(),
        localData.status,
        app.locationService.currentLocation,
        _tick,
    ) { status, (deferred, recordingNow), data, fix, now ->
        (if (deferred) Notices.RECORDING_WAITS_FOR_BLUETOOTH else Notices.scanner(status.state))
            ?: Notices.data(data.state)
            ?: Notices.location(recordingNow, fix?.let { now - it.time })
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun LiveScan.toSample() = LiveSample(
        addr = addr, rssi = rssi, timeMs = scannedAtMs, raw = raw, localName = localName,
        connectable = isConnectable, legacy = v3.isLegacy, primaryPhy = v3.primaryPhy,
        secondaryPhy = v3.secondaryPhy, advSid = v3.advSid, addrType = v3.addrType,
    )

    init {
        // The stores are fed by V3CluetoothApplication; this is only the on-screen list.
        viewModelScope.launch(Dispatchers.Default) {
            uiActive.collectLatest { active ->
                if (active) {
                    app.bleScanService.liveScans.collect { scan ->
                        val now = System.currentTimeMillis()
                        synchronized(lock) {
                            if (tracker.onSample(scan.toSample())) order.onDevice(scan.addr, now)
                        }
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            uiActive.collectLatest { active ->
                if (active) {
                    while (isActive) {
                        publish()
                        delay(1_000)
                    }
                }
            }
        }
        viewModelScope.launch {
            recording.state.map { it.error }.distinctUntilChanged().collect { error ->
                if (error != null) toasts.trySend("录制：$error")
            }
        }
        viewModelScope.launch { coordinator.state.collect(::onTransition) }
        viewModelScope.launch {
            gatt.state.collect { g ->
                if (g is EngineGattState.Finished) {
                    val key = g.result.request.target.addr
                    gattResults.update { m -> (m - key + (key to GattPresenter.finished(g.result))).let { if (it.size > MAX_GATT_RESULTS) it.entries.drop(1).associate { e -> e.key to e.value } else it } }
                }
            }
        }
    }

    // The recording's life, seen from the coordinator: the snackbar appears only once sealing succeeded.
    private fun onTransition(state: ScanTransitionState) {
        val previous = previousTransition
        previousTransition = state
        when (state) {
            ScanTransitionState.SCANNING, ScanTransitionState.DEFERRED, ScanTransitionState.PAUSED -> if (!recordingReached) {
                startSeen = false
                recordingReached = true
                pendingSaved = null
                _problem.value = null
                captureRecordingId()
            }
            ScanTransitionState.STOPPED -> {
                val rec = recording.state.value
                val summary = pendingSaved ?: if (recordingReached) SavedRecording((rec.elapsedMs / 1000).toInt(), rec.rowCount.toInt()) else null
                recordingReached = false
                pendingSaved = null
                _problem.value = null
                val startFailed = startSeen
                startSeen = false
                when (RecordingOutcome.atStop(previous, startFailed, summary != null)) {
                    RecordingOutcome.Kind.Saved -> showSaved(summary!!)
                    RecordingOutcome.Kind.StartFailed -> toasts.trySend("无法开始录制：本地存储还没有准备好")
                    RecordingOutcome.Kind.ScannerRejected -> toasts.trySend("扫描无法启动，请检查蓝牙和权限")
                    RecordingOutcome.Kind.None -> Unit
                }
            }
            ScanTransitionState.FINALIZATION_FAILED -> {
                val rec = recording.state.value
                if (recordingReached) pendingSaved = SavedRecording((rec.elapsedMs / 1000).toInt(), rec.rowCount.toInt())
                recordingReached = false
                _problem.value = "这次录制没有保存成功，数据还在手机上。"
            }
            ScanTransitionState.STARTING -> startSeen = true
            ScanTransitionState.FINALIZING -> Unit
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        var sorted = false
        val rows = synchronized(lock) {
            order.remove(tracker.prune(now))
            sorted = order.tick(now, tracker::rssi) { tracker.isStale(it, now) }
            order.addrs.mapNotNull { tracker.row(it, now) }
        }
        if (sorted) _sortVersion.update { it + 1 }
        _scanRows.value = rows
        _tick.value = now
    }

    /** Pull to refresh: sort the whole list by the current signal, once. */
    fun refreshScan() {
        viewModelScope.launch {
            _refreshing.value = true
            withContextDefault { synchronized(lock) { val now = System.currentTimeMillis(); order.resort(tracker::rssi) { tracker.isStale(it, now) } } }
            publish()
            _sortVersion.update { it + 1 }
            delay(500)
            _refreshing.value = false
        }
    }

    private suspend fun <T> withContextDefault(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.Default) { block() }

    private fun captureRecordingId() {
        pendingRecordingId = null
        viewModelScope.launch {
            repeat(30) {
                app.repository.activeSessionId()?.let { pendingRecordingId = it; return@launch }
                delay(200)
            }
        }
    }

    private fun showSaved(summary: SavedRecording) {
        val info = SavedInfo(summary, pendingRecordingId)
        _saved.value = info
        savedJob?.cancel()
        savedJob = viewModelScope.launch {
            delay(8_000)
            if (_saved.value === info) _saved.value = null
        }
    }

    fun viewSaved() {
        val id = _saved.value?.recordingId ?: return
        _saved.value = null
        selectTab(AppTab.Recordings)
        open(Route.RecordingMap(id))
    }

    // ---- device detail --------------------------------------------------------------------

    private val _selected = MutableStateFlow<String?>(null)
    val selectedAddr: StateFlow<String?> = _selected
    private val _window = MutableStateFlow(ChartWindow.FiveMinutes)

    fun select(addr: String?) { _selected.value = addr }
    fun setWindow(w: ChartWindow) { _window.value = w }

    private val selectedHistory: Flow<List<Sighting>> =
        _selected.flatMapLatest { a -> if (a == null) flowOf(emptyList()) else localData.sightingsOfDevice(a, 600) }

    /** Built on the default dispatcher: it reads the tracker, parses and matches advertisements once a second. */
    val detail: StateFlow<DeviceDetailUiState?> = combine(
        combine(_selected, _window, _tick) { addr, window, now -> Triple(addr, window, now) },
        combine(gatt.state, gattResults, selectedHistory) { g, saved, history -> Triple(g, saved, history) },
    ) { (addr, window, now), (g, saved, history) ->
        addr?.let { buildDetail(it, window, now, g, saved, history) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The detail of the selected device, or null; collected by the maps only while something is selected. */
    private val selectedDetail: Flow<DeviceDetailUiState?> = _selected.flatMapLatest { a -> if (a == null) flowOf(null) else detail }

    private fun buildDetail(
        addr: String,
        window: ChartWindow,
        now: Long,
        g: EngineGattState,
        saved: Map<String, GattUiState.Done>,
        history: List<Sighting>,
    ): DeviceDetailUiState {
        val catalog = localData.catalog.value
        val (live, variants, chart, raw) = synchronized(lock) {
            Quad(
                tracker.row(addr, now),
                tracker.variants(addr),
                tracker.chart(addr, window, now),
                tracker.latestAdvertisement(addr),
            )
        }
        val pageIds = if (raw != null) pagesFor(addr, raw) else history.firstOrNull()?.pageIds.orEmpty()
        val page = pageIds.firstNotNullOfOrNull { catalog?.page(it) }
        val row = live ?: DeviceRowState(
            id = addr, name = page?.title, mac = addr, addressType = AddressType.Unknown, advKind = AdvKind.Legacy,
            connectable = false, rssi = history.firstNotNullOfOrNull { it.bestRssi } ?: -100, history = emptyList(), stale = true,
        )
        val samples: List<Int?> = if (live != null) chart else history.mapNotNull { it.bestRssi }.reversed().takeLast(120)
        val engine = gattFor(addr, g, saved)
        val busyElsewhere = g is EngineGattState.Running && g.request.target.addr != addr
        val reason = when {
            engine is GattUiState.InProgress -> "正在发现 GATT"
            live == null -> "设备不在范围内，无法发现 GATT"
            row.stale -> "这台设备已有一阵子没有收到广播，无法发现 GATT"
            !row.connectable -> "该设备的广播不可连接，无法发现 GATT"
            busyElsewhere -> "正在发现另一台设备的 GATT"
            else -> null
        }
        return DeviceDetailUiState(
            device = row,
            variants = variants,
            chartWindow = window,
            chartSamples = samples,
            gatt = engine,
            wiki = page?.let {
                WikiUiState(
                    family = it.title,
                    summary = it.oneLine,
                    facts = buildList {
                        add("条目" to it.id)
                        if (it.categories.isNotEmpty()) add("类别" to it.categories.joinToString("、") { c -> catalog?.category(c)?.labelZh ?: c })
                        add("类型" to when (it.kind) {
                            edu.ucsd.sysnet.cluetoothscanner.local.PageKind.DEVICE -> "设备"
                            edu.ucsd.sysnet.cluetoothscanner.local.PageKind.PLATFORM -> "平台"
                            edu.ucsd.sysnet.cluetoothscanner.local.PageKind.IDENTIFIER -> "标识"
                        })
                    },
                    confidence = "候选，不是已认证的身份",
                )
            },
            gattDisabledReason = reason,
        )
    }

    /** At most [HEAT_MAX_POINTS] distinct spots (about one metre apart), spread over the whole list. */
    private fun thinForHeat(points: List<LatLon>): List<LatLon> {
        val distinct = LinkedHashMap<Long, LatLon>()
        points.forEach { p -> distinct.putIfAbsent((Math.round(p.lat * 1e5) shl 32) xor Math.round(p.lon * 1e5), p) }
        val all = distinct.values.toList()
        if (all.size <= HEAT_MAX_POINTS) return all
        val step = all.size.toDouble() / HEAT_MAX_POINTS
        return List(HEAT_MAX_POINTS) { all[(it * step).toInt()] }
    }

    private companion object {
        const val HEAT_MAX_POINTS = 4_000
        const val DEMO_PREFIX = "demo:"
        const val MATCH_CACHE_SIZE = 256
        const val MAX_GATT_RESULTS = 20
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** This device's discovery in flight, else the last result kept for it, else nothing. */
    private fun gattFor(addr: String, g: EngineGattState, saved: Map<String, GattUiState.Done>): GattUiState {
        if (g is EngineGattState.Running && g.request.target.addr == addr) return GattPresenter.running(g.progress)
        return saved[addr] ?: GattUiState.NotStarted
    }

    fun discoverGatt() {
        val addr = _selected.value ?: return
        val result = startGattDiscovery(
            context = app,
            manager = gatt,
            triggers = app.triggerCache,
            repository = app.repository,
            addr = addr,
            location = app.locationService.getLastKnownLocation(),
        )
        when (result) {
            GattStartResult.Started -> Unit
            GattStartResult.Busy -> toasts.trySend("已有一个 GATT 发现在进行")
            GattStartResult.PermissionMissing -> toasts.trySend("没有蓝牙连接权限")
            GattStartResult.BluetoothUnavailable -> toasts.trySend("蓝牙不可用")
        }
    }

    fun cancelGatt() = gatt.cancel()

    fun showSelectedOnMap() {
        val addr = _selected.value ?: return
        open(Route.DeviceMap(addr))
    }

    // ---- nearby ---------------------------------------------------------------------------

    val nearby: StateFlow<NearbyUiState> = combine(
        combine(recorder, localData.productsInRange(), localData.previouslySeenProducts(), localData.catalog) { rec, inRange, seen, catalog ->
            listOf(rec, inRange, seen, catalog)
        },
        localData.unknownStructuresInRange(),
        localData.previouslySeenUnknownStructures(),
    ) { products, unknownNow, unknownSeen ->
        @Suppress("UNCHECKED_CAST")
        NearbyMapper.map(
            products[0] as RecorderUiState, products[1] as InRangeSnapshot, products[2] as List<CategoryProducts>,
            products[3] as WikiCatalog?, unknownNow = unknownNow, unknownSeen = unknownSeen,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyMapper.map(RecorderUiState(), null, emptyList(), null))

    // ---- recordings -----------------------------------------------------------------------

    /** Every recording's note, each through the store's own observe flow. */
    private val allNotes: Flow<Map<String, String>> = localData.recordings().flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyMap())
        else combine(list.map { r -> localData.observeRecordingNote(r.id).map { r.id to it } }) { pairs -> pairs.toMap() }
    }

    val recordings: StateFlow<RecordingsUiState> = combine(
        recorder,
        localData.recordings(),
        app.repository.uiState.map { s -> if (s.coreReady) s.sessions.associate { it.sessionId to it.uploadState } else emptyMap() }.distinctUntilChanged(),
        allNotes,
    ) { rec, list, uploads, noteMap -> RecordingMapper.state(rec, list, uploads, noteMap) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState(RecorderUiState(), null, emptyList()))

    // ---- maps -----------------------------------------------------------------------------

    private val _mapLocal = MutableStateFlow(MapLocal())

    fun cycleSort() = _mapLocal.update { it.copy(sort = DeviceSort.entries[(it.sort.ordinal + 1) % DeviceSort.entries.size]) }
    fun toggleSearch() = _mapLocal.update { it.copy(searching = !it.searching, query = "") }
    fun setQuery(q: String) = _mapLocal.update { it.copy(query = q) }
    fun toggleHeat() = _mapLocal.update { it.copy(heat = !it.heat) }
    fun toggleArea() = _mapLocal.update { it.copy(areaOnly = !it.areaOnly) }
    fun onMapPanned() = _mapLocal.update { if (it.panned) it else it.copy(panned = true) }
    fun onViewport(bounds: GeoBounds) = _mapLocal.update { it.copy(viewport = bounds) }

    val mapState: StateFlow<MapUiState?> = _stack.map { it.last() }.distinctUntilChanged().flatMapLatest { route ->
        when (route) {
            is Route.RecordingMap -> recordingMap(route.id)
            is Route.ProductMap ->
                if (route.pageId.startsWith(DEMO_PREFIX)) demoMap(route.pageId.removePrefix(DEMO_PREFIX))
                else sightingMap(localData.sightingsOfProduct(route.pageId, 2_000), productTitle = route.pageId)
            is Route.DeviceMap -> sightingMap(localData.sightingsOfDevice(route.addr, 2_000), deviceAddr = route.addr)
            else -> flowOf(null)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun md(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    /** [areaAddrs] (when the filter is on and the store answered) replaces the per-row location test. */
    private fun List<MapListRowState>.arranged(local: MapLocal, areaAddrs: Set<String>? = null): List<MapListRowState> {
        val q = local.query.trim().lowercase()
        val searched = if (q.isEmpty()) this else filter { (it.title + " " + (it.mac ?: "") + " " + it.detail).lowercase().contains(q) }
        val inArea = when {
            areaAddrs != null -> searched.filter { it.mac in areaAddrs }
            local.areaOnly && local.viewport != null -> searched.filter { r -> r.at?.let { local.viewport.contains(it.lat, it.lon) } ?: false }
            else -> searched
        }
        return when (local.sort) {
            DeviceSort.Signal -> inArea.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            DeviceSort.Recent -> inArea.sortedByDescending { it.lastSeenMs }
            DeviceSort.Name -> inArea.sortedWith(compareBy({ it.title == "未命名" }, { it.title.lowercase() }, { it.mac }))
        }
    }

    private fun titleFor(addr: String, pageIds: List<String>): String {
        val live = synchronized(lock) { tracker.name(addr) }
        if (live != null) return live
        val catalog = localData.catalog.value
        return pageIds.firstNotNullOfOrNull { catalog?.page(it)?.title } ?: "未命名"
    }

    private fun recordingMap(id: String): Flow<MapUiState?> {
        val data = combine(localData.recording(id), localData.route(id), localData.devicesInRecording(id), localData.catalog) { r, route, devices, catalog ->
            Triple(r, route, devices) to catalog
        }
        // Recording-wide sightings are heavy: only collected while the density layer or the viewport filter needs them.
        val heatFlow = _mapLocal.map { it.heat }.distinctUntilChanged().flatMapLatest { on ->
            if (on) localData.sightingsInRecording(id) else flowOf(emptyList())
        }
        val areaFlow: Flow<Set<String>?> = _mapLocal.map { if (it.areaOnly) it.viewport else null }.distinctUntilChanged().flatMapLatest { box ->
            if (box == null) flowOf(null)
            else localData.sightingsInRecording(id, LocalBounds(box.south, box.west, box.north, box.east)).map { list -> list.mapTo(HashSet()) { it.addr } }
        }
        val extras = combine(selectedDetail, heatFlow, areaFlow) { det, heatRows, area -> Triple(det, heatRows, area) }
        return combine(data, selectedHistory, _selected, _mapLocal, extras) { d, history, selected, local, (det, heatRows, areaAddrs) ->
            val (r, route, devices) = d.first
            if (r == null) return@combine null
            val routePts = route.map { LatLon(it.lat, it.lon) }
            val rows = devices.map { dev: RecordedDevice ->
                MapListRowState(
                    id = dev.addr,
                    title = titleFor(dev.addr, dev.pageIds),
                    mac = dev.addr,
                    detail = "${dev.minuteCount} 分钟",
                    rssi = dev.strongestRssi,
                    lastSeenMs = dev.lastSeenMs,
                    at = dev.bestLocation?.let { LatLon(it.lat, it.lon) },
                )
            }.arranged(local, areaAddrs)
            val points = history.filter { it.recordingId == id }.mapNotNull { s -> s.location?.let { SightingPoint(LatLon(it.lat, it.lon), s.bestRssi ?: -90) } }
            val estimate = if (selected != null) estimatePosition(points) else null
            val heat = thinForHeat(heatRows.mapNotNull { s -> s.location?.let { LatLon(it.lat, it.lon) } })
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.startedAtMs), ZoneId.systemDefault())
            MapUiState(
                mode = MapMode.Recording,
                title = "${start.monthValue}月${start.dayOfMonth}日 ${"%02d:%02d".format(start.hour, start.minute)}" + if (r.active) " · 录制中" else "",
                subtitle = "${formatDuration((r.durationMs / 1000).toInt())} · ${formatDistance(r.distanceMeters.toInt())} · ${r.deviceCount} 台设备",
                route = routePts,
                rows = rows,
                sort = local.sort, query = local.query, searching = local.searching,
                productPoints = emptyList(),
                selected = if (selected != null) det else null,
                selectedPoints = if (selected != null) points else emptyList(),
                estimate = estimate?.at,
                estimateAccuracyMeters = estimate?.radiusMeters,
                showAreaChip = local.panned,
                areaOnly = local.areaOnly,
                heatmap = local.heat,
                heat = if (local.heat) heat else emptyList(),
                emptyNote = if (routePts.isEmpty() && devices.all { it.bestLocation == null }) "这次录制没有位置信息" else null,
            )
        }
    }

    /** The product map (one wiki page) and the device map (one address) share this. */
    private fun sightingMap(sightings: Flow<List<Sighting>>, productTitle: String? = null, deviceAddr: String? = null): Flow<MapUiState?> =
        combine(sightings, localData.catalog, _selected, _mapLocal, selectedDetail) { all, catalog, selected, local, det ->
            val located = all.filter { it.location != null }
            fun point(s: Sighting) = SightingPoint(LatLon(s.location!!.lat, s.location.lon), s.bestRssi ?: -90)
            val rows = all.groupBy { it.addr }.map { (addr, list) ->
                val last = list.maxOf { it.minuteMs }
                MapListRowState(
                    id = addr,
                    title = if (productTitle != null) catalog?.page(productTitle)?.title ?: productTitle else titleFor(addr, list.first().pageIds),
                    mac = addr,
                    detail = "${list.map { it.recordingId }.distinct().size} 次录制 · ${md(last)}",
                    rssi = list.mapNotNull { it.bestRssi }.maxOrNull(),
                    lastSeenMs = last,
                    at = list.firstOrNull { it.location != null }?.location?.let { LatLon(it.lat, it.lon) },
                )
            }.arranged(local)
            val selectedPoints = if (selected != null) located.filter { it.addr == selected }.map(::point) else emptyList()
            val all2 = located.map(::point)
            val estimate = if (selected != null) estimatePosition(selectedPoints) else null
            val title = productTitle?.let { catalog?.page(it)?.title ?: it } ?: deviceAddr.orEmpty()
            val macs = rows.size
            val recordingsCount = all.map { it.recordingId }.distinct().size
            MapUiState(
                mode = MapMode.Product,
                title = title,
                subtitle = if (productTitle != null) "$macs 个 MAC · 来自 $recordingsCount 次录制" else "来自 $recordingsCount 次录制 · ${all.size} 个分钟记录",
                route = emptyList(),
                rows = rows,
                sort = local.sort, query = local.query, searching = local.searching,
                productPoints = all2,
                selected = if (selected != null) det else null,
                selectedPoints = selectedPoints,
                estimate = estimate?.at,
                estimateAccuracyMeters = estimate?.radiusMeters,
                showAreaChip = local.panned,
                areaOnly = local.areaOnly,
                heatmap = local.heat,
                heat = if (local.heat) all2.map { it.at } else emptyList(),
                emptyNote = if (located.isEmpty()) "还没有带位置的记录" else null,
            )
        }

    /** Debug builds only: `DebugDemoMaps` (debug source set) supplies fake map data; release has no such class. */
    private fun demoMap(variant: String): Flow<MapUiState?> = _mapLocal.map { local ->
        runCatching {
            val demo = Class.forName("edu.ucsd.sysnet.cluetoothscanner.ui.v3.app.DebugDemoMaps")
            demo.getMethod("state", String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(null, variant, local.heat, local.panned) as MapUiState
        }.getOrNull()
    }

    fun openDemoMap(variant: String) {
        _stack.value = listOf(Route.Tab(AppTab.Scan), Route.ProductMap(DEMO_PREFIX + variant))
    }

    /** Opens the product map for a Nearby tile; the "unidentified" tile has no map. */
    fun openProduct(pageId: String) {
        if (pageId.startsWith(NearbyMapper.UNKNOWN_PREFIX)) return
        open(Route.ProductMap(pageId))
    }

    override fun onCleared() {
        toasts.close()
        super.onCleared()
    }
}
