package edu.ucsd.sysnet.cluetoothscanner.platform.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanObservationInput
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingScanner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The BLE scanner of a recording (§4.1, SPEC V-1): it scans only between [start] and [stopAndDrain], which the
 * recorder calls while recording and not paused.
 *
 * All decisions are in [ScanMachine] (a pure reducer over an immutable [ScanState]); this class is its runner. One
 * actor on the main thread owns the state and the Android objects (the scan callbacks, timers and receivers): Android
 * callbacks and the public calls become [ScanEvent]s in its inbox, it samples the [ScanEnv] facts when it reduces, and
 * it executes the [ScanEffect]s. Scan results never go through the actor: a callback of the live generation hands
 * them straight to [ScanIngest].
 *
 * - ScanSettings as v3: low latency, all matches, `setLegacy(false)`, PHY `ALL_SUPPORTED` with "Scan LE Coded PHY"
 *   on (else 1M), and one empty `ScanFilter`.
 * - The scan restarts every 60 s; with the screen off the restart waits until the scan has run 20 min
 *   ([ScanRestartPolicy]). A Coded PHY change applies at the next restart, after `core.configureScan`.
 * - Bluetooth off (or no scanner) defers the scan: [deferred] is true and the scan restarts by itself on `STATE_ON`.
 * - [stopAndDrain] waits until the actor stopped the scan, then for the callbacks already queued (main-looper
 *   fence), then flushes [ingest].
 */
