package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStartResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattUiState as EngineGattState
import edu.ucsd.sysnet.cluetoothscanner.gatt.startGattDiscovery
import edu.ucsd.sysnet.cluetoothscanner.local.AdvertisementMatcher
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataSink
import edu.ucsd.sysnet.cluetoothscanner.local.NativeAdvertisementMatcher
import edu.ucsd.sysnet.cluetoothscanner.local.RecordedDevice
import edu.ucsd.sysnet.cluetoothscanner.local.Sighting
import edu.ucsd.sysnet.cluetoothscanner.service.LiveScan
import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState
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
import kotlinx.coroutines.flow.flowOf
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
class V3ViewModel(private val app: CluetoothApplication) : ViewModel() {
    private val localData = app.localData
    private val sink = app.localData as? LocalDataSink
    private val recording = app.recordingController
    private val gatt = app.gattManager
    private val notes = RecordingNotes(app)
    private val matcher: AdvertisementMatcher? = runCatching {
        NativeAdvertisementMatcher.fromBundleJson(app.assets.open("wiki-bundle.json").bufferedReader().use { it.readText() })
    }.getOrNull()

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
        recording.state.map { it.toRecorderUi() }.stateIn(viewModelScope, SharingStarted.Eagerly, RecorderUiState())

    private val _controlBar = MutableStateFlow(false)
    val controlBarOpen: StateFlow<Boolean> = _controlBar
    fun setControlBar(open: Boolean) { _controlBar.value = open }

    val recorderCallbacks = RecorderCallbacks(
        onStart = { recording.start() },
        onPause = { recording.pause() },
        onResume = { recording.resume() },
        onStop = { recording.stop(); _controlBar.value = false },
        onOpenControls = { _controlBar.value = !_controlBar.value },
    )

    private val _saved = MutableStateFlow<SavedInfo?>(null)
    val saved: StateFlow<SavedInfo?> = _saved
    private var savedJob: Job? = null
    private var pendingRecordingId: String? = null

