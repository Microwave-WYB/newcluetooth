package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import edu.ucsd.sysnet.cluetoothscanner.ble.BleParser
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdField
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdStructureRow
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdVariantState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AdvKind
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.DeviceRowState

/** One advertisement, reduced to what the tracker keeps (see `LiveScan`). */
class LiveSample(
    val addr: String,
    /** Null when the platform reported none. */
    val rssi: Int?,
    val timeMs: Long,
    val raw: ByteArray,
    val localName: String?,
    val connectable: Boolean?,
    val legacy: Boolean?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advSid: Int?,
    /** 0 public, 1 random, null when the platform did not say. */
    val addrType: Int?,
    /** The phone's location fix when this was heard, if any. */
    val lat: Double? = null,
    val lon: Double? = null,
)

/** Where the phone was when it heard a device the strongest (in memory only, while the device is tracked). */
data class LiveSpot(val lat: Double, val lon: Double, val rssi: Int, val timeMs: Long)

/**
 * Aggregates the live scan stream per MAC: the latest values, one RSSI average per second for
 * the sparkline and the detail chart, and one entry per distinct advertisement variant. Not
 * thread safe on its own; the owner serialises access.
 *
 * Memory is bounded: 15 minutes of per-second buckets per device, at most [MAX_VARIANTS]
 * variants per device, and devices unheard for [FORGET_AFTER_MS] are dropped by [prune].
 */
class LiveDeviceTracker {
    private data class VariantKey(val flags: Int, val hash: Int, val size: Int)

    private class Variant(
        val key: VariantKey,
        val raw: ByteArray,
        val legacy: Boolean,
        val primaryPhy: Int?,
        val secondaryPhy: Int?,
        val sid: Int?,
        val connectable: Boolean,
        val firstMs: Long,
    ) {
        /** Parsed once per variant: the table is read every second while a detail page is open. */
        val structures: List<AdStructureRow> by lazy { adStructures(raw) }
        var lastMs = firstMs
    }

    /**
     * The strongest RSSI of each second in primitive arrays. It starts small and doubles up to [MAX_BUCKETS];
     * only a full-size ring wraps, overwriting the oldest second, so before that `head` stays 0.
     */
    private class RssiRing {
        private var seconds = LongArray(INITIAL_BUCKETS)
        private var maxes = IntArray(INITIAL_BUCKETS)
        private var capacity = INITIAL_BUCKETS
        private var head = 0
        private var size = 0

        fun add(second: Long, rssi: Int) {
            if (size > 0) {
                val last = (head + size - 1) % capacity
                if (seconds[last] == second) {
                    if (rssi > maxes[last]) maxes[last] = rssi
                    return
                }
            }
            if (size == capacity && capacity < MAX_BUCKETS) grow()
            val index = if (size < capacity) (head + size++) % capacity else head.also { head = (head + 1) % capacity }
            seconds[index] = second
            maxes[index] = rssi
        }

        private fun grow() {
            capacity = minOf(capacity * 2, MAX_BUCKETS)
            seconds = seconds.copyOf(capacity)
            maxes = maxes.copyOf(capacity)
        }

        inline fun forEach(block: (second: Long, max: Int) -> Unit) {
            for (i in 0 until size) {
                val index = (head + i) % capacity
                block(seconds[index], maxes[index])
            }
        }
    }

    private class Device(val addr: String, val firstSeenMs: Long) {
        var name: String? = null
        var rssi: Int? = null
        var lastSeenMs = 0L
        var connectable = false
        var legacy = true
        var addrType: Int? = null
        var spot: LiveSpot? = null
        val ring = RssiRing()

        /** Access order: the variant least recently heard is the one evicted when full. */
        val variants = LinkedHashMap<VariantKey, Variant>(16, 0.75f, true)
    }

    private val devices = LinkedHashMap<String, Device>()

    val size: Int get() = devices.size

    fun contains(addr: String) = devices.containsKey(addr)

