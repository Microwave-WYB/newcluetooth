package edu.ucsd.sysnet.cluetoothscanner.chrome.gallery

import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * R-4a samples for the gallery and the screenshots, in the core's 5 s bins, each at [ELAPSED_MS] (the gallery's
 * 12:34) except [empty] and [dropAtEnd]: just started (nothing scanned yet), walking (rising), standing still
 * (falling towards 0), a pause between a still stretch and a walk, and a rate that drops at its end.
 */
object DiscoverySamples {
    const val ELAPSED_MS = (12 * 60 + 34) * 1000L
    private const val T0 = 1_761_678_720_000L
    private const val BIN = DiscoveryBins.BIN_MS
    private const val PER_30_S = (30_000L / BIN).toInt()

    /** Each 30 s count from [start] spread over its 5 s bins (the remainder in the earliest); empty bins left out. */
    private fun bins(start: Long, per30s: List<Int>) = per30s.flatMapIndexed { k, n ->
        List(PER_30_S) { j -> Bin(start + (k * PER_30_S + j) * BIN, n / PER_30_S + (if (j < n % PER_30_S) 1 else 0)) }
    }.filter { it.newDevices > 0 }

    /** [empty]'s elapsed time. */
    const val EMPTY_ELAPSED_MS = 3_000L

    /** The first 3 s ([EMPTY_ELAPSED_MS]): the run has started, nothing is scanned yet. */
    val empty = DiscoveryBins(BIN, emptyList(), listOf(Run(T0, null)))

    /** Walking somewhere new: the rate climbs. */
    val rising = DiscoveryBins(BIN, bins(T0, List(25) { k -> 2 + k / 2 + (if (k % 3 == 0) 1 else 0) }), listOf(Run(T0, null)))

    /** Standing in one place: everything nearby is found, and the line falls towards 0. */
    val stationary = DiscoveryBins(BIN, bins(T0, List(25) { k -> (18 * exp(-k / 3.5)).roundToInt() }), listOf(Run(T0, null)))

    /** [dropAtEnd]'s elapsed time: three minutes and a few seconds. */
    const val DROP_ELAPSED_MS = 3 * 60_000L + 5_000L

    /** Two and a half busy minutes, then nothing new: the line drops at its end, under where the label goes. */
    val dropAtEnd = DiscoveryBins(BIN, bins(T0, listOf(20, 20, 20, 20, 20, 0)), listOf(Run(T0, null)))

    /** Five still minutes, a two-minute pause, then a walk: the line breaks across the pause. */
    val pauseGap = run {
        val resume = T0 + 7 * 60_000L
        DiscoveryBins(
            BIN,
            bins(T0, List(10) { k -> (14 * exp(-k / 3.0)).roundToInt() }) + bins(resume, List(16) { k -> 1 + k / 2 }),
            listOf(Run(T0, T0 + 5 * 60_000L), Run(resume, null)),
        )
    }
}
