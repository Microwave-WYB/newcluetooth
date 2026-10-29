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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Fixed-seed random event sequences through [reduceGatt] and [reduceRows]. */
class GattAndRowsPropertyTest {
    private fun view(state: GattStepState, ok: Boolean) =
        GattView("d", 7, 0, ok, listOf(GattStep(GattStepKind.Connecting, state, null, null, null, null, GattFailure.TimedOut.takeIf { state == GattStepState.Failed })), emptyList())

    @Test
    fun `D-13 random GATT events - a run starts only from the prompt, the prompt only from Idle or Failed`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            var s: GattUi = GattUi.Idle
            repeat(300) {
                val event = when (rnd.nextInt(8)) {
                    0 -> GattEvent.Discover
                    1 -> GattEvent.CancelPrompt
                    2 -> GattEvent.TryAgain
                    3 -> GattEvent.Authorized(7, 0)
                    4 -> GattEvent.Progress(view(GattStepState.entries.random(rnd), rnd.nextBoolean()))
                    5 -> GattEvent.Finished(view(GattStepState.entries.random(rnd), rnd.nextBoolean()).takeIf { rnd.nextBoolean() })
                    6 -> GattEvent.LastLoaded(view(GattStepState.Done, true).takeIf { rnd.nextBoolean() })
                    else -> GattEvent.Cancel
                }
                val step = reduceGatt(s, event)
                val ctx = "seed $seed: $s + $event -> ${step.state}"
                // D-13.3: the prompt comes only from Idle (Discover) or Failed (Try again), every time
                if (step.state == GattUi.Confirm && s != GattUi.Confirm) assertTrue(ctx, event == GattEvent.Discover && s == GattUi.Idle || event == GattEvent.TryAgain && s is GattUi.Failed)
                // a run starts only with "I am authorized" on the prompt
                if (GattEffect.StartRun in step.effects) assertTrue(ctx, s == GattUi.Confirm && event is GattEvent.Authorized)
                // D-13.8: cancelling never leaves a run showing, and always stops the collector
                if (event == GattEvent.Cancel) {
                    assertTrue(ctx, step.state !is GattUi.Running)
                    assertTrue(ctx, GattEffect.CancelRun in step.effects)
                }
                // D-13.7: a stored result shows only on an idle tab
                if (event is GattEvent.LastLoaded && s != GattUi.Idle) assertEquals(ctx, s, step.state)
                s = step.state
            }
        }
    }

    private fun row(key: Long) = DeviceRow(key, "AA", null, null, emptyList(), emptyList(), false, false, null, -60, true, null, null, 0)

    private fun delta(seq: Long, changed: List<Long>, appended: List<Long>) = LiveDelta(
        "s", seq, 0, CoreRecordingState(RecordingPhase.Recording, "s", 0, 0, null, 0, 0.0),
        changed.map { DeviceLive(it, -50, true, ByteArray(0), null, null, null) }, appended.map(::row), emptyList(), emptyList(), null, null,
    )

    @Test
    fun `S-2_6 random row events - stale deltas are ignored, a key is never loaded twice at once, appends reach the order`() {
        for (seed in 1..20) {
            val rnd = Random(seed)
            var cache = RowCache()
            var nextKey = 1000L
            repeat(300) {
                val event = when (rnd.nextInt(4)) {
                    0 -> RowEvent.Requested(List(rnd.nextInt(1, 8)) { rnd.nextLong(1, 40) })
                    1 -> RowEvent.ChunkLoaded(List(rnd.nextInt(0, 5)) { row(rnd.nextLong(1, 40)) })
                    2 -> RowEvent.LoadEnded(cache.loading.shuffled(rnd).take(rnd.nextInt(0, 4)))
                    else -> RowEvent.Live(delta(cache.seq + rnd.nextLong(-2, 3), List(rnd.nextInt(0, 4)) { rnd.nextLong(1, 40) }, List(rnd.nextInt(0, 2)) { nextKey++ }))
                }
                val step = reduceRows(cache, event)
                val ctx = "seed $seed: $event"
                if (event is RowEvent.Requested) {
                    val loads = step.effects.filterIsInstance<RowEffect.Load>().flatMap { it.keys }
                    assertTrue(ctx, loads.none { it in cache.loading || it in cache.rows })
                }
                if (event is RowEvent.Live) {
                    if (event.delta.seq <= cache.seq) assertEquals(ctx, cache, step.cache)
                    else {
                        assertEquals(ctx, event.delta.seq, step.cache.seq)
                        val appended = step.effects.filterIsInstance<RowEffect.AppendToOrder>().flatMap { it.keys.asList() }
                        assertEquals(ctx, event.delta.appended.map { it.key }, appended)
                        // changes apply in place to loaded rows only; a delta never invents an unloaded, unappended row
                        assertTrue(ctx, event.delta.changed.all { c -> c.key in cache.rows || c.key !in step.cache.rows || c.key in appended })
                    }
                }
                assertTrue(ctx, step.cache.seq >= cache.seq)
                cache = step.cache
            }
        }
    }
}
