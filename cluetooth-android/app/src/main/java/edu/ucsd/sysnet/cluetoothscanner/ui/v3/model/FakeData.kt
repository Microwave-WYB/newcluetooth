package edu.ucsd.sysnet.cluetoothscanner.ui.v3.model

import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Hand-made data for previews and early wiring. Nothing here is real. */
object FakeData {
    val idle = RecorderUiState(RecorderPhase.Idle)
    val recording = RecorderUiState(RecorderPhase.Recording, elapsedSeconds = 12 * 60 + 34, sightings = 1_204)
    val paused = RecorderUiState(RecorderPhase.Paused, elapsedSeconds = 12 * 60 + 34, sightings = 1_204)

    private fun history(seed: Int, base: Int, wobble: Int = 4, n: Int = 24): List<Int> {
        val r = Random(seed)
        return List(n) { (base + r.nextInt(-wobble, wobble + 1)).coerceIn(-100, -35) }
    }

    private fun row(
        seed: Int,
        name: String?,
        mac: String,
        rssi: Int,
        address: AddressType = AddressType.ResolvablePrivate,
        kind: AdvKind = AdvKind.Legacy,
        connectable: Boolean = true,
    ) = DeviceRowState(
        id = mac,
        name = name,
        mac = mac,
        addressType = address,
        advKind = kind,
        connectable = connectable,
        rssi = rssi,
        history = history(seed, rssi).dropLast(1) + rssi,
    )

    /** Sorted by RSSI; the last two arrived after the sort and are appended, so they are out of order. */
    val scanRows: List<DeviceRowState> = listOf(
        row(1, "Mi Smart Band 8", "C8:0F:10:3A:91:5E", -48, AddressType.Public),
        row(2, "WH-1000XM5", "88:C9:E8:20:4B:D7", -53, AddressType.Public),
        row(3, null, "5B:21:7C:E0:19:A4", -57),
        row(4, "Tile", "D4:6E:0E:7F:33:C2", -61, AddressType.RandomStatic),
        row(5, "JBL Flip 6", "F8:5C:7D:09:AA:16", -66, AddressType.Public),
        row(6, null, "4F:90:B3:5D:62:0E", -69, kind = AdvKind.Extended, connectable = false),
        row(7, "Galaxy Buds2 Pro", "A0:7D:9C:E4:18:3B", -72),
        row(8, "Govee_H6159_8C2A", "E3:11:08:8C:2A:7F", -74, AddressType.RandomStatic),
        row(9, null, "6D:A3:44:1B:F0:92", -77, connectable = false),
        row(10, "[TV] Samsung 7 Series", "B4:3A:28:6E:C1:05", -79, AddressType.Public),
        row(11, null, "7C:08:E5:12:AD:63", -82, kind = AdvKind.Extended),
        row(12, "LE-Bose QC45", "2C:41:A1:9F:50:D8", -85, AddressType.Public),
        row(13, null, "52:E6:19:C7:04:BB", -88, connectable = false),
        row(14, "ELK-BLEDOM", "BE:67:00:21:F4:0A", -91, AddressType.RandomStatic),
        row(15, "Fitbit Charge 5", "DC:2C:6E:73:08:41", -58, AddressType.Public),
        row(16, null, "6A:F2:30:BD:97:1C", -63),
    )

    val scan = ScanUiState(scanRows, idle)

    // ---- Device detail ----

    private val headphoneStructures = listOf(
        AdStructureRow("01", "Flags", 2, "06"),
        AdStructureRow("09", "Complete Local Name", 11, "57482D31303030584D35"),
        AdStructureRow("FF", "Manufacturer Specific", 8, "2D0103010A824C"),
        AdStructureRow("03", "Complete 16-bit UUIDs", 5, "0EFE0D18"),
    )

    private val variants = listOf(
        AdVariantState("v1", "ADV_IND", "1M", null, 1_284, "14:02:11", "14:31:48", -71, -50, headphoneStructures),
        AdVariantState(
            "v2", "SCAN_RSP", "1M", null, 1_279, "14:02:11", "14:31:48", -71, -50,
            listOf(AdStructureRow("09", "Complete Local Name", 11, "57482D31303030584D35")),
        ),
        AdVariantState(
            "v3", "ADV_EXT_IND", "2M", 3, 96, "14:19:02", "14:31:20", -66, -52,
            listOf(
                AdStructureRow("01", "Flags", 2, "06"),
                AdStructureRow("FF", "Manufacturer Specific", 8, "2D0103010A824C"),
            ),
        ),
    )