@SuppressLint("MissingPermission") // The recorder checks BLUETOOTH_SCAN/CONNECT before a recording starts.
class BleScanner(
    private val context: Context,
    private val ingest: ScanIngest,
    private val core: Deferred<CoreIngest>,
    /** The "Scan LE Coded PHY" setting, read at every (re)start. */
    private val codedPhy: () -> Boolean,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** The latest `BluetoothDevice` per address, for GATT on random addresses before API 34. */
    val devices: LatestByKey<String, BluetoothDevice> = LatestByKey(1_024),
) : RecordingScanner {
    private val main = Handler(Looper.getMainLooper())
    private val fence = CallbackFence(main::post)
    private val _deferred = MutableStateFlow(false)
    override val deferred: StateFlow<Boolean> = _deferred.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()
    override val dropped: StateFlow<Long> get() = ingest.dropped
    private val oversized = AtomicLong()

    /** Advertisements kept out of the durable path because they exceed the row limit. */
    val oversizedDropped: Long get() = oversized.get()

    /** One message per event; [done] completes once the actor has executed the event's effects. */
    private class Message(val event: ScanEvent, val done: CompletableDeferred<Unit>? = null)

    private val inbox = Channel<Message>(Channel.UNLIMITED)

    // ---- Owned by the actor (main thread) ----
    private var live: Pair<ScanGeneration, ScanCallback>? = null
    private val timers = mutableMapOf<TimerKind, Job>()
    private var receiversRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> send(ScanEvent.BluetoothOff)
                BluetoothAdapter.STATE_ON -> send(ScanEvent.BluetoothOn)
                else -> Unit // other adapter states change nothing
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) = send(ScanEvent.ScreenOn)
    }

    init {
        scope.launch(Dispatchers.Main.immediate) {
            var state = ScanState()
            for (message in inbox) {
                val transition = ScanMachine.reduce(state, message.event, env())
                state = transition.state
                _deferred.value = state.deferred
                _error.value = state.error
                transition.effects.forEach(::execute)
                message.done?.complete(Unit)
            }
        }
    }

    override fun start() = send(ScanEvent.Start)

    override suspend fun stopAndDrain() {
        val stopped = CompletableDeferred<Unit>()
        inbox.send(Message(ScanEvent.Stop, stopped))
        stopped.await()
        fence.await()
        ingest.flush()
    }

    /**
     * The environment for `core.startRecording` (the recorder's `environment`): the current Coded PHY setting becomes
     * the footer's, and the scans of this recording use it until a restart changes it through `configureScan`. This is
     * the only public way to get an environment, so a new recording can never start on the previous one's PHY.
     */
    fun environment(): ScanEnvironment {
        val phy = Phy.of(codedPhy())
        send(ScanEvent.FooterDeclared(phy))
        return environmentFor(phy)
    }

    private fun send(event: ScanEvent) {
        inbox.trySend(Message(event))
    }

    /** The footer facts of a scan with the given PHY (`start_recording`, `configure_scan`). */
    private fun environmentFor(phy: Phy): ScanEnvironment {
        val adapter = adapter()
        val o = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        return buildScanEnvironment(
            deviceModel = Build.MODEL ?: "",
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = appVersion,
            extendedAdvertisingSupported = o && adapter?.isLeExtendedAdvertisingSupported == true,
            codedPhySupported = o && adapter?.isLeCodedPhySupported == true,
            maxAdvertisingDataLength = if (o) adapter?.leMaximumAdvertisingDataLength ?: 31 else 31,
            codedPhyEnabled = phy == Phy.Coded,
        )
    }

    private fun adapter(): BluetoothAdapter? = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter
    } catch (_: SecurityException) {
        null
    }

    private fun env(): ScanEnv {
        val adapter = adapter()
        val radio = try {
            when {
                adapter?.isEnabled != true -> Radio.Off
                adapter.bluetoothLeScanner == null -> Radio.NoScanner
                else -> Radio.On
            }
        } catch (_: SecurityException) {
            Radio.Off
        }
        return ScanEnv(
            now = ElapsedMs(SystemClock.elapsedRealtime()),
            radio = radio,
            wanted = Phy.of(codedPhy()),
            screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true,
        )
    }

    private fun execute(effect: ScanEffect) {
        when (effect) {
            ScanEffect.RegisterReceivers -> registerReceivers()
            ScanEffect.UnregisterReceivers -> unregisterReceivers()
            is ScanEffect.StartScan -> startScan(effect.generation, effect.phy)
            is ScanEffect.StopScan -> stopScan(effect.generation)
            is ScanEffect.Arm -> {
                timers.remove(effect.kind)?.cancel()
                timers[effect.kind] = scope.launch(Dispatchers.Main.immediate) {
                    delay(effect.delay.value)
                    send(ScanEvent.TimerFired(effect.kind, effect.token))
                }
            }
            is ScanEffect.Cancel -> timers.remove(effect.kind)?.cancel()
            is ScanEffect.Configure -> configure(effect.phy)
        }
    }

    private fun startScan(generation: ScanGeneration, phy: Phy) {
        val callback = callback(generation)
        live = generation to callback
        val failure = try {
            val scanner = adapter()?.bluetoothLeScanner
            scanner?.startScan(listOf(ScanFilter.Builder().build()), settings(phy == Phy.Coded), callback)
            if (scanner == null) "no Bluetooth LE scanner" else null
        } catch (failure: Exception) {
            // SecurityException, DeadObjectException, IllegalStateException...: a typed failure event, never a crash.
            failure.message ?: failure::class.java.simpleName
        }
        if (failure != null) {
            live = null
            send(ScanEvent.StartFailed(generation, failure))
        }
    }

    private fun stopScan(generation: ScanGeneration) {
        val (current, callback) = live ?: return
        if (current != generation) return
        live = null
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (failure: Exception) {
            Log.w(TAG, "stopScan failed", failure)
        }
    }

    private fun configure(phy: Phy) {
        scope.launch(Dispatchers.Main.immediate) {
            val error = try {
                withContext(io) { core.await().configureScan(environmentFor(phy)) }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "configureScan failed; keeping the current PHY", failure)
                failure.message ?: failure::class.java.simpleName
            }
            send(ScanEvent.Configured(phy, error))
        }
    }

    /** Callbacks run on the main thread, like the actor; only the live generation's results count. */
    private fun callback(generation: ScanGeneration) = object : ScanCallback() {
        private fun isLive() = live?.first == generation

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result != null && isLive()) deliver(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            if (isLive()) results?.forEach(::deliver)
        }

        override fun onScanFailed(errorCode: Int) {
            if (!isLive()) return
            live = null
            send(ScanEvent.ScanFailed(generation, errorCode))
        }
    }

    private fun deliver(result: ScanResult) {
        val snapshot = ClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
        val observation = toObservation(result, snapshot) ?: run {
            oversized.incrementAndGet()
            return
        }
        devices.put(observation.addr, result.device)
        ingest.offer(observation)
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        receiversRegistered = true
        ContextCompat.registerReceiver(
            context, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        receiversRegistered = false
        context.unregisterReceiver(bluetoothReceiver)
        context.unregisterReceiver(screenReceiver)
    }

    companion object {
        private const val TAG = "BleScanner"
        /** [ScanMachine.RESTART] and [ScanMachine.RETRY], for callers that need plain numbers. */
        const val RESTART_MS = 60_000L
        const val RETRY_MS = 1_000L

        // Receive extended advertisements as well as legacy ones (API 26+), on every PHY the controller can scan
        // unless the Coded PHY switch is off.
        internal fun settings(coded: Boolean): ScanSettings {
            val builder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setLegacy(false)
                builder.setPhy(if (coded) ScanSettings.PHY_LE_ALL_SUPPORTED else BluetoothDevice.PHY_LE_1M)
            }
            return builder.build()
        }

        /** v3 rules: RSSI/TX power 127 → null, address type on API 35+, raw ≤ 1650 B (else null: not a row). */
        internal fun toObservation(result: ScanResult, snapshot: ClockSnapshot): ScanObservationInput? {
            val record = result.scanRecord
            val raw = record?.bytes?.copyOf() ?: ByteArray(0)
            if (!rawFitsRow(raw)) return null
            val o = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            val v3 = if (o) {
                ScanV3Fields.from(
                    isLegacy = result.isLegacy,
                    primaryPhy = result.primaryPhy,
                    secondaryPhy = result.secondaryPhy,
                    advertisingSid = result.advertisingSid,
                    txPower = result.txPower,
                    periodicAdvertisingInterval = result.periodicAdvertisingInterval,
                    dataStatus = result.dataStatus,
                    addressType = if (Build.VERSION.SDK_INT >= 35) result.device.addressType else null,
                )
            } else {
                ScanV3Fields.ABSENT
            }
            return ScanObservationInput(
                addr = result.device.address,
                rssi = result.rssi.takeUnless { it == 127 },
                scannedAtMs = scanWallClockMillis(result.timestampNanos, snapshot),
                elapsedRealtimeNanos = result.timestampNanos,
                raw = raw,
                localName = record?.deviceName,
                txPower = record?.txPowerLevel?.takeUnless { it == 127 || it == Int.MIN_VALUE },
                isConnectable = if (o) result.isConnectable else null,
                isLegacy = v3.isLegacy,
                primaryPhy = v3.primaryPhy,
                secondaryPhy = v3.secondaryPhy,
                advSid = v3.advSid,
                advTxPower = v3.advTxPower,
                periodicInterval = v3.periodicInterval,
                dataStatus = v3.dataStatus,
                addrType = v3.addrType,
            )
        }
    }
}

/**
 * A bounded map keeping the latest value per key; past [capacity] the key written first is evicted. Lock-free: the
 * scanner writes on the main thread, GATT reads from any thread.
 */
class LatestByKey<K : Any, V : Any>(private val capacity: Int) {
    private val entries = ConcurrentHashMap<K, V>()
    private val order = ConcurrentLinkedQueue<K>()

    fun put(key: K, value: V) {
        if (entries.put(key, value) == null) order.add(key)
        while (entries.size > capacity) order.poll()?.let(entries::remove) ?: break
    }

    operator fun get(key: K): V? = entries[key]
}
