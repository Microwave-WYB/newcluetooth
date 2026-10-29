package edu.ucsd.sysnet.cluetoothscanner.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.V3MainActivity
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.core.GatewayScanSession
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionStatus

/**
 * The recordings the core found interrupted (the process died while recording) that the user has
 * not been told about yet. Only sessions that saved rows count; each is reported once.
 */
internal fun unseenInterruptedRecordings(
    sessions: List<GatewayScanSession>,
    acknowledged: Set<String>,
): List<GatewayScanSession> = sessions
    .filter { it.status == GatewaySessionStatus.INTERRUPTED && it.observationCount > 0u }
    .filter { it.sessionId !in acknowledged }
    .sortedBy { it.startedAtMs }

/** Tells the user, once per session, that a recording ended without a Stop. */
internal class InterruptedRecordingNotifier(private val context: Context) {
    private val prefs = context.getSharedPreferences("cluetooth_notices", Context.MODE_PRIVATE)

    /** Posts a notification for new interrupted recordings and marks every one of them seen. */
    fun check(sessions: List<GatewayScanSession>) {
        val acknowledged = prefs.getStringSet(KEY_ACKNOWLEDGED, emptySet()).orEmpty()
        val unseen = unseenInterruptedRecordings(sessions, acknowledged)
        if (unseen.isEmpty()) return
        // The newest one is what the user cares about; all of them are marked seen.
        post(unseen.last().observationCount.toLong())
        prefs.edit().putStringSet(KEY_ACKNOWLEDGED, acknowledged + unseen.map { it.sessionId }).apply()
    }

    private fun post(rows: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording alerts", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, V3MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.recording_interrupted_title))
                .setContentText(context.getString(R.string.recording_interrupted_text, rows))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "recording-alerts"
        const val NOTIFICATION_ID = 4102
        const val KEY_ACKNOWLEDGED = "acknowledged_interrupted_sessions"
    }
}
