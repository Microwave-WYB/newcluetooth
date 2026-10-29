package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.platform.R

/** The interrupted sessions the user has not been told about, oldest first. Sessions without devices don't count. */
internal fun unseenInterrupted(sessions: List<SessionSummary>, acknowledged: Set<String>): List<SessionSummary> =
    sessions.filter { it.status == SessionStatus.Interrupted && it.deviceCount > 0 && it.id !in acknowledged }
        .sortedBy { it.startedMs }

/**
 * §1.2.6: process death is the only thing that ends a recording without Stop; `Core::open` marks that session
 * Interrupted. At the next start, [check] posts one "Recording interrupted" notification for every unseen
 * interrupted session together, and only once it was actually posted marks them all seen, so it never repeats. A
 * user who has notifications off (permission denied, notifications disabled, or the channel turned off) gets the
 * notice at a later start, once they are on again.
 *
 * Integration calls [check] once per process start, after `Core::open` has completed (so the interrupted session is
 * already marked), with `CoreQueries.sessions()`.
 */
class InterruptedRecordingNotice(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [sessions] is `CoreQueries.sessions()`. Returns the sessions it told the user about (empty when not posted). */
    suspend fun check(sessions: suspend () -> List<SessionSummary>): List<SessionSummary> {
        val acknowledged = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        val unseen = unseenInterrupted(sessions(), acknowledged)
        if (unseen.isEmpty()) return unseen
        if (!post(unseen.last())) return emptyList()
        prefs.edit().putStringSet(KEY_SEEN, acknowledged + unseen.map { it.id }).apply()
        return unseen
    }

    /** True only when the notification was posted where the user can see it. */
    private fun post(newest: SessionSummary): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording alerts", NotificationManager.IMPORTANCE_DEFAULT))
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        val devices = if (newest.deviceCount == 1) "1 device" else "${newest.deviceCount} devices"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(TITLE)
            .setContentText("The recording stopped without Stop. Its $devices up to then are saved.")
            .setAutoCancel(true)
        RecordingService.openAppIntent(context)?.let(builder::setContentIntent)
        manager.notify(NOTIFICATION_ID, builder.build())
        return true
    }

    companion object {
        const val CHANNEL_ID = "recording-alerts"
        const val NOTIFICATION_ID = 4102
        const val TITLE = "Recording interrupted"
        private const val PREFS = "cluetooth_notices"
        private const val KEY_SEEN = "acknowledged_interrupted_sessions"
    }
}
