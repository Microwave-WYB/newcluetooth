package edu.ucsd.sysnet.cluetoothscanner.ble

import android.bluetooth.BluetoothDevice
import edu.ucsd.sysnet.cluetoothscanner.core.CoreScanEnvironment

/**
 * The scan payload v3 columns taken from one `ScanResult` (docs/payload-schema-v3.md).
 *
 * Android reports "not present" through sentinel integers; the contract uses null for
 * "the platform did not report this", so every sentinel and every value outside the
 * documented range becomes null here, before the Rust core validates the batch.
 */
data class ScanV3Fields(
    val isLegacy: Boolean?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advSid: Int?,
    val advTxPower: Int?,
    val periodicInterval: Int?,
    val dataStatus: Int?,
    val addrType: Int?,
) {
    companion object {
        private const val PHY_1M = 1
        private const val PHY_CODED = 3
        private const val SID_MAX = 15
        private const val TX_POWER_MIN = -127
        private const val TX_POWER_MAX = 126
        private const val PERIODIC_INTERVAL_MAX = 65_535
        private const val DATA_COMPLETE = 0
        private const val DATA_TRUNCATED = 2

        fun from(
            isLegacy: Boolean,
            primaryPhy: Int,
            secondaryPhy: Int,
            advertisingSid: Int,
            txPower: Int,
            periodicAdvertisingInterval: Int,
            dataStatus: Int,
            addressType: Int?,
        ) = ScanV3Fields(
            isLegacy = isLegacy,
            primaryPhy = primaryPhy.takeIf { it == PHY_1M || it == PHY_CODED },
            secondaryPhy = secondaryPhy.takeIf { it in 0..3 },
            advSid = advertisingSid.takeIf { it in 0..SID_MAX },
            advTxPower = txPower.takeIf { it in TX_POWER_MIN..TX_POWER_MAX },
            periodicInterval = periodicAdvertisingInterval.takeIf { it in 1..PERIODIC_INTERVAL_MAX },
            dataStatus = dataStatus.takeIf { it == DATA_COMPLETE || it == DATA_TRUNCATED },
            addrType = addressType.takeIf { it == 0 || it == 1 },
        )

        /** Pre-O systems report none of the extended-advertising fields. */
        val ABSENT = ScanV3Fields(null, null, null, null, null, null, null, null)
    }
}

/** The longest advertisement the core accepts in a row (extended advertising maximum). */
const val MAX_ROW_RAW_BYTES = 1650

/**
 * Whether an advertisement may become a row. A longer one is kept out of the durable path (the
 * core would refuse it as well): it would otherwise be counted as a rejection, and must never
 * reach a payload where it could block every later checkpoint and seal.
 */
fun rawFitsRow(raw: ByteArray) = raw.size <= MAX_ROW_RAW_BYTES

/** Builds the file-footer facts the Rust core stores in every v3 payload. */
internal fun buildScanEnvironment(
    deviceModel: String,
    androidRelease: String,
    sdkInt: Int,
    appVersion: String,
    extendedAdvertisingSupported: Boolean,
    codedPhySupported: Boolean,
    maxAdvertisingDataLength: Int,
    codedPhyEnabled: Boolean,
) = CoreScanEnvironment(
    deviceModel = footerText(deviceModel),
    androidRelease = footerText(androidRelease),
    androidSdk = sdkInt,
    appVersion = footerText(appVersion),
    capExtAdv = extendedAdvertisingSupported,
    capCodedPhy = codedPhySupported,
    capMaxAdvLen = maxAdvertisingDataLength.coerceAtLeast(0),
    scanPhy = if (codedPhyEnabled) SCAN_PHY_ALL_SUPPORTED else SCAN_PHY_1M,
    // The scanner always asks for extended PDUs (setLegacy(false)).
    scanLegacyOnly = false,
)

internal const val SCAN_PHY_ALL_SUPPORTED = "all_supported"
internal const val SCAN_PHY_1M = "1m"
private const val FOOTER_TEXT_MAX_BYTES = 256

private fun footerText(value: String): String {
    val trimmed = value.trim().ifEmpty { "unknown" }
    var end = trimmed.length
    while (trimmed.substring(0, end).toByteArray(Charsets.UTF_8).size > FOOTER_TEXT_MAX_BYTES) end--
    return trimmed.substring(0, end)
}

/** The most recent advertisement the scanner saw from one address. */
data class AdvertisementTrigger(
    val addr: String,
    /** Kept so a random-address device can be connected with the exact address type. */
    val device: BluetoothDevice?,
    val addrType: Int?,
    val name: String?,
    val raw: ByteArray,
    val rssi: Int?,
    val scannedAtMs: Long,
    val fields: ScanV3Fields,
)

/** Bounded process-wide lookup of the latest advertisement per address (for GATT triggers). */
class AdvertisementTriggerCache(private val capacity: Int = 1_024) {
    private val entries = object : LinkedHashMap<String, AdvertisementTrigger>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AdvertisementTrigger>?) =
            size > capacity
    }

    fun put(trigger: AdvertisementTrigger) {
        synchronized(entries) { entries[trigger.addr] = trigger }
    }

    fun latest(addr: String): AdvertisementTrigger? =
        synchronized(entries) { entries[addr] }
}