    /** Returns true when the address was not known before. */
    fun onSample(s: LiveSample): Boolean {
        val isNew = !devices.containsKey(s.addr)
        val d = devices.getOrPut(s.addr) { Device(s.addr, s.timeMs) }
        d.lastSeenMs = s.timeMs
        if (s.rssi != null) {
            d.rssi = s.rssi
            d.ring.add(s.timeMs / 1000, s.rssi)
            if (s.lat != null && s.lon != null && (d.spot?.let { s.rssi >= it.rssi } != false)) {
                d.spot = LiveSpot(s.lat, s.lon, s.rssi, s.timeMs)
            }
        }
        s.connectable?.let { d.connectable = it }
        s.legacy?.let { d.legacy = it }
        s.addrType?.let { d.addrType = it }
        val legacy = s.legacy ?: true
        val connectable = s.connectable ?: false
        val flags = (if (legacy) 1 else 0) or (if (connectable) 2 else 0) or ((s.primaryPhy ?: 0) shl 2) or ((s.secondaryPhy ?: 0) shl 5) or (((s.advSid ?: -1) + 1) shl 8)
        val key = VariantKey(flags, s.raw.contentHashCode(), s.raw.size)
        val v = d.variants.getOrPut(key) {
            if (d.variants.size >= MAX_VARIANTS) d.variants.remove(d.variants.keys.first())
            Variant(key, s.raw, legacy, s.primaryPhy, s.secondaryPhy, s.advSid, connectable, s.timeMs).also {
                (s.localName ?: advertisedName(s.raw))?.let { name -> d.name = name }
            }
        }
        v.lastMs = s.timeMs
        return isNew
    }

    fun rssi(addr: String): Int? = devices[addr]?.rssi

    fun lastSeenMs(addr: String): Long? = devices[addr]?.lastSeenMs

    /** Where the phone was when it heard [addr] the strongest, if it had a fix. */
    fun spot(addr: String): LiveSpot? = devices[addr]?.spot

    /** Every tracked device with the bytes of each distinct advertisement, for matching against the wiki. */
    fun advertisements(): Map<String, List<ByteArray>> = devices.mapValues { (_, d) -> d.variants.values.map { it.raw } }

    /** The advertised name, without building a row. */
    fun name(addr: String): String? = devices[addr]?.name

    fun isStale(addr: String, nowMs: Long): Boolean = devices[addr]?.let { nowMs - it.lastSeenMs > STALE_AFTER_MS } ?: true

    /** The raw bytes of the most recently heard variant, for matching against the wiki. */
    fun latestAdvertisement(addr: String): ByteArray? = devices[addr]?.variants?.values?.maxByOrNull { it.lastMs }?.raw

    /** Drops devices unheard for [FORGET_AFTER_MS]; returns their addresses. */
    fun prune(nowMs: Long): List<String> {
        val gone = devices.values.filter { nowMs - it.lastSeenMs > FORGET_AFTER_MS }.map { it.addr }
        gone.forEach(devices::remove)
        return gone
    }

    fun row(addr: String, nowMs: Long): DeviceRowState? {
        val d = devices[addr] ?: return null
        return DeviceRowState(
            id = d.addr,
            name = d.name,
            mac = d.addr,
            advKind = if (d.legacy) AdvKind.Legacy else AdvKind.Extended,
            connectable = d.connectable,
            rssi = d.rssi ?: -100,
            history = sparkline(d, nowMs),
            stale = nowMs - d.lastSeenMs > STALE_AFTER_MS,
        )
    }

    /** The last 60 seconds in 2 s buckets (the strongest RSSI of each; null = nothing heard). */
    private fun sparkline(d: Device, nowMs: Long): List<Int?> = bucketed(d, nowMs, SPARK_WINDOW_SEC, SPARK_BUCKET_SEC)

    /** The detail chart series: the last minute in 1 s buckets (60), ending now. */
    fun chart(addr: String, nowMs: Long): RssiSeries {
        val window = RssiBuckets.CHART_WINDOW_SEC
        val bucket = RssiBuckets.CHART_BUCKET_SEC
        val d = devices[addr] ?: return RssiSeries(List(window / bucket) { null }, null)
        return RssiSeries(bucketed(d, nowMs, window, bucket), ((nowMs - d.firstSeenMs) / 1000).toInt().coerceAtLeast(0))
    }

    private fun bucketed(d: Device, nowMs: Long, windowSec: Int, bucketSec: Int): List<Int?> {
        val bucketer = RssiBucketer(nowMs / 1000, windowSec, bucketSec)
        d.ring.forEach { second, max -> bucketer.add(second, max) }
        return bucketer.buckets()
    }

    /**
     * The distinct advertisements, by content, in the order they first appeared. Variants that differ
     * only in flags (connectable, PHY, set id) carry the same bytes and show once.
     */
    fun variants(addr: String): List<AdVariantState> {
        val d = devices[addr] ?: return emptyList()
        // The map is access-ordered (for eviction); the table is shown in the order the variants first appeared.
        return d.variants.values.sortedWith(compareBy<Variant> { it.firstMs }.thenBy { it.key.hash })
            .distinctBy { it.key.hash to it.key.size }
            .map { v -> AdVariantState(id = "v${v.key.hash}-${v.key.size}", structures = v.structures) }
    }