    private val _noteTarget = MutableStateFlow<String?>(null)
    val noteTarget: StateFlow<String?> = _noteTarget
    val noteText: StateFlow<String> = combine(_noteTarget, notes.notes) { id, all -> all[id].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun addNote(recordingId: String?) { if (recordingId != null) _noteTarget.value = recordingId }
    fun dismissNote() { _noteTarget.value = null }
    fun saveNote(text: String) {
        _noteTarget.value?.let { notes.set(it, text) }
        _noteTarget.value = null
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
    val refreshing: StateFlow<Boolean> = _refreshing

    val scanState: StateFlow<ScanUiState> = combine(_scanRows, recorder) { rows, rec -> ScanUiState(rows, rec) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanUiState(emptyList(), RecorderUiState()))

    /** Why scanning is not running, in words; null when it is fine. */
    val scanNotice: StateFlow<String?> = combine(app.bleScanService.scanStatus, recording.state) { status, rec ->
        if (rec.scannerDeferred) return@combine "录制在等待蓝牙恢复"
        when (status.state) {
            ScanOperatingState.BLUETOOTH_OFF -> "蓝牙已关闭，打开后继续扫描"
            ScanOperatingState.SCANNER_UNAVAILABLE -> "蓝牙扫描暂时不可用，正在重试"
            ScanOperatingState.PAUSED_BACKPRESSURE -> "正在保存，稍后自动恢复扫描"
            ScanOperatingState.FAILED -> "无法开始扫描，请检查蓝牙权限"
            ScanOperatingState.SCANNING, ScanOperatingState.STOPPED -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun LiveScan.toSample() = LiveSample(
        addr = addr, rssi = rssi, timeMs = scannedAtMs, raw = raw, localName = localName,
        connectable = isConnectable, legacy = v3.isLegacy, primaryPhy = v3.primaryPhy,
        secondaryPhy = v3.secondaryPhy, advSid = v3.advSid, addrType = v3.addrType,
    )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            app.bleScanService.liveScans.collect { scan ->
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    if (tracker.onSample(scan.toSample())) order.onDevice(scan.addr, now)
                }
                // While rows are written the repository feeds the stores itself.
                if (recorder.value.phase != RecorderPhase.Recording) {
                    sink?.onAdvertisement(scan.addr, scan.rssi, scan.raw, scan.scannedAtMs)
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                publish()
                delay(1_000)
            }
        }
        viewModelScope.launch {
            recording.state.map { it.error }.distinctUntilChanged().collect { error ->
                if (error != null) toasts.trySend("录制：$error")
            }
        }
        viewModelScope.launch {
            var previous = RecorderPhase.Idle
            recorder.collect { r ->
                if (previous == RecorderPhase.Idle && r.phase != RecorderPhase.Idle) captureRecordingId()
                if (previous != RecorderPhase.Idle && r.phase == RecorderPhase.Idle) showSaved(r)
                previous = r.phase
            }
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val rows = synchronized(lock) {
            order.remove(tracker.prune(now))
            order.tick(now, tracker::rssi)
            order.addrs.mapNotNull { tracker.row(it, now) }
        }
        _scanRows.value = rows
        _tick.value = now
    }

    /** Pull to refresh: sort the whole list by the current signal, once. */
    fun refreshScan() {
        viewModelScope.launch {
            _refreshing.value = true
            withContextDefault { synchronized(lock) { order.resort(tracker::rssi) } }
            publish()
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

    private fun showSaved(r: RecorderUiState) {
        val info = SavedInfo(SavedRecording(r.elapsedSeconds, r.sightings), pendingRecordingId)
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

    val detail: StateFlow<DeviceDetailUiState?> =
        combine(_selected, _window, _tick, gatt.state, selectedHistory) { addr, window, now, g, history ->
            addr?.let { buildDetail(it, window, now, g, history) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun buildDetail(addr: String, window: ChartWindow, now: Long, g: EngineGattState, history: List<Sighting>): DeviceDetailUiState {
        val catalog = localData.catalog.value
        val (live, variants, chart, raw) = synchronized(lock) {
            Quad(
                tracker.row(addr, now),
                tracker.variants(addr),
                tracker.chart(addr, window, now),
                tracker.latestAdvertisement(addr),
            )
        }
        val pageIds = if (raw != null) matcher?.match(raw, addr).orEmpty() else history.firstOrNull()?.pageIds.orEmpty()
        val page = pageIds.firstNotNullOfOrNull { catalog?.page(it) }
        val row = live ?: DeviceRowState(
            id = addr, name = page?.title, mac = addr, addressType = AddressType.Unknown, advKind = AdvKind.Legacy,
            connectable = false, rssi = history.firstNotNullOfOrNull { it.bestRssi } ?: -100, history = emptyList(), stale = true,
        )
        val samples = if (live != null) chart else history.mapNotNull { it.bestRssi }.reversed().takeLast(120)
        val engine = gattFor(addr, g)
        val busyElsewhere = g is EngineGattState.Running && g.request.target.addr != addr
        val reason = when {
            engine is GattUiState.InProgress -> "正在发现 GATT"
            live == null -> "设备不在范围内，无法发现 GATT"
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

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun gattFor(addr: String, g: EngineGattState): GattUiState = when (g) {
        EngineGattState.Idle -> GattUiState.NotStarted
        is EngineGattState.Running -> if (g.request.target.addr == addr) GattPresenter.running(g.progress) else GattUiState.NotStarted
        is EngineGattState.Finished -> if (g.result.request.target.addr == addr) GattPresenter.finished(g.result) else GattUiState.NotStarted
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
        recorder,
        localData.productsInRange(),
        localData.previouslySeenProducts(),
        localData.catalog,
    ) { rec, inRange, seen, catalog -> NearbyMapper.map(rec, inRange, seen, catalog) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyMapper.map(RecorderUiState(), null, emptyList(), null))

    // ---- recordings -----------------------------------------------------------------------

    val recordings: StateFlow<RecordingsUiState> = combine(
        recorder,
        localData.recordings(),
        app.repository.uiState.map { s -> s.sessions.associate { it.sessionId to it.uploadState } }.distinctUntilChanged(),
        notes.notes,
    ) { rec, list, uploads, noteMap -> RecordingMapper.state(rec, list, uploads, noteMap) }
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
            is Route.ProductMap -> sightingMap(localData.sightingsOfProduct(route.pageId, 2_000), productTitle = route.pageId)
            is Route.DeviceMap -> sightingMap(localData.sightingsOfDevice(route.addr, 2_000), deviceAddr = route.addr)
            else -> flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun md(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    private fun List<MapListRowState>.arranged(local: MapLocal): List<MapListRowState> {
        val q = local.query.trim().lowercase()
        val searched = if (q.isEmpty()) this else filter { (it.title + " " + (it.mac ?: "") + " " + it.detail).lowercase().contains(q) }
        val inArea = if (local.areaOnly && local.viewport != null) {
            searched.filter { r -> r.at?.let { local.viewport.contains(it.lat, it.lon) } ?: false }
        } else searched
        return when (local.sort) {
            DeviceSort.Signal -> inArea.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            DeviceSort.Recent -> inArea.sortedByDescending { it.lastSeenMs }
            DeviceSort.Name -> inArea.sortedWith(compareBy({ it.title == "未命名" }, { it.title.lowercase() }, { it.mac }))
        }
    }

    private fun titleFor(addr: String, pageIds: List<String>): String {
        val live = synchronized(lock) { tracker.row(addr, System.currentTimeMillis())?.name }
        if (live != null) return live
        val catalog = localData.catalog.value
        return pageIds.firstNotNullOfOrNull { catalog?.page(it)?.title } ?: "未命名"
    }

    private fun recordingMap(id: String): Flow<MapUiState?> {
        val data = combine(localData.recording(id), localData.route(id), localData.devicesInRecording(id), localData.catalog) { r, route, devices, catalog ->
            Triple(r, route, devices) to catalog
        }
        return combine(data, selectedHistory, _selected, _mapLocal, combine(recorder, detail) { a, b -> a to b }) { d, history, selected, local, rd ->
            val (r, route, devices) = d.first
            val (rec, det) = rd
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
            }.arranged(local)
            val points = history.filter { it.recordingId == id }.mapNotNull { s -> s.location?.let { SightingPoint(LatLon(it.lat, it.lon), s.bestRssi ?: -90) } }
            val estimate = if (selected != null) estimatePosition(points) else null
            val heat = devices.mapNotNull { it.bestLocation }.map { LatLon(it.lat, it.lon) } + routePts
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.startedAtMs), ZoneId.systemDefault())
            MapUiState(
                mode = MapMode.Recording,
                title = "${start.monthValue}月${start.dayOfMonth}日 ${"%02d:%02d".format(start.hour, start.minute)}" + if (r.active) " · 录制中" else "",
                subtitle = "${formatDuration((r.durationMs / 1000).toInt())} · ${formatDistance(r.distanceMeters.toInt())} · ${r.deviceCount} 台设备",
                recorder = rec,
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
                emptyNote = if (routePts.isEmpty() && heat.isEmpty()) "这次录制没有位置信息" else null,
            )
        }
    }

    /** The product map (one wiki page) and the device map (one address) share this. */
    private fun sightingMap(sightings: Flow<List<Sighting>>, productTitle: String? = null, deviceAddr: String? = null): Flow<MapUiState?> =
        combine(sightings, localData.catalog, _selected, _mapLocal, combine(recorder, detail) { a, b -> a to b }) { all, catalog, selected, local, rd ->
            val (rec, det) = rd
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
                recorder = rec,
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

    /** Opens the product map for a Nearby tile; the "unidentified" tile has no map. */
    fun openProduct(pageId: String) {
        if (pageId == "unidentified") return
        open(Route.ProductMap(pageId))
    }

    override fun onCleared() {
        toasts.close()
        super.onCleared()
    }
}