    private fun chart(seed: Int, base: Int, n: Int) = history(seed, base, wobble = 6, n = n)

    private val doneSteps = listOf(
        GattStepState("连接", StepStatus.Done, "0.6 s"),
        GattStepState("协商 MTU", StepStatus.Done, "247"),
        GattStepState("发现服务", StepStatus.Done, "5 个服务"),
        GattStepState("读取特征", StepStatus.Done, "17 个特征"),
        GattStepState("断开", StepStatus.Done),
    )

    private val progressSteps = listOf(
        GattStepState("连接", StepStatus.Done, "0.6 s"),
        GattStepState("协商 MTU", StepStatus.Done, "247"),
        GattStepState("发现服务", StepStatus.Active, "已找到 3 个"),
        GattStepState("读取特征", StepStatus.Pending),
        GattStepState("断开", StepStatus.Pending),
    )

    private val gattServices = listOf(
        GattServiceState(
            "1800", "Generic Access",
            listOf(
                GattCharState("2A00", "Device Name", "读", "\"WH-1000XM5\"", null),
                GattCharState("2A01", "Appearance", "读", "0x0941 (Headset)", null),
            ),
        ),
        GattServiceState(
            "180A", "Device Information",
            listOf(
                GattCharState("2A29", "Manufacturer Name String", "读", "\"Sony Corporation\"", null),
                GattCharState("2A24", "Model Number String", "读", "\"WH-1000XM5\"", null),
                GattCharState("2A25", "Serial Number String", "读", null, "需要认证"),
                GattCharState("2A26", "Firmware Revision String", "读", "\"4.5.1\"", null),
            ),
        ),
        GattServiceState(
            "180F", "Battery Service",
            listOf(GattCharState("2A19", "Battery Level", "读 通知", "82 %", null)),
        ),
        GattServiceState(
            "96cc203e-5068-46ad-b32d-e316f0d6ef89", null,
            listOf(
                GattCharState("96cc203e-5068-46ad-b32d-e316f0d6ef8a", null, "写", null, "需要加密"),
                GattCharState("96cc203e-5068-46ad-b32d-e316f0d6ef8b", null, "通知", null, null),
            ),
        ),
    )

    private val wiki = WikiUiState(
        family = "Sony 音频设备广播",
        summary = "Sony 头戴式与入耳式耳机共用同一种广播布局：company field 301，带 Fast Pair 服务数据。",
        facts = listOf(
            "本地名称" to "WH-1000XM5 / WF-1000XM5",
            "公司字段" to "company field 301",
            "服务 UUID" to "FE2C, 0E0D",
            "AD 形状" to "01/2,09/11,FF/8,03/5",
        ),
        confidence = "候选，不是已认证的身份",
    )

    fun detail(
        device: DeviceRowState = scanRows[1],
        gatt: GattUiState = GattUiState.NotStarted,
        window: ChartWindow = ChartWindow.FiveMinutes,
        wiki: WikiUiState? = this.wiki,
        gattDisabledReason: String? = null,
    ) = DeviceDetailUiState(
        device = device,
        variants = variants,
        chartWindow = window,
        chartSamples = chart(device.id.hashCode(), device.rssi, n = 60),
        gatt = gatt,
        wiki = wiki,
        gattDisabledReason = gattDisabledReason,
    )

    val detailHalf = detail()
    val detailGattProgress = detail(gatt = GattUiState.InProgress(progressSteps))
    val detailGattDone = detail(gatt = GattUiState.Done(doneSteps, gattServices))
    val detailNotConnectable = detail(
        device = scanRows[5],
        wiki = null,
        gattDisabledReason = "该设备的广播不可连接，无法发现 GATT",
    )

    // ---- Nearby ----

    private fun product(id: String, name: String, subtitle: String, rssi: Int?) =
        ProductItemState(id, name, subtitle, rssi)

    private const val LAST_SEEN = "上次 10月21日"

