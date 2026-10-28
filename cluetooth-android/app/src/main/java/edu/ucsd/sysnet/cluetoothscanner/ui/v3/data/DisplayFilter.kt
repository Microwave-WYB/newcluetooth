package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ble.AdTraits
import edu.ucsd.sysnet.cluetoothscanner.local.RecordedDevice
import edu.ucsd.sysnet.cluetoothscanner.local.Sighting

/**
 * "Hide unnamed Apple devices" (Settings, on by default): which of what was read the screens leave out.
 * It only filters lists after they are read; scanning, recording, storage, uploads, exports and rule
 * matching never see it.
 */
object DisplayFilter {
    /** The recorded devices to show. */
    fun devices(devices: List<RecordedDevice>, hideUnnamedApple: Boolean): List<RecordedDevice> =
        if (hideUnnamedApple) devices.filterNot { it.unnamedApple } else devices

    /** Addresses whose minute rows read as an unnamed Apple device (Apple's id in one, a name in none). */
    fun unnamedAppleAddrs(sightings: List<Sighting>): Set<String> =
        sightings.groupBy { it.addr }.filterValues { list -> AdTraits.unnamedApple(null, list.map { AdTraits(it.apple, it.named) }) }.keys
}
