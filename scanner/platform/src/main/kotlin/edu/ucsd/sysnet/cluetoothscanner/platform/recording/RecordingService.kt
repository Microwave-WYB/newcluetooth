package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Recorder
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.platform.R
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The process-wide link between the [Recorder] singleton and [RecordingService]: the service reads the recorder from
 * here, and [AndroidForegroundHost] waits here for the service's `startForeground`.
 */
internal object RecordingServiceLink {
    private val recorderRef = AtomicReference<Recorder?>(null)
    private val foregroundRef = AtomicReference<CompletableDeferred<Boolean>?>(null)

    /** The process recorder; written once at startup, read by the service on the main thread. Lock-free. */
    var recorder: Recorder?
        get() = recorderRef.get()
        set(value) = recorderRef.set(value)

    /** The confirmation the next `startForeground` completes. Lock-free. */
    var foreground: CompletableDeferred<Boolean>?
        get() = foregroundRef.get()
        set(value) = foregroundRef.set(value)
}

/**
 * The started foreground service of a recording (§1.2.3, `FOREGROUND_SERVICE.md`). Its type is
 * `location|connectedDevice`; it keeps the default `stopWithTask="false"` and is `START_NOT_STICKY`. It holds the
 * notification and the bounded wake lock and no recording logic: [Recorder] starts and stops the scanner and location,
 * and the service follows [Recorder.state] until it is Idle.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val throttle = RecordingNotificationPolicy.Throttle()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockWanted = false
    private var observing = false
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = RecordingServiceLink.recorder
        when (intent?.action) {
            ACTION_START -> {
                // Enter the foreground before anything else: the 5 s deadline never waits for the core.
                // startForeground must follow startForegroundService even when there is nothing to record.
                val entered = enterForeground(recorder?.state?.value ?: RecorderState.Idle)
                // Without a linked recorder the service cannot follow a recording: refuse, so no session starts.
                val confirmed = entered && recorder != null
                RecordingServiceLink.foreground?.complete(confirmed)
                if (!confirmed) {
                    if (entered) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    inForeground = false
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_PAUSE -> recorder?.let { scope.launch { it.pause() } }
            ACTION_RESUME -> recorder?.let { scope.launch { it.resume() } }
            ACTION_STOP -> recorder?.let { scope.launch { it.stop() } }
        }
        if (recorder == null || (!inForeground && recorder.state.value.phase == RecorderState.Phase.Idle)) {
            // A stale notification action after process death, or nothing to record: never linger.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch { recorder.state.collect(::follow) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun follow(state: RecorderState) {
        if (!RecordingNotificationPolicy.serviceNeeded(state)) {
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
            return
        }
        applyWakeLock(state)
        if (inForeground && throttle.shouldUpdate(state, SystemClock.elapsedRealtime())) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
        }
    }

    private fun enterForeground(state: RecorderState): Boolean {
        createChannel(this)
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), types)
            inForeground = true
            true
        } catch (error: Exception) {
            // ForegroundServiceStartNotAllowedException or a SecurityException for a missing grant.
            Log.w(TAG, "startForeground refused", error)
            false
        }
    }

    private fun applyWakeLock(state: RecorderState) {
        val needed = RecordingNotificationPolicy.wakeLockNeeded(state)
        val entering = needed && !wakeLockWanted
        wakeLockWanted = needed
        if (!needed) {
            releaseWakeLock()
            return
        }
        // Acquired only on entering a collecting state (start, resume, Bluetooth back, the seal after a pause), never
        // on the updates in between, so the 12 h bound holds; a resume renews it.
        if (!entering) return
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cluetooth:recording")
            .apply {
                setReferenceCounted(false)
                acquire(RecordingNotificationPolicy.WAKE_LOCK_MAX_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** True while the wake lock is held (tests). */
    internal val holdsWakeLock: Boolean get() = wakeLock?.isHeld == true

    private fun notification(state: RecorderState): Notification {
        val now = System.currentTimeMillis()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(RecordingNotificationPolicy.title(state))
            .setContentText(RecordingNotificationPolicy.text(state, now))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        openAppIntent(this)?.let(builder::setContentIntent)
        when (RecordingNotificationPolicy.pauseAction(state)) {
            RecordingNotificationPolicy.Action.Pause -> builder.addAction(0, "Pause", action(ACTION_PAUSE, 1))
            RecordingNotificationPolicy.Action.Resume -> builder.addAction(0, "Resume", action(ACTION_RESUME, 2))
            null -> Unit
        }
        if (RecordingNotificationPolicy.stopAction(state)) builder.addAction(0, "Stop", action(ACTION_STOP, 3))
        return builder.build()
    }

    private fun action(name: String, requestCode: Int) = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RecordingService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 4101
        const val ACTION_START = "edu.ucsd.sysnet.cluetoothscanner.platform.action.START"
        const val ACTION_PAUSE = "edu.ucsd.sysnet.cluetoothscanner.platform.action.PAUSE"
        const val ACTION_RESUME = "edu.ucsd.sysnet.cluetoothscanner.platform.action.RESUME"
        const val ACTION_STOP = "edu.ucsd.sysnet.cluetoothscanner.platform.action.STOP"

        internal fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shown while Cluetooth is recording"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** The host app's launcher activity, so :platform never names `MainActivity` and the gallery works too. */
        internal fun openAppIntent(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                PendingIntent.getActivity(
                    context,
                    0,
                    launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
    }
}

/** [ForegroundHost] over [RecordingService]. */
class AndroidForegroundHost(
    private val context: Context,
    private val confirmTimeoutMs: Long = 5_000,
) : ForegroundHost {
    override suspend fun enterForeground(): Boolean {
        val confirmed = CompletableDeferred<Boolean>()
        RecordingServiceLink.foreground = confirmed
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START),
            )
        } catch (error: Exception) {
            // ForegroundServiceStartNotAllowedException (started from the background) or a SecurityException.
            Log.w("RecordingService", "startForegroundService refused", error)
            return false
        }
        return withTimeoutOrNull(confirmTimeoutMs) { confirmed.await() } ?: false
    }

    override fun leaveForeground() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}