    val nearby = NearbyUiState(
        recorder = idle,
        categories = listOf(
            NearbyCategoryState(
                "audio", "耳机与音箱", CategoryIcon.Headphones,
                listOf(
                    product("a1", "WH-1000XM5", "Sony · 索尼", -53),
                    product("a2", "Galaxy Buds2 Pro", "Samsung · 三星", -72),
                    product("a3", "JBL Flip 6", "JBL · 便携音箱", -66),
                    product("a4", "AirPods Pro", "Apple · 苹果", null).copy(subtitle = LAST_SEEN),
                    product("a5", "QC45", "Bose", null).copy(subtitle = LAST_SEEN),
                    product("a6", "Beats Flex", "Apple · Beats", null).copy(subtitle = LAST_SEEN),
                ),
            ),
            NearbyCategoryState(
                "wear", "手环与手表", CategoryIcon.Watch,
                listOf(
                    product("w1", "Mi Smart Band 8", "小米 · 手环", -48),
                    product("w2", "Charge 5", "Fitbit · 手环", -58),
                    product("w3", "Watch 4", "Samsung · 手表", null).copy(subtitle = LAST_SEEN),
                    product("w4", "Forerunner 255", "Garmin", null).copy(subtitle = LAST_SEEN),
                ),
            ),
            NearbyCategoryState(
                "track", "防丢追踪器", CategoryIcon.Tracker,
                listOf(
                    product("t1", "Tile Mate", "Tile", -61),
                    product("t2", "AirTag", "Apple · 苹果", -84),
                    product("t3", "SmartTag2", "Samsung · 三星", null).copy(subtitle = LAST_SEEN),
                ),
            ),
            NearbyCategoryState(
                "home", "智能家居", CategoryIcon.Home,
                listOf(
                    product("h1", "H6159 灯带", "Govee", -74),
                    product("h2", "三星电视 7 系", "Samsung · 电视", -79),
                    product("h3", "Hue Bridge", "Philips", null).copy(subtitle = LAST_SEEN),
                ),
            ),
            NearbyCategoryState("car", "车载设备", CategoryIcon.Car, emptyList()),
        ).filter { it.items.isNotEmpty() },
        unrecognized = NearbyCategoryState(
            "unknown", "未识别", CategoryIcon.Unknown,
            listOf(
                product("u1", "结构 3f9a", "9 台设备", -69),
                product("u2", "结构 c102", "4 台设备", -82),
                product("u3", "结构 07be", "2 台设备", null).copy(subtitle = "上次 10月24日"),
                product("u4", "结构 91d4", "1 台设备", null).copy(subtitle = "上次 10月19日"),
            ),
        ),
    )

    val nearbyRecording = nearby.copy(recorder = recording)
    val nearbyPaused = nearby.copy(recorder = paused)
    val nearbyAudio: NearbyCategoryState = nearby.categories.first()

    // ---- Recordings ----

    private fun rec(
        id: String, month: Int, day: Int, h: Int, m: Int, minutes: Int, meters: Int, devices: Int,
        upload: UploadStatus, note: String? = null,
    ) = RecordingSummaryState(
        id, LocalDateTime.of(2026, month, day, h, m), minutes * 60, meters, devices, upload, note,
    )

    val currentRecording = RecordingSummaryState(
        "cur", LocalDateTime.of(2026, 10, 28, 9, 41), recording.elapsedSeconds, 640, 58,
        UploadStatus.Uploading, null,
    )

    val recordings = RecordingsUiState(
        recorder = recording,
        current = currentRecording,
        days = listOf(
            RecordingDayState(
                "昨天 · 10月27日",
                listOf(
                    rec("r1", 10, 27, 18, 5, 23, 1_840, 214, UploadStatus.Uploading, "校园南门到图书馆"),
                    rec("r2", 10, 27, 12, 30, 41, 3_200, 389, UploadStatus.Uploaded),
                    rec("r3", 10, 27, 8, 52, 9, 410, 77, UploadStatus.Failed, "手机没电中断"),
                ),
            ),
            RecordingDayState(
                "10月26日",
                listOf(
                    rec("r4", 10, 26, 15, 14, 62, 5_900, 512, UploadStatus.Uploaded, "医院周边一圈"),
                    rec("r5", 10, 26, 10, 3, 17, 980, 130, UploadStatus.Uploaded),
                ),
            ),
            RecordingDayState(
                "10月24日",
                listOf(rec("r6", 10, 24, 19, 40, 35, 2_450, 267, UploadStatus.Uploaded)),
            ),
        ),
    )

    val recordingsIdle = recordings.copy(recorder = idle, current = null)

    // ---- Maps ----

    /** Unit-square coordinates to a made-up spot on a campus-sized patch. */
    private fun geo(x: Float, y: Float) = LatLon(32.8900 - y * 0.008, -117.2400 + x * 0.012)

