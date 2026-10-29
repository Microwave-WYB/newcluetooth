package edu.ucsd.sysnet.cluetoothscanner.sheets

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreRecordingState
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceLive
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceRow
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveDelta
import edu.ucsd.sysnet.cluetoothscanner.api.core.RecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Fixed-seed random event sequences through [reduceGatt] and [reduceRows]. */
class GattAndRowsPropertyTest {
    private fun view(state: GattStepState, ok: Boolean) =
        GattView("d", 7, 0, ok, listOf(GattStep(GattStepKind.Connecting, state, null, null, null, null, GattFailure.TimedOut.takeIf { state == GattStepState.Failed })), emptyList())

    @Test
    fun `D-13 random GATT events - Running only after a start, and only the newest run's events count`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            var s = GattState()
            var started = 0L // the newest run a StartRun effect asked for
            var cancelled = true // no collector of [started] is running
            repeat(400) {
                // runs: the current one, an old one, or one never started
                val run = s.run + rnd.nextLong(-2, 2)
                val event = when (rnd.nextInt(9)) {
                    0 -> GattEvent.Discover
                    1 -> GattEvent.CancelPrompt
                    2 -> GattEvent.TryAgain
                    3 -> GattEvent.Authorized(7, 0)
                    4 -> GattEvent.Progress(run, view(GattStepState.entries.random(rnd), rnd.nextBoolean()))
                    5 -> GattEvent.Finished(run, view(GattStepState.entries.random(rnd), rnd.nextBoolean()).takeIf { rnd.nextBoolean() })
                    6 -> GattEvent.RunFailed(run)
                    7 -> GattEvent.LastLoaded(view(GattStepState.Done, true).takeIf { rnd.nextBoolean() })
                    else -> GattEvent.Cancel
                }
                val step = reduceGatt(s, event)
                val next = step.state
                val ctx = "seed $seed: $s + $event -> $next"
                // D-13.3: the prompt comes only from Idle (Discover) or Failed (Try again), every time
                if (next.ui == GattUi.Confirm && s.ui != GattUi.Confirm) {
                    assertTrue(ctx, event == GattEvent.Discover && s.ui == GattUi.Idle || event == GattEvent.TryAgain && s.ui is GattUi.Failed)
                }
                // a run starts only with "I am authorized" on the prompt, and gets a new number
                step.effects.filterIsInstance<GattEffect.StartRun>().forEach { start ->
                    assertTrue(ctx, s.ui == GattUi.Confirm && event is GattEvent.Authorized)
                    assertTrue(ctx, start.run > started)
                    started = start.run
                    cancelled = false
                }
                if (GattEffect.CancelRun in step.effects) cancelled = true
                // Running only after a start, for the run that was started, and never after its collector was cancelled
                if (next.ui is GattUi.Running) {
                    assertEquals(ctx, started, next.run)
                    assertFalse(ctx, cancelled)
                }
                // a run's events count only while that run shows
                if (event is GattEvent.Progress && (event.run != s.run || s.ui !is GattUi.Running)) assertSame(ctx, s, next)
                if (event is GattEvent.Finished && (event.run != s.run || s.ui !is GattUi.Running)) assertSame(ctx, s, next)
                if (event is GattEvent.RunFailed) {
                    if (event.run != s.run || s.ui !is GattUi.Running) assertSame(ctx, s, next) else assertTrue(ctx, next.ui is GattUi.Failed)
                }
                // D-13.8: cancelling never leaves a run showing, and always stops the collector
                if (event == GattEvent.Cancel) {
                    assertTrue(ctx, next.ui !is GattUi.Running)
                    assertTrue(ctx, GattEffect.CancelRun in step.effects)
                }
                // D-13.7: a stored result shows only on an idle tab
                if (event is GattEvent.LastLoaded && s.ui != GattUi.Idle) assertEquals(ctx, s, next)
                // a run ends Running when its flow ends: Finished leaves Running
                if (event is GattEvent.Finished && event.run == s.run && s.ui is GattUi.Running) assertTrue(ctx, next.ui !is GattUi.Running)
                if (next.ui !is GattUi.Running && s.ui is GattUi.Running && event != GattEvent.Cancel) cancelled = true // its flow ended
                s = next
            }
        }
    }

    private fun row(key: Long, rssi: Int = -60) = DeviceRow(key, "AA", null, null, emptyList(), emptyList(), false, false, null, rssi, true, null, null, 0)

    private fun delta(seq: Long, changed: List<Long>, appended: List<Long>) = LiveDelta(
        "s", seq, 0, CoreRecordingState(RecordingPhase.Recording, "s", 0, 0, null, 0, 0.0),
        changed.map { DeviceLive(it, -50, true, ByteArray(0), null, null, null) }, appended.map(::row), emptyList(), emptyList(), null, null,
    )

    @Test
    fun `S-2_6 random row events - stale deltas are ignored, a key is never loaded twice at once, every write bumps the version`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            var state = RowState()
            val table = HashMap<Long, DeviceRow>() // the runner's table: written only by Put
            var nextKey = 1000L
            repeat(300) {
                val event = when (rnd.nextInt(4)) {
                    0 -> RowEvent.Requested(List(rnd.nextInt(1, 8)) { rnd.nextLong(1, 40) })
                    1 -> RowEvent.ChunkLoaded(List(rnd.nextInt(0, 5)) { row(rnd.nextLong(1, 40)) })
                    2 -> RowEvent.LoadEnded(state.loading.shuffled(rnd).take(rnd.nextInt(0, 4)))
                    else -> RowEvent.Live(delta(state.seq + rnd.nextLong(-2, 3), List(rnd.nextInt(0, 4)) { rnd.nextLong(1, 40) }, List(rnd.nextInt(0, 2)) { nextKey++ }))
                }
                val before = HashMap(table)
                val step = reduceRows(state, table, event)
                val ctx = "seed $seed: $event"
                val puts = step.effects.filterIsInstance<RowEffect.Put>().flatMap { it.rows }
                puts.forEach { table[it.key] = it }
                // the version counts writes, exactly
                assertEquals(ctx, state.version + (if (puts.isEmpty()) 0 else 1), step.state.version)
                if (event is RowEvent.Requested) {
                    val loads = step.effects.filterIsInstance<RowEffect.Load>().flatMap { it.keys }
                    assertTrue(ctx, loads.none { it in state.loading || it in before })
                    assertEquals(ctx, loads.size, loads.toSet().size)
                }
                if (event is RowEvent.Live) {
                    if (event.delta.seq <= state.seq) assertEquals(ctx, RowStep(state), step)
                    else {
                        assertEquals(ctx, event.delta.seq, step.state.seq)
                        val appended = step.effects.filterIsInstance<RowEffect.AppendToOrder>().flatMap { it.keys.asList() }
                        assertEquals(ctx, event.delta.appended.map { it.key }, appended)
                        // changes apply in place to loaded rows only; a delta never invents an unloaded, unappended row
                        assertTrue(ctx, puts.all { it.key in before || it.key in appended })
                        // a changed loaded row takes the delta's values and keeps the rest
                        for (c in event.delta.changed) if (c.key in before && c.key !in appended) assertEquals(ctx, c.rssi, table[c.key]?.rssi)
                    }
                }
                assertTrue(ctx, step.state.seq >= state.seq)
                state = step.state
            }
        }
    }
}
