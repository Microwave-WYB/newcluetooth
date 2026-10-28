package edu.ucsd.sysnet.cluetoothscanner.ui.v3.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import edu.ucsd.sysnet.cluetoothscanner.BuildConfig
import edu.ucsd.sysnet.cluetoothscanner.CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ble.AdTraits
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayDeleteResult
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayExportFormat
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayPreparedExport
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStartResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattUiState as EngineGattState
import edu.ucsd.sysnet.cluetoothscanner.gatt.startGattDiscovery
import edu.ucsd.sysnet.cluetoothscanner.local.CategoryProducts
import edu.ucsd.sysnet.cluetoothscanner.local.GeoBounds as LocalBounds
import edu.ucsd.sysnet.cluetoothscanner.local.InRangeSnapshot
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataHub
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataStatus
import edu.ucsd.sysnet.cluetoothscanner.local.RecordedDevice
import edu.ucsd.sysnet.cluetoothscanner.local.Sighting
import edu.ucsd.sysnet.cluetoothscanner.local.StructureKey
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCatalog
import edu.ucsd.sysnet.cluetoothscanner.service.LiveScan
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.service.UploadService
import edu.ucsd.sysnet.cluetoothscanner.service.sealThenUpload
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecorderCallbacks
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDistance
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DiscoverMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DisplayFilter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattPresenter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveDeviceTracker
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSample
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveScanList
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LiveSpot
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.LocationFix
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.NearbyMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Notices
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingOutcome
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.ResourceTexts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RssiSeries
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.SigNumbers
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.StableOrder
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Texts
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.estimatePosition
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.scanLocationOf
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.toRecorderUi
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceDetailUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceSort
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DiscoverMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapMode
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.NearbyUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductHeaderState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ProductItemState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingSummaryState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecordingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanLocation
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.ScanUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SettingsUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import edu.ucsd.sysnet.cluetoothscanner.utils.AppSettings
import edu.ucsd.sysnet.cluetoothscanner.utils.DeviceIdManager
import java.io.File
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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

