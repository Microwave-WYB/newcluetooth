package edu.ucsd.sysnet.cluetoothscanner.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.V3CluetoothApplication
import edu.ucsd.sysnet.cluetoothscanner.V3MainActivity
import edu.ucsd.sysnet.cluetoothscanner.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps a recording alive: it holds a partial wake lock while the
 * recording runs, shows a notification with Pause/Resume and Stop, and goes away when the
 * recording is idle. It owns no recording logic; [RecordingController] does.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false
    private var lastNotificationAtMs = 0L

    private val controller: RecordingController
        get() = (application as V3CluetoothApplication).recordingController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> controller.stop()
        }
        // startForeground must follow every startForegroundService within a few seconds.
        if (!enterForeground(controller.state.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) {
            observing = true
            scope.launch {
                controller.state.collect { state ->
                    applyWakeLock(state)
                    if (!state.serviceNeeded) {
                        ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        updateNotification(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /** False when Android refused (the service then stops itself and the error is surfaced). */
    private fun enterForeground(state: RecordingState): Boolean {
        createChannel()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(state), types)
            true
        } catch (error: Exception) {
            // Not allowed to become a foreground service (for example started while the app is
            // not visible). The recording itself carries on in the process, without the
            // foreground guarantees; the UI is told through RecordingState.error.
            Log.w(TAG, "Could not start as a foreground service", error)
            controller.reportServiceFailure(
                "The recording service could not start (${error.message ?: error::class.java.simpleName}); " +
                    "recording continues only while the app stays open",
            )
            false
        }
    }

    private fun updateNotification(state: RecordingState) {
        val now = android.os.SystemClock.elapsedRealtime()
        // The text changes every second; the notification does not need to.
        if (now - lastNotificationAtMs < NOTIFICATION_INTERVAL_MS && state.mode == lastMode) return
        lastNotificationAtMs = now
        lastMode = state.mode
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
    }

    private var lastMode: RecordingMode? = null

    private fun applyWakeLock(state: RecordingState) {
        if (state.wakeLockNeeded) {
            if (wakeLock?.isHeld != true) {
                val lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cluetooth:recording")
                lock.setReferenceCounted(false)
                // A bound so a bug can never hold the CPU awake forever. It is released while the
                // scanner is deferred (Bluetooth off) and taken again when scanning resumes.
                lock.acquire(WAKE_LOCK_MAX_MS)
                wakeLock = lock
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shown while Cluetooth is recording BLE advertisements"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(state: RecordingState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, V3MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(RecordingNotificationText.title(state))
            .setContentText(RecordingNotificationText.text(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.mode == RecordingMode.PAUSED) {
            builder.addAction(0, "Resume", action(ACTION_RESUME, 1))
        } else {
            builder.addAction(0, "Pause", action(ACTION_PAUSE, 2))
        }
        builder.addAction(0, "Stop", action(ACTION_STOP, 3))
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
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_MAX_MS = 12 * 60 * 60 * 1_000L
        const val ACTION_PAUSE = "edu.ucsd.sysnet.cluetoothscanner.action.PAUSE"
        const val ACTION_RESUME = "edu.ucsd.sysnet.cluetoothscanner.action.RESUME"
        const val ACTION_STOP = "edu.ucsd.sysnet.cluetoothscanner.action.STOP"
    }
}

/** [RecordingHost] backed by [RecordingService]. */
internal class AndroidRecordingHost(private val context: Context) : RecordingHost {
    override fun onActive() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        } catch (error: Exception) {
            Log.w("RecordingService", "Foreground service not started; recording continues in the process", error)
            (context.applicationContext as? V3CluetoothApplication)?.recordingController?.reportServiceFailure(
                "The recording service could not start (${error.message ?: error::class.java.simpleName}); " +
                    "recording continues only while the app stays open",
            )
        }
    }

    override fun onIdle() {
        context.stopService(Intent(context, RecordingService::class.java))
    }
}