    val route: List<LatLon> = listOf(
        geo(0.14f, 0.18f), geo(0.20f, 0.20f), geo(0.31f, 0.21f), geo(0.42f, 0.24f),
        geo(0.50f, 0.33f), geo(0.52f, 0.43f), geo(0.60f, 0.50f), geo(0.70f, 0.52f),
        geo(0.78f, 0.58f), geo(0.80f, 0.68f), geo(0.74f, 0.76f), geo(0.62f, 0.80f),
    )

    private fun ring(cx: Float, cy: Float, r: Float, n: Int, seed: Int, rssiBase: Int): List<SightingPoint> {
        val rnd = Random(seed)
        return List(n) { i ->
            val a = 2 * PI * i / n + rnd.nextDouble(-0.3, 0.3)
            val d = r * (0.3f + rnd.nextFloat())
            val dist = d / r
            SightingPoint(
                geo(cx + (d * cos(a)).toFloat(), cy + (d * sin(a)).toFloat()),
                (rssiBase - dist * 24 + rnd.nextInt(-3, 4)).toInt(),
            )
        }
    }

    private val selectedPoints = ring(0.54f, 0.42f, 0.17f, 16, 7, -46)

    private val mapRows = listOf(
        MapListRowState("m1", "WH-1000XM5", "88:C9:E8:20:4B:D7", "412 次", -49),
        MapListRowState("m2", "Mi Smart Band 8", "C8:0F:10:3A:91:5E", "380 次", -52),
        MapListRowState("m3", "未命名", "5B:21:7C:E0:19:A4", "96 次", -58),
        MapListRowState("m4", "Tile", "D4:6E:0E:7F:33:C2", "211 次", -63),
        MapListRowState("m5", "JBL Flip 6", "F8:5C:7D:09:AA:16", "54 次", -68),
        MapListRowState("m6", "未命名", "4F:90:B3:5D:62:0E", "33 次", -74),
        MapListRowState("m7", "Govee_H6159_8C2A", "E3:11:08:8C:2A:7F", "120 次", -79),
        MapListRowState("m8", "[TV] Samsung 7 Series", "B4:3A:28:6E:C1:05", "18 次", -86),
    )

    private val heat: List<LatLon> = buildList {
        val rnd = Random(11)
        route.zipWithNext().forEach { (a, b) ->
            repeat(10) {
                val t = rnd.nextDouble()
                add(LatLon(a.lat + (b.lat - a.lat) * t + rnd.nextDouble() * 0.0003, a.lon + (b.lon - a.lon) * t + rnd.nextDouble() * 0.0004))
            }
        }
        repeat(30) { add(geo(0.54f + rnd.nextFloat() * 0.1f - 0.05f, 0.42f + rnd.nextFloat() * 0.1f - 0.05f)) }
    }

    val recordingMap = MapUiState(
        mode = MapMode.Recording,
        title = "10月27日 18:05",
        subtitle = "23 分钟 · 1.8 km · 214 台设备",
        recorder = idle,
        route = route,
        rows = mapRows,
        sort = DeviceSort.Signal,
        productPoints = emptyList(),
        selected = null,
        selectedPoints = emptyList(),
        estimate = null,
        showAreaChip = false,
        heatmap = false,
        heat = emptyList(),
    )

    val recordingMapSelected = recordingMap.copy(
        selected = detail(),
        selectedPoints = selectedPoints,
        estimate = geo(0.545f, 0.425f),
        estimateAccuracyMeters = 12,
    )

    val recordingMapPanned = recordingMap.copy(showAreaChip = true, heatmap = true, heat = heat)

    val productMap = MapUiState(
        mode = MapMode.Product,
        title = "WH-1000XM5",
        subtitle = "Sony · 3 个 MAC · 来自 4 次录制",
        recorder = idle,
        route = emptyList(),
        rows = listOf(
            MapListRowState("p1", "WH-1000XM5", "88:C9:E8:20:4B:D7", "3 次录制 · 最近 10月27日", -49),
            MapListRowState("p2", "WH-1000XM5", "88:C9:E8:20:4B:E1", "1 次录制 · 10月24日", -71),
            MapListRowState("p3", "WH-1000XM5", "88:C9:E8:77:0A:3C", "1 次录制 · 10月19日", -83),
        ),
        sort = DeviceSort.Signal,
        productPoints = ring(0.36f, 0.35f, 0.1f, 12, 21, -55) +
            ring(0.66f, 0.62f, 0.08f, 9, 22, -62) +
            ring(0.28f, 0.72f, 0.05f, 4, 23, -74),
        selected = null,
        selectedPoints = emptyList(),
        estimate = null,
        showAreaChip = false,
        heatmap = false,
        heat = emptyList(),
    )
}
