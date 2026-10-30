package edu.ucsd.sysnet.cluetoothscanner.chrome.gallery

import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Bin
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins.Run
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * R-4a samples for the gallery and the screenshots, each at [ELAPSED_MS] (the gallery's 12:34) except [empty]: just
 * started (no bin complete), walking (rising), standing still (falling towards 0), and a pause between a still
 * stretch and a walk.
 */
object DiscoverySamples {
    const val ELAPSED_MS = (12 * 60 + 34) * 1000L
    private const val T0 = 1_761_678_720_000L
    private const val BIN = DiscoveryBins.BIN_MS

    private fun bins(start: Long, counts: List<Int>) = counts.mapIndexed { k, n -> Bin(start + k * BIN, n) }.filter { it.newDevices > 0 }

    /** [empty]'s elapsed time. */
    const val EMPTY_ELAPSED_MS = 20_000L

    /** The first 20 s ([EMPTY_ELAPSED_MS]): the run has started, no bin is complete. */
    val empty = DiscoveryBins(BIN, listOf(Bin(T0, 3)), listOf(Run(T0, null)))

    /** Walking somewhere new: the rate climbs. */
    val rising = DiscoveryBins(BIN, bins(T0, List(25) { k -> 2 + k / 2 + (if (k % 3 == 0) 1 else 0) }), listOf(Run(T0, null)))

    /** Standing in one place: everything nearby is found, and the line falls towards 0. */
    val stationary = DiscoveryBins(BIN, bins(T0, List(25) { k -> (18 * exp(-k / 3.5)).roundToInt() }), listOf(Run(T0, null)))

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
