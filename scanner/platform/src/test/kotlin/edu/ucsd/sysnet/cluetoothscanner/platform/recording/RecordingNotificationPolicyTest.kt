package edu.ucsd.sysnet.cluetoothscanner.platform.recording

import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.platform.recording.RecordingNotificationPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotificationPolicyTest {
    private val recording = RecorderState.Idle.copy(
        phase = Phase.Recording, sessionId = "s1", activeBeforeMs = 60_000, runningSinceMs = 1_000_000, deviceCount = 26,
    )

    @Test
    fun `R-3 while recording Android ticks the elapsed time from the run's start minus the time before the pause`() {
        assertEquals("Recording", RecordingNotificationPolicy.title(recording))
        val base = RecordingNotificationPolicy.chronometerBaseMs(recording)
        assertEquals(1_000_000L - 60_000L, base)
        // A chronometer shows now - base: 12:34 at 694 s into the run after 60 s recorded before the pause.
        assertEquals(754_000L, 1_000_000L + 694_000L - (base ?: 0))
        assertEquals(RecordingNotificationPolicy.elapsedMs(recording, 1_694_000), 1_694_000 - (base ?: 0))
        assertEquals("the text holds only the device count", "26 devices", RecordingNotificationPolicy.text(recording, nowMs = 1_694_000))
        for (phase in listOf(Phase.Paused, Phase.Starting, Phase.Stopping, Phase.Idle)) {
            assertNull("$phase: no chronometer", RecordingNotificationPolicy.chronometerBaseMs(recording.copy(phase = phase)))
        }
        assertNull("no run start yet", RecordingNotificationPolicy.chronometerBaseMs(recording.copy(runningSinceMs = null)))
    }

    @Test
    fun `section 4_4 without a chronometer the text holds the elapsed time and device count`() {
        assertEquals("1:00:00 · 1 device", RecordingNotificationPolicy.text(recording.copy(activeBeforeMs = 3_600_000, runningSinceMs = null, deviceCount = 1), 0))
        assertEquals("0:00 · 12,345 devices", RecordingNotificationPolicy.text(recording.copy(activeBeforeMs = 0, runningSinceMs = null, deviceCount = 12_345), 0))
    }

    @Test
    fun `paused time does not tick`() {
        val paused = recording.copy(phase = Phase.Paused, runningSinceMs = null)
        assertEquals("Recording paused", RecordingNotificationPolicy.title(paused))
        assertEquals("1:00 · 26 devices", RecordingNotificationPolicy.text(paused, nowMs = 99_999_999))
    }

    @Test
    fun `titles for starting, stopping and Bluetooth off`() {
        assertEquals("Starting recording", RecordingNotificationPolicy.title(recording.copy(phase = Phase.Starting)))
        assertEquals("Saving recording", RecordingNotificationPolicy.title(recording.copy(phase = Phase.Stopping)))
        assertEquals("Recording waiting for Bluetooth", RecordingNotificationPolicy.title(recording.copy(scannerDeferred = true)))
    }

    @Test
    fun `actions are Pause or Resume plus Stop while live`() {
        assertEquals(Action.Pause, RecordingNotificationPolicy.pauseAction(recording))
        assertEquals(Action.Resume, RecordingNotificationPolicy.pauseAction(recording.copy(phase = Phase.Paused)))
        assertNull(RecordingNotificationPolicy.pauseAction(recording.copy(phase = Phase.Stopping)))
        assertTrue(RecordingNotificationPolicy.stopAction(recording.copy(phase = Phase.Paused)))
        assertFalse(RecordingNotificationPolicy.stopAction(recording.copy(phase = Phase.Starting)))
    }

    @Test
    fun `the service lives until idle and pause keeps it`() {
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Starting)))
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Paused)))
        assertTrue(RecordingNotificationPolicy.serviceNeeded(recording.copy(phase = Phase.Stopping)))
        assertFalse(RecordingNotificationPolicy.serviceNeeded(RecorderState.Idle))
    }

    @Test
    fun `the wake lock is held only while collecting or sealing`() {
        assertTrue(RecordingNotificationPolicy.wakeLockNeeded(recording))
        assertTrue(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Stopping)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Paused)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(scannerDeferred = true)))
        assertFalse(RecordingNotificationPolicy.wakeLockNeeded(recording.copy(phase = Phase.Starting)))
    }

    private fun RecordingNotificationPolicy.ThrottleStep.shown() =
        effects.filterIsInstance<RecordingNotificationPolicy.ThrottleEffect.Show>().map { it.state }

    @Test
    fun `updates coalesce to one per 5 s, and the latest held-back state shows when the window opens`() {
        val p = RecordingNotificationPolicy
        val first = p.offer(RecordingNotificationPolicy.Throttle(), recording, 0)
        assertEquals(listOf(recording), first.shown())
        val held = p.offer(first.throttle, recording.copy(deviceCount = 30), 1_000)
        assertTrue(held.shown().isEmpty())
        assertEquals(listOf(RecordingNotificationPolicy.ThrottleEffect.Arm(5_000)), held.effects)
        val newer = p.offer(held.throttle, recording.copy(deviceCount = 31), 2_000)
        assertTrue("one timer is enough", newer.effects.isEmpty())
        val opened = p.fired(newer.throttle, 5_000)
        assertEquals("the latest one, not dropped", listOf(recording.copy(deviceCount = 31)), opened.shown())
        assertTrue(p.fired(opened.throttle, 5_001).effects.isEmpty())
    }

    @Test
    fun `a change of kind shows at once, and a timer armed before it waits for the new window`() {
        val p = RecordingNotificationPolicy
        val first = p.offer(RecordingNotificationPolicy.Throttle(), recording, 0)
        val held = p.offer(first.throttle, recording.copy(deviceCount = 30), 1_000)
        val paused = recording.copy(phase = Phase.Paused, runningSinceMs = null, pause = RecorderState.PauseReason.User)
        val kind = p.offer(held.throttle, paused, 2_000)
        assertEquals(listOf(paused), kind.shown())
        val count = p.offer(kind.throttle, paused.copy(deviceCount = 32), 3_000)
        val early = p.fired(count.throttle, 5_000)
        assertTrue("the window opened at 2 s + 5 s", early.shown().isEmpty())
        assertEquals(listOf(RecordingNotificationPolicy.ThrottleEffect.Arm(7_000)), early.effects)
        assertEquals(listOf(paused.copy(deviceCount = 32)), p.fired(early.throttle, 7_000).shown())
        assertTrue(p.offer(RecordingNotificationPolicy.Throttle(), paused, 0).shown().isNotEmpty())
        assertTrue("Bluetooth off is a kind", p.offer(first.throttle, recording.copy(scannerDeferred = true), 1).shown().isNotEmpty())
    }

    /**
     * Random states at random times, with the service's timers fired when they are due (late, never early). After
     * every step: no two plain updates closer than 5 s; and once the offers stop and the timers run, the last state
     * offered is the one shown.
     */
    @Test
    fun `random updates always end on the last state, at most one plain update per window`() {
        val p = RecordingNotificationPolicy
        repeat(SEEDS) { seed ->
            val random = kotlin.random.Random(seed)
            var throttle = RecordingNotificationPolicy.Throttle()
            var now = 0L
            val timers = mutableListOf<Long>()
            var lastShown: RecorderState? = null
            var lastPlainAt: Long? = null
            var lastOffered: RecorderState? = null
            fun apply(step: RecordingNotificationPolicy.ThrottleStep, kindChange: Boolean) {
                throttle = step.throttle
                for (effect in step.effects) {
                    when (effect) {
                        is RecordingNotificationPolicy.ThrottleEffect.Show -> {
                            if (!kindChange) {
                                lastPlainAt?.let { assertTrue("seed $seed: two updates within 5 s at $now", now - it >= p.UPDATE_INTERVAL_MS) }
                            }
                            lastPlainAt = now
                            lastShown = effect.state
                        }
                        is RecordingNotificationPolicy.ThrottleEffect.Arm -> timers += effect.atMs
                    }
                }
            }
            fun fireDue() {
                while (true) {
                    val due = timers.filter { it <= now }.minOrNull() ?: return
                    timers.remove(due)
                    apply(p.fired(throttle, now), kindChange = false)
                }
            }
            repeat(STEPS) {
                now += random.nextLong(0, 3_000)
                fireDue()
                val phase = if (random.nextInt(8) == 0) Phase.entries.random(random) else Phase.Recording
                val state = recording.copy(phase = phase, deviceCount = random.nextInt(100), scannerDeferred = random.nextInt(10) == 0)
                val kindChange = throttle.lastKind == null ||
                    lastShown?.let { it.phase != state.phase || it.scannerDeferred != state.scannerDeferred } ?: true
                lastOffered = state
                apply(p.offer(throttle, state, now), kindChange)
            }
            // The offers stop; the service's timers run until none is left.
            while (timers.isNotEmpty()) {
                now = maxOf(now, timers.min())
                fireDue()
            }
            assertEquals("seed $seed: the last state is shown", lastOffered, lastShown)
        }
    }

    @Test
    fun `R-10 storage full shows in the notification, with Resume and Stop`() {
        val paused = recording.copy(phase = Phase.Paused, runningSinceMs = null, pause = RecorderState.PauseReason.User)
        val full = paused.copy(pause = RecorderState.PauseReason.StorageFull(150_000_000))
        assertEquals("Recording paused: storage is full", RecordingNotificationPolicy.title(full))
        assertEquals("Free up space to continue · 150 MB free", RecordingNotificationPolicy.text(full, 0))
        assertEquals(Action.Resume, RecordingNotificationPolicy.pauseAction(full))
        assertTrue(RecordingNotificationPolicy.stopAction(full))
        assertTrue("the service stays", RecordingNotificationPolicy.serviceNeeded(full))
        assertFalse("nothing is collected", RecordingNotificationPolicy.wakeLockNeeded(full))
        val failed = paused.copy(pause = RecorderState.PauseReason.StorageFailed("disk I/O error"))
        assertEquals("Recording paused: it could not be stored", RecordingNotificationPolicy.title(failed))
        assertEquals("Resume to try again · disk I/O error", RecordingNotificationPolicy.text(failed, 0))
        assertEquals(Action.Resume, RecordingNotificationPolicy.pauseAction(failed))
        val p = RecordingNotificationPolicy
        val shown = p.offer(RecordingNotificationPolicy.Throttle(), paused, 0)
        val fullShown = p.offer(shown.throttle, full, 1)
        assertEquals("storage full shows at once", listOf(full), fullShown.shown())
        assertEquals("a storage failure too", listOf(failed), p.offer(fullShown.throttle, failed, 2).shown())
    }

    private companion object {
        const val SEEDS = 300
        const val STEPS = 200
    }
}
