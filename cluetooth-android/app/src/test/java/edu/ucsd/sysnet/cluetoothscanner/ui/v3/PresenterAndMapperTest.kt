package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.gatt.DisconnectInitiator
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattCharacteristicResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDisconnect
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDiscoveryResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattError
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattOutcome
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattProgress
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattReadValue
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattRequest
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattServiceResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStage
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattTarget
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattTimings
import edu.ucsd.sysnet.cluetoothscanner.local.CategoryProducts
import edu.ucsd.sysnet.cluetoothscanner.local.InRangeSnapshot
import edu.ucsd.sysnet.cluetoothscanner.local.PageKind
import edu.ucsd.sysnet.cluetoothscanner.local.ProductInRange
import edu.ucsd.sysnet.cluetoothscanner.local.Recording
import edu.ucsd.sysnet.cluetoothscanner.local.SeenProduct
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCatalog
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCategory
import edu.ucsd.sysnet.cluetoothscanner.local.WikiPage
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingState
import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattNames
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattPresenter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.NearbyMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.estimatePosition
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.toRecorderUi
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.LatLon
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.SightingPoint
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.StepStatus
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.UploadStatus
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattPresenterTest {
    private val request = GattRequest("id", null, GattTarget("AA:BB:CC:DD:EE:01", "random", null, null), null, "0.1.0", "m", "a")

    private fun result(outcome: GattOutcome, reached: GattStage, error: GattError? = null, services: List<GattServiceResult> = emptyList()) =
        GattDiscoveryResult(
            request, 0, 5_000, outcome, reached, error, GattDisconnect(DisconnectInitiator.LOCAL, null),
            247, null, GattTimings(600, 100, 900, 2_000), services,
        )

    @Test
    fun runningMarksEarlierStagesDoneAndTheCurrentOneActive() {
        val s = GattPresenter.running(GattProgress(GattStage.READ, readsDone = 3, readsTotal = 17)).steps
        assertEquals(listOf(StepStatus.Done, StepStatus.Done, StepStatus.Done, StepStatus.Active, StepStatus.Pending), s.map { it.status })
        assertEquals("3/17", s[3].detail)
        assertEquals(listOf("连接", "协商 MTU", "发现服务", "读取特征", "断开"), s.map { it.label })
    }

    @Test
    fun completeResultListsServicesAndErrorsInRed() {
        val chars = listOf(
            GattCharacteristicResult("2A00", 1, listOf("read"), GattReadValue.ok("WH-1".toByteArray()), emptyList()),
            GattCharacteristicResult("2A25", 2, listOf("read"), GattReadValue(null, GattReadValue.INSUFFICIENT_AUTHENTICATION), emptyList()),
            GattCharacteristicResult("2A19", 3, listOf("read", "notify"), GattReadValue.ok(byteArrayOf(82)), emptyList()),
        )
        val done = GattPresenter.finished(result(GattOutcome.COMPLETE, GattStage.DISCONNECT, services = listOf(GattServiceResult("1800", true, 1, chars))))
        assertTrue(done.steps.all { it.status == StepStatus.Done })
        assertNull(done.summary)
        val c = done.services.single().characteristics
        assertEquals("Generic Access", done.services.single().name)
        assertEquals("\"WH-1\"", c[0].value)
        assertEquals("需要认证", c[1].error)
        assertNull(c[1].value)
        assertEquals("82 %", c[2].value)
        assertEquals("读 通知", c[2].properties)
    }

    @Test
    fun failedAtDiscoverMarksThatStageAndLeavesLaterOnesPending() {
        val done = GattPresenter.finished(result(GattOutcome.FAILED, GattStage.DISCOVER, GattError(GattStage.DISCOVER, 8, "GATT_CONN_TIMEOUT")))
        assertEquals(listOf(StepStatus.Done, StepStatus.Done, StepStatus.Failed, StepStatus.Pending, StepStatus.Pending), done.steps.map { it.status })
        assertEquals("失败 · GATT_CONN_TIMEOUT", done.summary)
    }

    @Test
    fun cancelledIsSaidSo() {
        val done = GattPresenter.finished(result(GattOutcome.CANCELLED, GattStage.READ))
        assertEquals("已取消", done.steps[3].detail)
        assertNotNull(done.summary)
    }

    @Test
    fun readStatusesToWords() {
        assertEquals("需要加密", GattPresenter.errorText(GattReadValue.INSUFFICIENT_ENCRYPTION))
        assertEquals("读取超时", GattPresenter.errorText(GattReadValue.TIMEOUT))
        assertEquals("错误 133", GattPresenter.errorText("error:133"))
        assertNull(GattPresenter.errorText(GattReadValue.NOT_READ))
        assertNull(GattPresenter.errorText(GattReadValue.OK))
    }

    @Test
    fun valueFormatting() {
        assertEquals("01 02 ff", GattNames.valueText("2A01", "0102ff"))
        assertEquals("\"Sony\"", GattNames.valueText("2A29", "536f6e79"))
        assertTrue(GattNames.valueText("x", "00".repeat(40)).endsWith("(40 字节)"))
        assertEquals("(空)", GattNames.valueText("x", ""))
    }
}