/**
 * Everything the v3 UI shows, built from the live scan stream, the recording controller, the
 * GATT manager and the local stores. Screens read the state flows and call the functions; nothing
 * here touches Compose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V3ViewModel(
    private val app: CluetoothApplication,
    private val texts: Texts = ResourceTexts(app),
) : ViewModel() {
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
        if (tab == AppTab.Discover && _stack.value.firstOrNull() != Route.Tab(AppTab.Discover)) nearbyOrder.reset(System.currentTimeMillis())
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
            toasts.trySend(texts.string(R.string.record_toast_finalizing))
            recording.stop()
            return
        }
        recording.start()
    }

    // ---- settings ------------------------------------------------------------------------

    private val uploadService = UploadService(app)
    private val settingsRevision = MutableStateFlow(0)

    val settings: StateFlow<SettingsUiState> = combine(settingsRevision, app.repository.uiState) { _, s ->
        val pending = if (s.coreReady) s.sessions.count { it.uploadState == GatewaySessionUploadState.PENDING || it.uploadState == GatewaySessionUploadState.FAILED } else 0
        SettingsUiState(
            userId = DeviceIdManager.getUserId(app),
            autoRecord = AppSettings.isAutoRecord(app),
            wifiOnly = uploadService.isWifiOnlyUpload(),
            codedPhy = AppSettings.isCodedPhyEnabled(app),
            hideUnnamedApple = AppSettings.isHideUnnamedApple(app),
            pendingUploads = pending,
            canUploadNow = pending > 0 || s.activePayloadRows > 0u,
            version = BuildConfig.VERSION_NAME,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** False (and nothing saved) unless [id] follows the user ID rule. */
    fun setUserId(id: String): Boolean {
        if (!DeviceIdManager.isValidUserId(id)) return false
        DeviceIdManager.setUserId(app, id)
        settingsRevision.update { it + 1 }
        return true
    }

    fun setAutoRecord(on: Boolean) { AppSettings.setAutoRecord(app, on); settingsRevision.update { it + 1 } }

    fun setWifiOnly(on: Boolean) { uploadService.setWifiOnlyUpload(on); settingsRevision.update { it + 1 } }

    fun setCodedPhy(on: Boolean) { AppSettings.setCodedPhyEnabled(app, on); settingsRevision.update { it + 1 } }

    /** Display filter only (see DisplayFilter); the screens follow it as soon as it changes. */
    private val hideApple: StateFlow<Boolean> = settingsRevision.map { AppSettings.isHideUnnamedApple(app) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.isHideUnnamedApple(app))

    fun setHideUnnamedApple(on: Boolean) { AppSettings.setHideUnnamedApple(app, on); settingsRevision.update { it + 1 } }

    /** Seal what is buffered, then upload everything pending now (also the History menu's retry). */
    fun uploadNow() {
        viewModelScope.launch {
            sealThenUpload(flush = { app.repository.flushPayloadAndWait() }, enqueueImmediate = uploadService::forceUpload)
            toasts.trySend(texts.string(R.string.settings_upload_started))
        }
    }

    // ---- one recording's actions (History detail menu) ------------------------------------

    private var pendingExport: GatewayPreparedExport? = null
    private val exportRequests = Channel<String>(Channel.CONFLATED)

    /** File names to save, for the page's document picker; [finishExport] gets the chosen place. */
    val exportFileNames: Flow<String> = exportRequests.receiveAsFlow()

    fun exportRecording(id: String, format: GatewayExportFormat) {
        viewModelScope.launch {
            try {
                val prepared = app.repository.prepareSessionExport(id, format)
                pendingExport = prepared
                exportRequests.trySend(prepared.suggestedFileName)
            } catch (e: Exception) {
                toasts.trySend(texts.string(R.string.export_failed, e.message ?: ""))
            }
        }
    }

    /** Copies the prepared file to [uri] (null: the picker was cancelled), then lets the core drop it. */
    fun finishExport(uri: Uri?) {
        val prepared = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (uri != null) {
                    app.contentResolver.openOutputStream(uri)?.use { out -> File(prepared.localPath).inputStream().use { it.copyTo(out) } }
                        ?: error("cannot open the destination")
                    toasts.trySend(texts.string(R.string.export_done))
                }
            } catch (e: Exception) {
                toasts.trySend(texts.string(R.string.export_failed, e.message ?: ""))
            } finally {
                runCatching { app.repository.acknowledgeExport(prepared.exportId) }
            }
        }
    }

    /** Deletes the recording's scan data kept on the phone; the History entry (route, devices) stays. */
    fun deleteRecordingData(id: String) {
        viewModelScope.launch {
            val message = runCatching { app.repository.deleteScanSession(id, allowUnuploadedDataLoss = true) }.fold(
                onSuccess = { r -> texts.string(if (r == GatewayDeleteResult.NOTHING_LOCAL_TO_DELETE) R.string.delete_nothing else R.string.delete_done) },
                onFailure = { texts.string(R.string.delete_failed, it.message ?: "") },
            )
            toasts.trySend(message)
        }
    }

    /** One recording in History (current or finished), for its detail page's menu. */
    fun recordingSummary(id: String): RecordingSummaryState? =
        recordings.value.let { r -> r.current?.takeIf { it.id == id } ?: r.days.firstNotNullOfOrNull { d -> d.recordings.firstOrNull { it.id == id } } }

    /** A recording that could not be saved or started, with a retry; null when all is well. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem
    fun retrySave() { recording.stop() }
    fun dismissProblem() { _problem.value = null }

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
                toasts.trySend(texts.string(R.string.note_toast_not_saved))
            }
        }
    }

    val toasts = Channel<String>(Channel.BUFFERED)
    val toastFlow: Flow<String> = toasts.receiveAsFlow()

    // ---- live scan ------------------------------------------------------------------------

    private val scanList = LiveScanList()
    private val lock = scanList.lock
    private val tracker = scanList.tracker
    /** The rows and the sort they come from, set together (see [ScanUiState.sortVersion]). */
    private val _scanRows = MutableStateFlow(emptyList<DeviceRowState>() to 0)
    private val _tick = MutableStateFlow(System.currentTimeMillis())
    private val _refreshing = MutableStateFlow(false)

    val refreshing: StateFlow<Boolean> = _refreshing

    private val scanLocation: Flow<ScanLocation> = combine(app.locationService.currentLocation, _tick) { fix, now ->
        scanLocationOf(fix?.let { LocationFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time) }, now)
    }.distinctUntilChanged()

    val scanState: StateFlow<ScanUiState> = combine(_scanRows, recorder, scanLocation) { (rows, version), rec, location -> ScanUiState(rows, rec, location, version) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState(emptyList(), RecorderUiState()))

    // ---- recording lifecycle state (read by onTransition) ----
    private var previousTransition = ScanTransitionState.STOPPED
    private var recordingReached = false

    /** This ViewModel saw the start begin; a stop it only joined midway (a new ViewModel during sealing) is not a failed start. */
    private var startSeen = false

    private val gattResults = MutableStateFlow<Map<String, GattUiState.Done>>(emptyMap())

    /** The UI is on screen (STARTED). Live collection and the one-second publish loop run only then. */
    private val uiActive = MutableStateFlow(false)
    fun setUiActive(active: Boolean) { uiActive.value = active }

    /** The scan, data and location problems in one line under the page header; null when all is well. */
    val headerNotice: StateFlow<Int?> = combine(
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
        secondaryPhy = v3.secondaryPhy, advSid = v3.advSid, addrType = v3.addrType, lat = lat, lon = lon,
    )

    init {
        // Names for company ids and UUIDs in the device sheet; read once, before any sheet can open.
        viewModelScope.launch(Dispatchers.IO) { SigNumbers.load(app) }
        // The stores are fed by CluetoothApplication; this is only the on-screen list.
        viewModelScope.launch(Dispatchers.Default) {
            uiActive.collectLatest { active ->
                if (active) {
                    app.bleScanService.liveScans.collect { scan ->
                        scanList.onSample(scan.toSample(), System.currentTimeMillis())
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
                if (error != null) toasts.trySend(texts.string(R.string.record_toast_error, error))
            }
        }
        viewModelScope.launch { coordinator.state.collect(::onTransition) }
        viewModelScope.launch {
            gatt.state.collect { g ->
                if (g is EngineGattState.Finished) {
                    val key = g.result.request.target.addr
                    gattResults.update { m -> (m - key + (key to GattPresenter.finished(g.result, texts))).let { if (it.size > MAX_GATT_RESULTS) it.entries.drop(1).associate { e -> e.key to e.value } else it } }
                }
            }
        }
    }

    // The recording's life, seen from the coordinator. A recording that was sealed says nothing; a failed seal, a start that
    // never ran and a scanner that refused are the cases that tell the user.
    private fun onTransition(state: ScanTransitionState) {
        val previous = previousTransition
        previousTransition = state
        when (state) {
            ScanTransitionState.SCANNING, ScanTransitionState.DEFERRED, ScanTransitionState.PAUSED -> if (!recordingReached) {
                startSeen = false
                recordingReached = true
                _problem.value = null
            }
            ScanTransitionState.STOPPED -> {
                val recorded = recordingReached
                recordingReached = false
                _problem.value = null
                val startFailed = startSeen
                startSeen = false
                when (RecordingOutcome.atStop(previous, startFailed, recorded)) {
                    RecordingOutcome.Kind.StartFailed -> toasts.trySend(texts.string(R.string.record_toast_start_failed))
                    RecordingOutcome.Kind.ScannerRejected -> toasts.trySend(texts.string(R.string.record_toast_scanner_rejected))
                    RecordingOutcome.Kind.None -> Unit
                }
            }
            ScanTransitionState.FINALIZATION_FAILED -> {
                recordingReached = false
                _problem.value = texts.string(R.string.record_problem)
            }
            ScanTransitionState.STARTING -> startSeen = true
            ScanTransitionState.FINALIZING -> Unit
        }
    }

    private fun publish(snapshot: LiveScanList.Snapshot? = null, now: Long = System.currentTimeMillis(), resorted: Boolean = false) {
        val snap = snapshot ?: scanList.publish(now)
        val version = _scanRows.value.second + if (snap.sorted || resorted) 1 else 0
        // The display filter leaves rows out of what is shown (and counted); the list and its order are untouched.
        val rows = if (hideApple.value) synchronized(lock) { snap.rows.filterNot { tracker.isUnnamedApple(it.id) } } else snap.rows
        _scanRows.value = rows to version
        _tick.value = now
    }

    /** Pull to refresh: sort the whole list by the current signal, once. */
    fun refreshScan() {
        viewModelScope.launch {
            _refreshing.value = true
            val now = System.currentTimeMillis()
            publish(withContextDefault { scanList.refresh(now) }, now, resorted = true)
            delay(500)
            _refreshing.value = false
        }
    }

    private suspend fun <T> withContextDefault(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.Default) { block() }

    // ---- device detail --------------------------------------------------------------------

    private val _selected = MutableStateFlow<String?>(null)
    val selectedAddr: StateFlow<String?> = _selected

    fun select(addr: String?) { _selected.value = addr }

    private val selectedHistory: Flow<List<Sighting>> =
        _selected.flatMapLatest { a -> if (a == null) flowOf(emptyList()) else localData.sightingsOfDevice(a, 600) }

    /** Built on the default dispatcher: it reads the tracker, parses and matches advertisements once a second. */
    val detail: StateFlow<DeviceDetailUiState?> = combine(
        combine(_selected, _tick) { addr, now -> addr to now },
        combine(gatt.state, gattResults, selectedHistory) { g, saved, history -> Triple(g, saved, history) },
    ) { (addr, now), (g, saved, history) ->
        addr?.let { buildDetail(it, now, g, saved, history) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The detail of the selected device, or null; collected by the maps only while something is selected. */
    private val selectedDetail: Flow<DeviceDetailUiState?> = _selected.flatMapLatest { a -> if (a == null) flowOf(null) else detail }

    private fun buildDetail(
        addr: String,
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
                tracker.chart(addr, now),
                tracker.latestAdvertisement(addr),
            )
        }
        val pageIds = if (raw != null) pagesFor(addr, raw) else history.firstOrNull()?.pageIds.orEmpty()
        val page = pageIds.firstNotNullOfOrNull { catalog?.page(it) }
        val row = live ?: DeviceRowState(
            id = addr, name = page?.title, mac = addr, advKind = AdvKind.Legacy,
            connectable = false, rssi = history.firstNotNullOfOrNull { it.bestRssi } ?: -100, history = emptyList(), stale = true,
        )
        // Not heard now (a device opened from a map): an empty minute; the maps do not show the chart anyway.
        val series: RssiSeries = chart
        val engine = gattFor(addr, g, saved)
        val busyElsewhere = g is EngineGattState.Running && g.request.target.addr != addr
        val reason = when {
            engine is GattUiState.InProgress -> texts.string(R.string.gatt_reason_in_progress)
            live == null -> texts.string(R.string.gatt_reason_out_of_range)
            row.stale -> texts.string(R.string.gatt_reason_stale)
            !row.connectable -> texts.string(R.string.gatt_reason_not_connectable)
            busyElsewhere -> texts.string(R.string.gatt_reason_busy)
            else -> null
        }
        return DeviceDetailUiState(
            device = row,
            variants = variants,
            chartSamples = series.buckets,
            chartHeardSeconds = series.heardSeconds,
            gatt = engine,
            wikiPageId = page?.id,
            productName = page?.title,
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
        @Volatile var autoRecordDone = false
        const val HEAT_MAX_POINTS = 4_000
        const val DEMO_PREFIX = "demo:"
        const val MATCH_CACHE_SIZE = 2_048
        const val MAX_GATT_RESULTS = 20
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** This device's discovery in flight, else the last result kept for it, else nothing. */
    private fun gattFor(addr: String, g: EngineGattState, saved: Map<String, GattUiState.Done>): GattUiState {
        if (g is EngineGattState.Running && g.request.target.addr == addr) return GattPresenter.running(g.progress, texts)
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
            GattStartResult.Busy -> toasts.trySend(texts.string(R.string.gatt_toast_busy))
            GattStartResult.PermissionMissing -> toasts.trySend(texts.string(R.string.gatt_toast_permission))
            GattStartResult.BluetoothUnavailable -> toasts.trySend(texts.string(R.string.gatt_toast_bluetooth))
        }
    }

    fun cancelGatt() = gatt.cancel()

    fun showSelectedOnMap() {
        val addr = _selected.value ?: return
        open(Route.DeviceMap(addr))
    }

    // ---- discover ------------------------------------------------------------------------

    private val _discoverMode = MutableStateFlow(DiscoverMode.Nearby)
    val discoverMode: StateFlow<DiscoverMode> = _discoverMode
    fun setDiscoverMode(mode: DiscoverMode) {
        if (mode != _discoverMode.value) nearbyOrder.reset(System.currentTimeMillis())
        _discoverMode.value = mode
    }

    /** The Discover lists hold their order while on screen (as Scan): set when the tab opens or the mode changes. */
    private val nearbyOrder = StableOrder()

    /**
     * The category layout, with the display filter applied, in the order [nearbyOrder] holds: [first] for the
     * Discover page in its mode (Nearby: in range now; All: also what was seen before), [second] everything,
     * for the category pages.
     */
    private val nearbyViews: StateFlow<Pair<NearbyUiState, NearbyUiState>> = combine(
        combine(recorder, localData.productsInRange(), localData.previouslySeenProducts(), localData.catalog) { rec, inRange, seen, catalog ->
            listOf(rec, inRange, seen, catalog)
        },
        combine(localData.unknownStructuresInRange(), localData.previouslySeenUnknownStructures()) { now, seen -> now to seen },
        combine(hideApple, localData.pagesOnlyFromUnnamedApple()) { hide, pages -> hide to pages },
        _discoverMode,
    ) { products, (unknownNow, unknownSeen), (hide, applePages), mode ->
        @Suppress("UNCHECKED_CAST")
        val inRange = products[1] as InRangeSnapshot
        @Suppress("UNCHECKED_CAST")
        val seen = products[2] as List<CategoryProducts>
        // Display filter: in range recounted over the devices still shown; seen before, a product only ever heard
        // from unnamed Apple devices is left out, and so are Apple's company-id groups of the unidentified.
        val shownInRange = if (hide) visibleInRange(inRange) else inRange
        val live = shownInRange.products.mapTo(HashSet()) { it.page.id }
        val shownSeen = if (hide) seen.map { g -> g.copy(products = g.products.filter { it.page.id !in applePages || it.page.id in live }) } else seen
        fun apple(key: StructureKey) = key.companyId == AdTraits.APPLE_COMPANY_ID
        val state = NearbyMapper.map(
            products[0] as RecorderUiState, shownInRange, shownSeen, products[3] as WikiCatalog?, texts,
            unknownNow = if (hide) unknownNow.filterNot { apple(it.structure) } else unknownNow,
            unknownSeen = if (hide) unknownSeen.filterNot { apple(it.structure) } else unknownSeen,
        )
        val now = System.currentTimeMillis()
        arrangeNearby(state, mode, now) to arrangeNearby(state, DiscoverMode.All, now, keyPrefix = "category")
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyMapper.map(RecorderUiState(), null, emptyList(), null, texts).let { it to it })

    val nearby: StateFlow<NearbyUiState> = nearbyViews.map { it.first }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), nearbyViews.value.first)

    /** Every product by category (in range and seen before), for the category pages. */
    val nearbyAll: StateFlow<NearbyUiState> = nearbyViews.map { it.second }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), nearbyViews.value.second)

    /** Nearby keeps what is in range now; then every list holds its order (categories, their items, the unidentified). */
    private fun arrangeNearby(state: NearbyUiState, mode: DiscoverMode, nowMs: Long, keyPrefix: String = mode.name): NearbyUiState {
        val nearbyOnly = mode == DiscoverMode.Nearby
        fun keep(items: List<ProductItemState>) = if (nearbyOnly) items.filter { it.strongestRssi != null } else items
        val categories = state.categories.map { it.copy(items = keep(it.items)) }.filter { it.items.isNotEmpty() }
            .map { c -> c.copy(items = nearbyOrder.arrange("$keyPrefix:${c.id}", c.items, nowMs) { it.id }) }
        return state.copy(
            categories = nearbyOrder.arrange("$keyPrefix:categories", categories, nowMs) { it.id },
            unrecognized = state.unrecognized.copy(items = nearbyOrder.arrange("$keyPrefix:unknown", keep(state.unrecognized.items), nowMs) { it.id }),
        )
    }

    /** The in-range products recounted over the live devices the display filter keeps (strongest RSSI too). */
    private fun visibleInRange(snapshot: InRangeSnapshot): InRangeSnapshot {
        class Acc(var devices: Int = 0, var rssi: Int? = null)
        val shown = synchronized(lock) {
            tracker.advertisements().filterKeys { addr ->
                !tracker.isUnnamedApple(addr) && snapshot.atMs - (tracker.lastSeenMs(addr) ?: 0) <= snapshot.windowMs
            }.mapValues { (addr, raws) -> raws to tracker.rssi(addr) }
        }
        val byPage = HashMap<String, Acc>()
        for ((addr, v) in shown) {
            val (raws, rssi) = v
            for (page in raws.flatMap { pagesFor(addr, it) }.toSet()) {
                val acc = byPage.getOrPut(page) { Acc() }
                acc.devices++
                if (rssi != null && (acc.rssi == null || rssi > acc.rssi!!)) acc.rssi = rssi
            }
        }
        return snapshot.copy(products = snapshot.products.mapNotNull { p -> byPage[p.page.id]?.let { p.copy(deviceCount = it.devices, strongestRssi = it.rssi) } })
    }

    /** The wiki's word on a product, for its page; null until the catalog is loaded. */
    fun productHeader(pageId: String): ProductHeaderState? = localData.catalog.value?.page(pageId)?.let(DiscoverMapper::header)

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
        combine(hideApple, localData.unnamedAppleDevicesPerRecording()) { hide, counts -> if (hide) counts else emptyMap() },
    ) { rec, list, uploads, noteMap, hidden -> RecordingMapper.state(rec, list, uploads, noteMap, texts, hiddenDevices = hidden) }
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
            is Route.Product -> sightingMap(localData.sightingsOfProduct(route.pageId, 2_000), _tick.map { liveOfProduct(route.pageId) }, productTitle = route.pageId)
            is Route.ProductMap ->
                if (route.pageId.startsWith(DEMO_PREFIX)) demoMap(route.pageId.removePrefix(DEMO_PREFIX))
                else sightingMap(localData.sightingsOfProduct(route.pageId, 2_000), _tick.map { liveOfProduct(route.pageId) }, productTitle = route.pageId)
            is Route.DeviceMap -> sightingMap(localData.sightingsOfDevice(route.addr, 2_000), _tick.map { liveDevice(route.addr) }, deviceAddr = route.addr)
            else -> flowOf(null)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun md(ms: Long): String =
        texts.monthDay(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate())

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
            DeviceSort.Name -> inArea.sortedWith(compareBy({ it.unnamed }, { it.title.lowercase() }, { it.mac }))
        }
    }

    private val unnamedText get() = texts.string(R.string.unnamed)

    private fun titleFor(addr: String, pageIds: List<String>): String {
        val live = synchronized(lock) { tracker.name(addr) }
        if (live != null) return live
        val catalog = localData.catalog.value
        return pageIds.firstNotNullOfOrNull { catalog?.page(it)?.title } ?: unnamedText
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
            val (r, route, allDevices) = d.first
            if (r == null) return@combine null
            val hide = hideApple.value
            val devices = DisplayFilter.devices(allDevices, hide)
            val hiddenAddrs = if (hide) allDevices.filter { it.unnamedApple }.mapTo(HashSet()) { it.addr } else emptySet()
            val routePts = route.map { LatLon(it.lat, it.lon) }
            val rows = devices.map { dev: RecordedDevice ->
                val title = titleFor(dev.addr, dev.pageIds)
                MapListRowState(
                    id = dev.addr,
                    title = title,
                    unnamed = title == unnamedText,
                    mac = dev.addr,
                    detail = texts.plural(R.plurals.minutes_heard, dev.minuteCount, dev.minuteCount),
                    rssi = dev.strongestRssi,
                    lastSeenMs = dev.lastSeenMs,
                    at = dev.bestLocation?.let { LatLon(it.lat, it.lon) },
                )
            }.arranged(local, areaAddrs)
            val points = history.filter { it.recordingId == id && it.addr !in hiddenAddrs }.mapNotNull { s -> s.location?.let { SightingPoint(LatLon(it.lat, it.lon), s.bestRssi ?: -90) } }
            val estimate = if (selected != null) estimatePosition(points) else null
            val heat = thinForHeat(heatRows.filter { it.addr !in hiddenAddrs }.mapNotNull { s -> s.location?.let { LatLon(it.lat, it.lon) } })
            val deviceCount = r.deviceCount - hiddenAddrs.size
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.startedAtMs), ZoneId.systemDefault())
            MapUiState(
                mode = MapMode.Recording,
                title = texts.string(R.string.map_recording_title, texts.monthDay(start.toLocalDate()), texts.timeOfDay(start)),
                // A 0 m distance and, while recording, a 0 device count say nothing; they are left out.
                subtitle = listOfNotNull(
                    if (r.active) texts.string(R.string.history_in_progress) else null,
                    formatDuration((r.durationMs / 1000).toInt(), texts),
                    r.distanceMeters.toInt().takeIf { it > 0 }?.let(::formatDistance),
                    if (r.active && deviceCount == 0) null else texts.plural(R.plurals.devices, deviceCount, deviceCount),
                ).joinToString(" · "),
                listHint = if (r.active && rows.isEmpty()) texts.string(R.string.recording_devices_pending) else null,
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
                emptyNote = if (routePts.isEmpty() && devices.all { it.bestLocation == null }) texts.string(R.string.recording_no_location) else null,
            )
        }
    }

    /** A device heard right now, with the phone's position when it heard it the strongest. */
    private data class LiveHit(val addr: String, val rssi: Int?, val spot: LiveSpot?, val lastSeenMs: Long)

    private fun liveHit(addr: String): LiveHit? {
        val last = tracker.lastSeenMs(addr) ?: return null
        return LiveHit(addr, tracker.rssi(addr), tracker.spot(addr), last)
    }

    /** The devices tracked now whose advertisements (any of them) identify [pageId]. */
    private fun liveOfProduct(pageId: String): List<LiveHit> {
        val ads = synchronized(lock) { tracker.advertisements() }
        val addrs = ads.filter { (addr, raws) -> raws.any { pageId in pagesFor(addr, it) } }.keys
        return synchronized(lock) { addrs.mapNotNull(::liveHit) }
    }

    private fun liveDevice(addr: String): List<LiveHit> = synchronized(lock) { listOfNotNull(liveHit(addr)) }

    /**
     * The product map (one wiki page) and the device map (one address) share this. It joins what is heard
     * now (from the live scan, placed where the phone heard it the strongest) with what recordings saved.
     */
    private fun sightingMap(
        sightings: Flow<List<Sighting>>,
        live: Flow<List<LiveHit>>,
        productTitle: String? = null,
        deviceAddr: String? = null,
    ): Flow<MapUiState?> =
        combine(combine(sightings, live) { a, b -> a to b }, localData.catalog, _selected, _mapLocal, selectedDetail) { (allRows, allHits), catalog, selected, local, det ->
            // Display filter: a device heard now is judged on everything it advertised, a recorded one on its rows.
            val hiddenAddrs: Set<String> = if (hideApple.value) {
                synchronized(lock) { allHits.filter { tracker.isUnnamedApple(it.addr) }.mapTo(HashSet()) { it.addr } } +
                    DisplayFilter.unnamedAppleAddrs(allRows).filter { addr -> allHits.none { it.addr == addr } }
            } else {
                emptySet()
            }
            val all = allRows.filter { it.addr !in hiddenAddrs }
            val liveHits = allHits.filter { it.addr !in hiddenAddrs }
            val located = all.filter { it.location != null }
            fun point(s: Sighting) = SightingPoint(LatLon(s.location!!.lat, s.location.lon), s.bestRssi ?: -90)
            fun livePoint(h: LiveHit) = h.spot?.let { SightingPoint(LatLon(it.lat, it.lon), it.rssi) }
            val liveByAddr = liveHits.associateBy { it.addr }
            val recordedByAddr = all.groupBy { it.addr }
            val now = System.currentTimeMillis()
            fun heard(h: LiveHit): String {
                val minutes = ((now - h.lastSeenMs) / 60_000).toInt()
                return if (minutes < 1) texts.string(R.string.map_row_heard_now)
                else texts.string(R.string.map_row_heard_ago, texts.plural(R.plurals.minutes_heard, minutes, minutes))
            }
            val rows = (liveByAddr.keys + recordedByAddr.keys).map { addr ->
                val list = recordedByAddr[addr].orEmpty()
                val hit = liveByAddr[addr]
                val last = maxOf(list.maxOfOrNull { it.minuteMs } ?: 0L, hit?.lastSeenMs ?: 0L)
                // Each device by its own advertised name (Govee_H6056_4828), else the product's title.
                val title = if (productTitle != null) {
                    synchronized(lock) { tracker.name(addr) } ?: catalog?.page(productTitle)?.title ?: productTitle
                } else {
                    titleFor(addr, list.firstOrNull()?.pageIds.orEmpty())
                }
                val recorded = if (list.isEmpty()) null else texts.string(
                    R.string.map_row_detail,
                    list.map { it.recordingId }.distinct().size.let { texts.plural(R.plurals.recordings_count, it, it) }, md(last),
                )
                MapListRowState(
                    id = addr,
                    title = title,
                    unnamed = productTitle == null && title == unnamedText,
                    mac = addr,
                    // What is heard now wins; otherwise what recordings saved.
                    detail = hit?.let(::heard) ?: recorded.orEmpty(),
                    rssi = hit?.rssi ?: list.mapNotNull { it.bestRssi }.maxOrNull(),
                    lastSeenMs = last,
                    at = hit?.spot?.let { LatLon(it.lat, it.lon) } ?: list.firstOrNull { it.location != null }?.location?.let { LatLon(it.lat, it.lon) },
                )
            }.arranged(local)
            val selectedPoints = if (selected != null) {
                located.filter { it.addr == selected }.map(::point) + listOfNotNull(liveByAddr[selected]?.let(::livePoint))
            } else {
                emptyList()
            }
            val all2 = located.map(::point) + liveHits.mapNotNull(::livePoint)
            val estimate = if (selected != null) estimatePosition(selectedPoints) else null
            val title = productTitle?.let { catalog?.page(it)?.title ?: it } ?: deviceAddr.orEmpty()
            val macs = (liveByAddr.keys + recordedByAddr.keys).size
            val recordingsCount = all.map { it.recordingId }.distinct().size
            MapUiState(
                mode = MapMode.Product,
                title = title,
                // One phrase, no dot-joined facts: how many are heard now, else what recordings hold.
                subtitle = when {
                    liveHits.isNotEmpty() && productTitle != null -> texts.string(R.string.map_heard_now_count, liveHits.size)
                    liveHits.isNotEmpty() -> texts.string(R.string.map_row_heard_now)
                    recordingsCount > 0 -> texts.string(R.string.map_from_recordings, texts.plural(R.plurals.recordings_count, recordingsCount, recordingsCount))
                    else -> ""
                },
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
                emptyNote = if (all2.isEmpty()) texts.string(R.string.sightings_no_location) else null,
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

    /** Opens a product's page from a Discover card or a category page; the unidentified groups have none. */
    fun openProduct(pageId: String) {
        if (pageId.startsWith(NearbyMapper.UNKNOWN_PREFIX)) return
        open(Route.Product(pageId))
    }

    /** From the product page's map preview: the full product map. */
    fun openProductMap(pageId: String) = open(Route.ProductMap(pageId))

    // Last, once everything it uses is initialised. "Record when the app opens": once per process, so a
    // rotated or re-created page does not restart a recording the user stopped.
    init {
        if (!autoRecordDone) {
            autoRecordDone = true
            if (AppSettings.isAutoRecord(app) && recording.state.value.mode == RecordingMode.IDLE) startRecording()
        }
    }

    override fun onCleared() {
        toasts.close()
        super.onCleared()
    }
}
