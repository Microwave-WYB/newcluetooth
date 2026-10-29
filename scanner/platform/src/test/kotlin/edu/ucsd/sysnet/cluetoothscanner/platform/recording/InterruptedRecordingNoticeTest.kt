package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionStatus
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InterruptedRecordingNoticeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun session(id: String, status: SessionStatus, started: Long, devices: Int = 3) =
        SessionSummary(id, status, started, started + 60_000, 60_000, 0.0, devices, UploadState.Pending)

    private val sessions = listOf(
        session("old", SessionStatus.Interrupted, 1),
        session("done", SessionStatus.Completed, 2),
        session("empty", SessionStatus.Interrupted, 3, devices = 0),
        session("new", SessionStatus.Interrupted, 4, devices = 26),
    )

    @Test
    fun `only unseen interrupted sessions with devices count, oldest first`() {
        assertEquals(listOf("old", "new"), unseenInterrupted(sessions, emptySet()).map { it.id })
        assertEquals(listOf("new"), unseenInterrupted(sessions, setOf("old")).map { it.id })
    }

    @Test
    fun `section 1_2_6 one notice for the newest, then never again`() = runBlocking {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notice = InterruptedRecordingNotice(context)
        assertEquals(listOf("old", "new"), notice.check { sessions }.map { it.id })
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(InterruptedRecordingNotice.TITLE, shadowOf(posted).contentTitle)
        assertTrue(shadowOf(posted).contentText.toString().contains("26 devices"))

        manager.cancelAll()
        assertTrue("seen sessions are never reported again", InterruptedRecordingNotice(context).check { sessions }.isEmpty())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `without the notification permission nothing is posted`() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        InterruptedRecordingNotice(context).check { sessions }
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