    companion object {
        const val MAX_BUCKETS = 900
        const val INITIAL_BUCKETS = 16
        const val MAX_VARIANTS = 24
        const val SPARK_WINDOW_SEC = 60
        const val SPARK_BUCKET_SEC = 2
        const val STALE_AFTER_MS = 20_000L
        const val FORGET_AFTER_MS = 30 * 60_000L

        fun advertisedName(raw: ByteArray): String? =
            BleParser.extractDeviceName(BleParser.parseAdvertisementData(raw))?.takeIf { it.isNotBlank() }

        fun adStructures(raw: ByteArray, sig: SigNumbers = SigNumbers.current): List<AdStructureRow> =
            BleParser.parseAdvertisementData(raw).structures.map { s ->
                AdStructureRow(
                    type = s.type.toString(16).padStart(2, '0').uppercase(),
                    typeName = sig.adType(s.type.toInt()) ?: BleParser.getAdTypeDescription(s.type),
                    length = s.length,
                    data = s.data.joinToString("") { "%02x".format(it) },
                    fields = fields(s.type.toInt(), s.data, sig),
                )
            }

        private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun uuid128(b: ByteArray, at: Int): String {
            val h = (15 downTo 0).joinToString("") { "%02x".format(b[at + it]) }
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }

        /** "Heart Rate (180D)", or the bare "180D" when the SIG list has no name for it. */
        private fun named16(sig: SigNumbers, uuid: Int): String {
            val code = "%04X".format(uuid)
            return sig.uuid16(uuid)?.let { "$it ($code)" } ?: code
        }

        /** The Flags bits (Core Specification Supplement, Part A, 1.3), one per line. */
        private val FLAG_BITS = listOf(
            "LE Limited Discoverable",
            "LE General Discoverable",
            "BR/EDR Not Supported",
            "LE and BR/EDR (Controller)",
            "LE and BR/EDR (Host)",
        )

        /** The data after an identifier (a company, a service), as its own row when there is any. */
        private fun rest(b: ByteArray, from: Int): List<AdField> =
            if (b.size > from) listOf(AdField("Data", hex(b.copyOfRange(from, b.size)), bytes = true)) else emptyList()

        /**
         * What a structure carries, as rows a person reads: the flags, a name as text, services and the
         * company by their SIG names, a TX power, an appearance. Several values go one per line. Anything
         * else, or anything malformed, stays bytes under "Type 0xNN".
         */
        fun fields(type: Int, b: ByteArray, sig: SigNumbers = SigNumbers.current): List<AdField> = when {
            type == 0x01 && b.size == 1 -> {
                val v = b[0].toInt() and 0xFF
                val bits = FLAG_BITS.filterIndexed { i, _ -> v and (1 shl i) != 0 }
                listOf(AdField("Flags", bits.joinToString("\n").ifEmpty { "None" }))
            }
            type == 0x09 && b.isNotEmpty() -> listOf(AdField("Name", b.decodeToString()))
            type == 0x08 && b.isNotEmpty() -> listOf(AdField("Short name", b.decodeToString()))
            (type == 0x02 || type == 0x03) && b.size % 2 == 0 && b.isNotEmpty() ->
                listOf(AdField("Services", (b.indices step 2).joinToString("\n") { named16(sig, u16(b, it)) }))
            (type == 0x06 || type == 0x07) && b.size % 16 == 0 && b.isNotEmpty() ->
                listOf(AdField("Services", (b.indices step 16).joinToString("\n") { uuid128(b, it) }))
            type == 0x16 && b.size >= 2 -> listOf(AdField("Service", named16(sig, u16(b, 0)))) + rest(b, 2)
            type == 0x21 && b.size >= 16 -> listOf(AdField("Service", uuid128(b, 0))) + rest(b, 16)
            type == 0x0A && b.size == 1 -> listOf(AdField("TX power", "${b[0].toInt()} dBm"))
            type == 0x19 && b.size == 2 -> {
                val v = u16(b, 0)
                listOf(AdField("Appearance", sig.appearance(v)?.let { "$it (0x%04X)".format(v) } ?: "0x%04X".format(v)))
            }
            type == 0xFF && b.size >= 2 -> {
                val id = u16(b, 0)
                val code = "0x%04X".format(id)
                listOf(AdField("Company", sig.company(id)?.let { "$it ($code)" } ?: code)) + rest(b, 2)
            }
            else -> listOf(AdField("Type 0x%02X".format(type), hex(b), bytes = true))
        }
    }
}
