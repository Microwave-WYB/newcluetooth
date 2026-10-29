package edu.ucsd.sysnet.cluetoothscanner.fake.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A line-by-line port of `scanner/design/ui-v2/data.js`: the same seeded LCG, consumed in the same order, so the
 * MACs, payloads, points and trails equal the prototype's. Only the data is ported here; the query semantics live in
 * [FakeCore].
 */
internal object FakeData {
    const val SEED = 20261028L
    val CENTER_LAT = 32.88
    val CENTER_LON = -117.234

    /** The prototype's clock: data.js says "just now" at the end of the 7:12 PM recording (12 min). */
    val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    val DEMO_NOW_MS: Long = epochMs("2026-10-28", "7:24 PM")

    class Lcg(private var seed: Long = SEED) {
        fun next(): Double {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFF_FFFFL
            return seed / 4294967296.0
        }
        fun <T> pick(items: List<T>): T = items[Math.floor(next() * items.size).toInt()]
        fun byte(): Int = Math.floor(next() * 256).toInt()
        fun mac(): String = (0 until 6).joinToString(":") { "%02X".format(byte()) }
        fun bytes(n: Int): String = (0 until n).joinToString("") { "%02x".format(byte()) }
    }

    class Product(
        val id: String, val name: String, val category: String, val company: Int?, val service: String?,
        val description: String, val places: Int, val last: String,
        val readable: Boolean = false, val vulnerable: Boolean = false,
    )

    /** data.js PRODUCTS, in its order (the bundle page order of the fake). */
    val PRODUCTS = listOf(
        Product("govee", "Govee lighting and sensors", "lighting", null, "EC88",
            "Govee and ihoment strip lights, bulbs and thermo-hygrometers.", 20, "just now", readable = true),
        Product("airpods", "Apple AirPods and Beats (Proximity Pairing)", "audio", 76, null,
            "Apple and Beats earbuds and headphones announcing their case and battery state.", 64, "just now"),
        Product("jbl", "JBL Flip and Charge speakers", "audio", 87, null,
            "Harman JBL portable speakers advertising their model and pairing state.", 7, "just now"),
        Product("bose", "Bose QuietComfort headphones", "audio", 158, null,
            "Bose headphones advertising the Bose app service.", 11, "4 min ago"),
        Product("hue", "Philips Hue Bluetooth lights", "lighting", null, "FE0F",
            "Hue bulbs and lamps that pair over Bluetooth without a bridge.", 3, "just now"),
        Product("xiaomi", "Xiaomi Mi Home BLE sensors (MiBeacon)", "sensors", null, "FE95",
            "Xiaomi thermometers and sensors sending MiBeacon service data.", 9, "just now", readable = true),
        Product("switchbot", "SwitchBot Meter and Hub sensors", "sensors", 2409, "FD3D",
            "SwitchBot meters advertising temperature and humidity.", 4, "just now", readable = true),
        Product("tile", "Tile trackers", "trackers", null, "FEED",
            "Tile Mate, Slim and Pro item trackers.", 38, "just now"),
        Product("smarttag", "Samsung Galaxy SmartTag and SmartTag2 offline finding beacons", "trackers", 117, "FD5A",
            "Samsung trackers broadcasting for SmartThings Find.", 26, "just now"),
        Product("fitbit", "Fitbit and Google Pixel Watch wearables", "wearables", null, "FEAA",
            "Fitbit trackers and Pixel Watch advertising for phone sync.", 15, "2 min ago"),
        Product("tesla", "Tesla vehicle phone key", "vehicles", null, null,
            "Tesla cars advertising for phone-as-key.", 6, "just now", vulnerable = true),
        Product("inkbird", "Inkbird IBS-TH temperature and humidity sensors", "sensors", null, "FFF0",
            "Inkbird sensors with readings in the manufacturer data.", 2, "2 h ago"),
        Product("nanoleaf", "Nanoleaf Essentials", "lighting", 2059, null,
            "Nanoleaf bulbs and strips commissioning over Bluetooth.", 1, "3 d ago"),
        Product("dexcom", "Dexcom G7 continuous glucose monitor", "medical", 208, "FEBC",
            "Dexcom sensors advertising to their paired phone.", 2, "1 d ago"),
        Product("ouraring", "Oura Ring", "wearables", 1797, null,
            "Oura smart rings advertising for sync.", 1, "5 h ago"),
    )
    val PRODUCT = PRODUCTS.associateBy { it.id }