class EstimateTest {
    @Test
    fun weightsTowardTheStrongestSightings() {
        val strong = SightingPoint(LatLon(32.0, -117.0), -40)
        val weak = SightingPoint(LatLon(32.001, -117.001), -70)
        val e = estimatePosition(listOf(weak, strong))!!
        assertTrue(e.at.lat < 32.0001 && e.at.lon > -117.0001)
        assertTrue(e.radiusMeters >= 5)
    }

    @Test
    fun noPointsNoEstimate() {
        assertNull(estimatePosition(emptyList()))
    }

    @Test
    fun singlePointIsItsOwnEstimate() {
        val e = estimatePosition(listOf(SightingPoint(LatLon(1.0, 2.0), -60)))!!
        assertEquals(1.0, e.at.lat, 1e-9)
        assertEquals(5, e.radiusMeters)
    }
}

class MapperTest {
    private val catalog = WikiCatalog(
        schema = 1,
        version = "v",
        categories = listOf(WikiCategory("audio-device", "Audio", "音频设备"), WikiCategory("wearable", "Wearable", "可穿戴设备")),
        pages = listOf(
            WikiPage("model--a", "Headset A", PageKind.DEVICE, "A headset. More text.", listOf("audio-device"), null, true),
            WikiPage("model--b", "Band B", PageKind.DEVICE, "A band", listOf("wearable"), null, true),
            WikiPage("cohort--c", "Cohort C", PageKind.IDENTIFIER, "Something", emptyList(), null, true),
        ),
    )

    @Test
    fun recordingStateMapsToTheRecorder() {
        val ui = RecordingState(RecordingMode.PAUSED, 754_000, 1_204).toRecorderUi()
        assertEquals(RecorderPhase.Paused, ui.phase)
        assertEquals(754, ui.elapsedSeconds)
        assertEquals(1_204, ui.sightings)
    }

    @Test
    fun nearbyPutsProductsInRangeFirstThenWhatWasSeenBeforeAndHidesEmptyCategories() {
        val a = catalog.pages[0]
        val b = catalog.pages[1]
        val day = 1_761_000_000_000L // 2025-10-20
        val state = NearbyMapper.map(
            RecorderUiState(),
            InRangeSnapshot(0, 30_000, listOf(ProductInRange(a, 2, -60, 0)), unidentifiedDeviceCount = 4, totalDeviceCount = 6),
            listOf(
                CategoryProducts(catalog.category("audio-device"), listOf(SeenProduct(a, 0, day, 1, 5))),
                CategoryProducts(catalog.category("wearable"), listOf(SeenProduct(b, 0, day, 1, 5))),
                CategoryProducts(null, listOf(SeenProduct(catalog.pages[2], 0, day, 1, 5))),
            ),
            catalog,
            ZoneOffset.UTC,
        )
        assertEquals(listOf("audio-device", "wearable", "other"), state.categories.map { it.id })
        assertEquals("音频设备", state.categories[0].title)
        assertEquals(-60, state.categories[0].items.single().strongestRssi)
        assertEquals("A headset", state.categories[0].items.single().subtitle)
        val band = state.categories[1].items.single()
        assertNull(band.strongestRssi)
        assertEquals("上次 10月20日", band.subtitle)
        assertEquals("未识别", state.unrecognized.title)
        assertEquals("4 台在范围内", state.unrecognized.items.single().subtitle)
    }

    @Test
    fun nearbyWithoutDataHasNoCategories() {
        val state = NearbyMapper.map(RecorderUiState(), null, emptyList(), null)
        assertTrue(state.categories.isEmpty())
        assertTrue(state.unrecognized.items.isEmpty())
    }

    private fun rec(id: String, start: Long, active: Boolean = false) = Recording(id, start, if (active) null else start + 60_000, 60_000, 1_840.0, 12, active)

    @Test
    fun recordingsAreGroupedByDayWithTheCurrentOnePinnedAndUploadStatusMapped() {
        val today = LocalDate.of(2026, 10, 28)
        fun ms(y: Int, m: Int, d: Int, h: Int) = LocalDate.of(y, m, d).atTime(h, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val state = RecordingMapper.state(
            RecorderUiState(RecorderPhase.Recording, 30, 100),
            listOf(rec("now", ms(2026, 10, 28, 9), active = true), rec("y1", ms(2026, 10, 27, 18)), rec("y2", ms(2026, 10, 27, 8)), rec("old", ms(2026, 10, 20, 10))),
            mapOf("y1" to GatewaySessionUploadState.FAILED, "y2" to GatewaySessionUploadState.PENDING, "old" to GatewaySessionUploadState.UPLOADED),
            mapOf("y1" to "校园南门"),
            today,
        )
        assertEquals("now", state.current!!.id)
        assertEquals(listOf("昨天 · 10月27日", "10月20日"), state.days.map { it.label })
        val yesterday = state.days[0].recordings
        assertEquals(listOf("y1", "y2"), yesterday.map { it.id })
        assertEquals(UploadStatus.Failed, yesterday[0].upload)
        assertEquals(UploadStatus.Uploading, yesterday[1].upload)
        assertEquals("校园南门", yesterday[0].note)
        assertEquals(1_840, yesterday[0].distanceMeters)
        assertEquals(UploadStatus.Uploaded, state.days[1].recordings.single().upload)
    }

    @Test
    fun dayLabels() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals("今天 · 10月28日", RecordingMapper.dayLabel(today, today))
        assertEquals("2025年12月3日", RecordingMapper.dayLabel(LocalDate.of(2025, 12, 3), today))
    }
}