    val COMPANY_NAMES = mapOf(
        76 to "Apple, Inc.", 87 to "Harman International", 117 to "Samsung Electronics", 158 to "Bose Corporation",
        208 to "Dexcom, Inc.", 1797 to "Oura Health Oy", 2059 to "Nanoleaf", 2409 to "Woan Technology",
        6 to "Microsoft", 89 to "Nordic Semiconductor",
    )

    class LiveRow(
        val name: String?, val product: String?, val company: Int?, val rssi: Int, val stale: Boolean,
        val connectable: Boolean, val legacy: Boolean,
    )

    /** data.js LIVE: the 32 devices of the live scan list. */
    val LIVE = listOf(
        LiveRow("ihoment_H6008_1A2B", "govee", null, -48, false, true, true),
        LiveRow("AirPods Pro", "airpods", null, -52, false, false, true),
        LiveRow("Govee_H6199_RGBIC_LED_Strip_Living_Room_Cabinet", "govee", null, -55, false, true, true),
        LiveRow(null, "tile", null, -58, false, true, true),
        LiveRow("JBL Flip 6", "jbl", null, -61, false, true, true),
        LiveRow("LYWSD03MMC", "xiaomi", null, -63, false, true, true),
        LiveRow(null, null, null, -64, false, false, true),
        LiveRow("GVH5075_4C21", "govee", null, -66, false, false, true),
        LiveRow("Hue color lamp", "hue", null, -67, true, true, true),
        LiveRow("SwitchBot Meter Plus", "switchbot", null, -69, false, true, true),
        LiveRow("[TV] Samsung 7 Series (55)", null, 117, -70, false, true, true),
        LiveRow("Bose QC Ultra Headphones", "bose", null, -72, false, true, false),
        LiveRow(null, "smarttag", null, -73, false, false, false),
        LiveRow("AirPods Max", "airpods", null, -74, true, false, true),
        LiveRow("Charge 5", "jbl", null, -75, false, true, true),
        LiveRow(null, null, 6, -76, false, false, true),
        LiveRow(null, null, 76, -76, false, false, true),
        LiveRow("S7d1c2a9e0b4f61a3C", "tesla", null, -77, false, true, true),
        LiveRow("Thermostat Hallway Upstairs Remote Sensor (Back Bedroom)", null, null, -78, false, true, true),
        LiveRow("Charge 3", "fitbit", null, -79, false, true, true),
        LiveRow(null, "smarttag", null, -81, false, false, false),
        LiveRow("ELK-BLEDOM", null, null, -82, false, true, true),
        LiveRow(null, null, 89, -83, true, true, true),
        LiveRow("GVH5102_9E0A", "govee", null, -84, false, false, true),
        LiveRow("Nordic_UART", null, 89, -85, false, true, true),
        LiveRow(null, "tile", null, -86, false, true, true),
        LiveRow("MJ_HT_V1", "xiaomi", null, -87, true, true, true),
        LiveRow(null, null, null, -88, false, false, true),
        LiveRow(null, null, 76, -90, false, false, true),
        LiveRow("BT05", null, null, -89, false, true, true),
        LiveRow(null, null, null, -91, true, false, true),
        LiveRow("SmartLock-8F2C", null, null, -93, false, true, true),
    )

    class Structure(val type: Int, val dataHex: String) {
        val data: ByteArray get() = dataHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val length: Int get() = dataHex.length / 2 + 1
    }

    class Device(
        val addr: String, val name: String?, val product: String?, val rssi: Int, val stale: Boolean,
        val connectable: Boolean, val legacy: Boolean, val company: Int?,
        /** 30 samples, one every 2 s, ending now (20 s ago for stale rows); null for recording-only devices. */
        val history: List<Int?>?, val structures: List<Structure>, val txPower: Int = -12,
    )

    class Point(val addr: String, val lon: Double, val lat: Double, val rssi: Int)

    class Recording(
        val id: String, val current: Boolean, val day: String, val time: String, val minutes: Int, val meters: Int,
        val devices: Int, val route: List<DoubleArray>,
    ) {
        lateinit var points: List<Point>
        val startedMs: Long get() = epochMs(day, time)
    }

    /** JS `+x.toFixed(6)`: the exact binary value rounded half away from zero. */
    fun fixed6(x: Double): Double = BigDecimal(x).setScale(6, RoundingMode.HALF_UP).toDouble()

    /** JS `Math.round`. */
    private fun jsRound(x: Double): Int = Math.floor(x + 0.5).toInt()

    private fun history(rssi: Int, stale: Boolean, i: Int): List<Int?> {
        val end = if (stale) 20 else 30
        return (0 until 30).map { k ->
            when {
                k >= end -> null
                k == end - 1 -> rssi
                else -> jsRound(rssi + 5 * StrictMath.sin(k * 0.6 + i) + 2.5 * StrictMath.sin(k * 1.7 + i * 2))
            }
        }
    }

    private fun Lcg.structuresFor(name: String?, product: String?, company: Int?): List<Structure> {
        val s = mutableListOf(Structure(0x01, "06"))
        if (name != null) s += Structure(0x09, name.map { "%02x".format(it.code) }.joinToString(""))
        val p = product?.let { PRODUCT.getValue(it) }
        val cid = if (p != null) p.company else company
        val svc = p?.service
        if (svc != null) {
            val le = svc.substring(2).lowercase() + svc.substring(0, 2).lowercase()
            s += Structure(0x03, le)
            s += Structure(0x16, le + bytes(8))
        }
        if (cid != null) s += Structure(0xFF, "%02x".format(cid and 0xff) + "%02x".format(cid shr 8) + bytes(10))
        if (svc == null && cid == null) s += Structure(0xFF, "ffff" + bytes(6))
        s += Structure(0x0A, "f4")
        return s
    }

    private fun Lcg.makeDevice(row: LiveRow, i: Int, extra: Boolean): Device {
        val p = row.product?.let { PRODUCT.getValue(it) }
        val addr = mac()
        return Device(
            addr = addr, name = row.name, product = row.product, rssi = row.rssi, stale = row.stale,
            connectable = row.connectable, legacy = row.legacy, company = if (p != null) p.company else row.company,
            history = if (extra) null else history(row.rssi, row.stale, i),
            structures = structuresFor(row.name, row.product, row.company),
        )
    }

    private fun route(phase: Double, scale: Double, n: Int): List<DoubleArray> = (0 until n).map { k ->
        val t = (k.toDouble() / (n - 1)) * Math.PI * 1.3 + phase
        doubleArrayOf(
            fixed6(CENTER_LON + scale * (0.0048 * StrictMath.cos(t) + 0.0006 * StrictMath.sin(5 * t))),
            fixed6(CENTER_LAT + scale * (0.0034 * StrictMath.sin(t) + 0.0005 * StrictMath.cos(4 * t))),
        )
    }

    private val EXTRA: Map<String, List<String?>> = mapOf(
        "" to listOf(null, null, null, null, "Pixel 8", "Galaxy Buds2 Pro", "WH-1000XM5", "Instinct 2", "LE_WH-1000XM4",
            "Echo Dot-4F2"),
        "govee" to listOf("ihoment_H6159_77B1", "Govee_H6172_0D3E", "GVH5179_21AF"),
        "airpods" to listOf("AirPods", "AirPods Pro", "Beats Studio Buds"),
        "tile" to listOf(null, "Tile"), "smarttag" to listOf(null), "xiaomi" to listOf("LYWSD03MMC", "MJ_HT_V1"),
        "jbl" to listOf("JBL Flip 5", "JBL Charge 4"), "fitbit" to listOf("Charge 6", "Inspire 3"),
        "bose" to listOf("Bose QC45", "Bose Flex SoundLink"), "switchbot" to listOf("WoMeter", null),
        "tesla" to listOf("S3f09a1c27be4d580C", "Sb81d2c0e94a7f312C"), "hue" to listOf("Hue white lamp", "Hue go"),
    )
    private val EXTRA_PRODUCTS = listOf("", "", "", "govee", "airpods", "tile", "smarttag", "xiaomi", "jbl", "fitbit",
        "bose", "switchbot", "tesla", "hue")

    /** A recording-only style device for the live simulator's appends and the bulk session (own [rnd] stream). */
    fun synthetic(rnd: Lcg, i: Int): Device {
        val prod = rnd.pick(EXTRA_PRODUCTS).ifEmpty { null }
        val name = rnd.pick(EXTRA.getValue(prod ?: ""))
        val company = if (prod != null) null else rnd.pick(listOf(null, 6, 76, 89, 117))
        val rssi = -55 - Math.floor(rnd.next() * 40).toInt()
        return rnd.makeDevice(LiveRow(name, prod, company, rssi, false, rnd.next() > 0.4, rnd.next() > 0.2), i, true)
    }

    /** The "now" recording's route formula, continued past its 40 points (k ≥ 40) for the live simulator. */
    fun liveRoutePoint(k: Int): DoubleArray {
        val t = (k.toDouble() / 39) * Math.PI * 1.3 + 0.4
        return doubleArrayOf(
            fixed6(CENTER_LON + 0.45 * (0.0048 * StrictMath.cos(t) + 0.0006 * StrictMath.sin(5 * t))),
            fixed6(CENTER_LAT + 0.45 * (0.0034 * StrictMath.sin(t) + 0.0005 * StrictMath.cos(4 * t))),
        )
    }

    /** The generated world: the same objects data.js builds at load. */
    class World(
        val devices: List<Device>, val recordings: List<Recording>, val byAddr: Map<String, Device>,
    )

    fun build(): World {
        val rnd = Lcg()
        val devices = LIVE.mapIndexed { i, row -> rnd.makeDevice(row, i, false) }
        val recordings = listOf(
            Recording("now", true, "2026-10-28", "7:12 PM", 12, 640, 26, route(0.4, 0.45, 40)),
            Recording("r1", false, "2026-10-28", "6:05 PM", 23, 1800, 214, route(0.0, 1.0, 90)),
            Recording("r2", false, "2026-10-28", "12:10 PM", 8, 0, 41,
                listOf(doubleArrayOf(CENTER_LON + 0.001, CENTER_LAT - 0.0008))),
            Recording("r3", false, "2026-10-27", "5:32 PM", 41, 3200, 388, route(1.2, 1.25, 120)),
            Recording("r4", false, "2026-10-27", "3:10 PM", 2, 0, 0, emptyList()),
            Recording("r5", false, "2026-10-27", "9:02 AM", 15, 950, 122, route(2.4, 0.7, 60)),
            Recording("r6", false, "2026-10-25", "2:14 PM", 65, 4600, 512, route(3.1, 1.4, 140)),
        )
        val extras = LinkedHashMap<String, Device>()
        fun jitter(p: DoubleArray) = doubleArrayOf(
            fixed6(p[0] + (rnd.next() - 0.5) * 0.0011),
            fixed6(p[1] + (rnd.next() - 0.5) * 0.0009),
        )
        for (rec in recordings) {
            if (rec.devices == 0) { rec.points = emptyList(); continue }
            val pts = rec.route.ifEmpty { listOf(doubleArrayOf(CENTER_LON, CENTER_LAT)) }
            val live = if (rec.current) devices.take(rec.devices) else devices.take(minOf(18, rec.devices))
            val out = live.mapIndexed { i, d ->
                val at = jitter(pts[(i * 7) % pts.size])
                Point(d.addr, at[0], at[1], d.rssi)
            }.toMutableList()
            for (i in out.size until rec.devices) {
                val prod = rnd.pick(EXTRA_PRODUCTS).ifEmpty { null }
                val name = rnd.pick(EXTRA.getValue(prod ?: ""))
                val company = if (prod != null) null else rnd.pick(listOf(null, 6, 89, 117))
                val rssi = -50 - Math.floor(rnd.next() * 45).toInt()
                val connectable = rnd.next() > 0.4
                val legacy = rnd.next() > 0.2
                val d = rnd.makeDevice(LiveRow(name, prod, company, rssi, true, connectable, legacy), i, true)
                extras[d.addr] = d
                val at = jitter(pts[Math.floor(rnd.next() * pts.size).toInt()])
                out += Point(d.addr, at[0], at[1], d.rssi)
            }
            rec.points = out
        }
        val byAddr = LinkedHashMap<String, Device>()
        devices.forEach { byAddr[it.addr] = it }
        byAddr.putAll(extras)
        return World(devices, recordings, byAddr)
    }

    /** data.js `sightings(addr)`: the strongest point plus a 10-step trail around it. */
    fun sightings(world: World, addr: String): List<Point> {
        var best: Point? = null
        for (r in world.recordings) for (p in r.points) if (p.addr == addr && (best == null || p.rssi > best.rssi)) best = p
        val b = best ?: return emptyList()
        var h = 0L
        for (c in addr) h = (h * 31 + c.code) and 0xFFFF_FFFFL
        val out = mutableListOf(b)
        for (k in 1..10) {
            val a = (h % 360) * Math.PI / 180 + k * 0.5
            out += Point(addr, fixed6(b.lon + StrictMath.cos(a) * 0.00028 * k), fixed6(b.lat + StrictMath.sin(a) * 0.0002 * k),
                b.rssi - k * 3)
        }
        return out
    }

    /** "just now", "4 min ago", "2 h ago", "3 d ago" relative to [nowMs]. */
    fun agoMs(label: String, nowMs: Long): Long {
        val m = Regex("""(\d+) (min|h|d) ago""").matchEntire(label) ?: return nowMs
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2]) { "min" -> 60_000L; "h" -> 3_600_000L; else -> 86_400_000L }
        return nowMs - n * unit
    }

    fun epochMs(day: String, time: String): Long {
        val t = LocalTime.parse(time, DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return LocalDate.parse(day).atTime(t).atZone(ZONE).toInstant().toEpochMilli()
    }
}
