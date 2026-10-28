package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.core.GatewaySessionUploadState
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
import edu.ucsd.sysnet.cluetoothscanner.local.LocalDataStatus
import edu.ucsd.sysnet.cluetoothscanner.local.PageKind
import edu.ucsd.sysnet.cluetoothscanner.local.ProductInRange
import edu.ucsd.sysnet.cluetoothscanner.local.Recording
import edu.ucsd.sysnet.cluetoothscanner.local.SeenProduct
import edu.ucsd.sysnet.cluetoothscanner.local.SeenUnknown
import edu.ucsd.sysnet.cluetoothscanner.local.StructureKey
import edu.ucsd.sysnet.cluetoothscanner.local.StructureKind
import edu.ucsd.sysnet.cluetoothscanner.local.UnknownInRange
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCatalog
import edu.ucsd.sysnet.cluetoothscanner.local.WikiCategory
import edu.ucsd.sysnet.cluetoothscanner.local.WikiPage
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingMode
import edu.ucsd.sysnet.cluetoothscanner.service.RecordingState
import edu.ucsd.sysnet.cluetoothscanner.service.ScanOperatingState
import edu.ucsd.sysnet.cluetoothscanner.ui.ScanTransitionState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.DiscoverMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattNames
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.GattPresenter
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.NearbyMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.Notices
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingMapper
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.data.RecordingOutcome
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
        val s = GattPresenter.running(GattProgress(GattStage.READ, readsDone = 3, readsTotal = 17), TestTexts).steps
        assertEquals(listOf(StepStatus.Done, StepStatus.Done, StepStatus.Done, StepStatus.Active, StepStatus.Pending), s.map { it.status })
        assertEquals("3/17", s[3].detail)
        assertEquals(listOf("Connect", "Negotiate MTU", "Discover services", "Read characteristics", "Disconnect"), s.map { it.label })
    }

    @Test
    fun completeResultListsServicesAndErrorsInRed() {
        val chars = listOf(
            GattCharacteristicResult("2A00", 1, listOf("read"), GattReadValue.ok("WH-1".toByteArray()), emptyList()),
            GattCharacteristicResult("2A25", 2, listOf("read"), GattReadValue(null, GattReadValue.INSUFFICIENT_AUTHENTICATION), emptyList()),
            GattCharacteristicResult("2A19", 3, listOf("read", "notify"), GattReadValue.ok(byteArrayOf(82)), emptyList()),
        )
        val done = GattPresenter.finished(result(GattOutcome.COMPLETE, GattStage.DISCONNECT, services = listOf(GattServiceResult("1800", true, 1, chars))), TestTexts)
        assertTrue(done.steps.all { it.status == StepStatus.Done })
        assertNull(done.summary)
        val c = done.services.single().characteristics
        assertEquals("Generic Access", done.services.single().name)
        assertEquals("\"WH-1\"", c[0].value)
        assertEquals("Requires authentication", c[1].error)
        assertNull(c[1].value)
        assertEquals("82 %", c[2].value)
        assertEquals("Read Notify", c[2].properties)
    }

    @Test
    fun failedAtDiscoverMarksThatStageAndLeavesLaterOnesPending() {
        val done = GattPresenter.finished(result(GattOutcome.FAILED, GattStage.DISCOVER, GattError(GattStage.DISCOVER, 8, "GATT_CONN_TIMEOUT")), TestTexts)
        assertEquals(listOf(StepStatus.Done, StepStatus.Done, StepStatus.Failed, StepStatus.Pending, StepStatus.Pending), done.steps.map { it.status })
        assertEquals("Failed · GATT_CONN_TIMEOUT", done.summary)
    }

    @Test
    fun cancelledIsSaidSo() {
        val done = GattPresenter.finished(result(GattOutcome.CANCELLED, GattStage.READ), TestTexts)
        assertEquals("Cancelled", done.steps[3].detail)
        assertNotNull(done.summary)
    }

    @Test
    fun readStatusesToWords() {
        assertEquals("Requires encryption", GattPresenter.errorText(GattReadValue.INSUFFICIENT_ENCRYPTION, TestTexts))
        assertEquals("Read timed out", GattPresenter.errorText(GattReadValue.TIMEOUT, TestTexts))
        assertEquals("Error 133", GattPresenter.errorText("error:133", TestTexts))
        assertNull(GattPresenter.errorText(GattReadValue.NOT_READ, TestTexts))
        assertNull(GattPresenter.errorText(GattReadValue.OK, TestTexts))
    }

    @Test
    fun valueFormatting() {
        assertEquals("01 02 ff", GattNames.valueText("2A01", "0102ff", TestTexts))
        assertEquals("\"Sony\"", GattNames.valueText("2A29", "536f6e79", TestTexts))
        assertTrue(GattNames.valueText("x", "00".repeat(40), TestTexts).endsWith("(40 bytes)"))
        assertEquals("(empty)", GattNames.valueText("x", "", TestTexts))
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
        categories = listOf(WikiCategory("audio-device", "Audio device"), WikiCategory("wearable", "Wearable")),
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
            TestTexts,
            ZoneOffset.UTC,
        )
        assertEquals(listOf("audio-device", "wearable", "other"), state.categories.map { it.id })
        assertEquals("Audio device", state.categories[0].title)
        assertEquals(-60, state.categories[0].items.single().strongestRssi)
        assertEquals("A headset", state.categories[0].items.single().subtitle)
        val band = state.categories[1].items.single()
        assertNull(band.strongestRssi)
        assertEquals("Last seen Oct 20", band.subtitle)
        assertEquals("Unidentified", state.unrecognized.title)
        assertTrue(state.unrecognized.items.isEmpty())
    }

    @Test
    fun unidentifiedStructuresAreGroupedAndLabelledInRangeFirstThenSeenBefore() {
        val day = 1_761_000_000_000L // 2025-10-20
        val state = NearbyMapper.map(
            RecorderUiState(), null, emptyList(), catalog, TestTexts, ZoneOffset.UTC,
            unknownNow = listOf(
                UnknownInRange(StructureKey(StructureKind.SERVICE_UUID, "FEED"), 4, -70, 0),
                UnknownInRange(StructureKey(StructureKind.COMPANY, "76"), 6, -55, 0),
            ),
            unknownSeen = listOf(
                SeenUnknown(StructureKey(StructureKind.COMPANY, "76"), 9, 100, 0, day),
                SeenUnknown(StructureKey(StructureKind.LOCAL_NAME, "ELK"), 2, 10, 0, day),
            ),
        )
        val items = state.unrecognized.items
        assertEquals(listOf("Company 0x004C ×6", "Service FEED ×4", "Name ELK… ×2"), items.map { it.name })
        assertEquals(-55, items[0].strongestRssi)
        assertEquals("6 devices in range", items[0].subtitle)
        assertNull(items[2].strongestRssi)
        assertEquals("Last seen Oct 20", items[2].subtitle)
        assertEquals("unknown:company:76", items[0].id)
        assertEquals("Shape 01/1,FF/7", NearbyMapper.structureLabel(StructureKey(StructureKind.AD_SHAPE, "01/1,FF/7"), TestTexts))
    }

    @Test
    fun nearbyWithoutDataHasNoCategories() {
        val state = NearbyMapper.map(RecorderUiState(), null, emptyList(), null, TestTexts)
        assertTrue(state.categories.isEmpty())
        assertTrue(state.unrecognized.items.isEmpty())
    }

    @Test
    fun agoReadsInMinutesHoursDaysThenTheDate() {
        val now = 1_761_000_000_000L // 2025-10-20
        assertEquals("just now", DiscoverMapper.ago(now - 30_000, now, TestTexts, ZoneOffset.UTC))
        assertEquals("5 min ago", DiscoverMapper.ago(now - 5 * 60_000, now, TestTexts, ZoneOffset.UTC))
        assertEquals("3 h ago", DiscoverMapper.ago(now - 3 * 3_600_000, now, TestTexts, ZoneOffset.UTC))
        assertEquals("2 d ago", DiscoverMapper.ago(now - 2 * 86_400_000, now, TestTexts, ZoneOffset.UTC))
        assertEquals("Oct 10", DiscoverMapper.ago(now - 10 * 86_400_000, now, TestTexts, ZoneOffset.UTC))
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
            mapOf("y1" to "South Gate"),
            TestTexts,
            today,
        )
        assertEquals("now", state.current!!.id)
        assertEquals(listOf("Yesterday · Oct 27", "Oct 20"), state.days.map { it.label })
        val yesterday = state.days[0].recordings
        assertEquals(listOf("y1", "y2"), yesterday.map { it.id })
        assertEquals(UploadStatus.Failed, yesterday[0].upload)
        assertEquals(UploadStatus.Uploading, yesterday[1].upload)
        assertEquals("South Gate", yesterday[0].note)
        assertEquals(1_840, yesterday[0].distanceMeters)
        assertEquals(UploadStatus.Uploaded, state.days[1].recordings.single().upload)
    }

    @Test
    fun noDataRecordingsAreHiddenButTheCurrentOneNeverIs() {
        val today = LocalDate.of(2026, 10, 28)
        fun ms(d: Int, h: Int) = LocalDate.of(2026, 10, d).atTime(h, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val recorder = RecorderUiState(RecorderPhase.Recording, 30, 100)
        val state = RecordingMapper.state(
            recorder,
            listOf(rec("now", ms(28, 9), active = true), rec("empty", ms(28, 8)), rec("kept", ms(28, 7)), rec("allEmpty", ms(27, 7))),
            // The core may already call the running session empty; it stays pinned anyway.
            mapOf("now" to GatewaySessionUploadState.EMPTY, "empty" to GatewaySessionUploadState.EMPTY, "allEmpty" to GatewaySessionUploadState.EMPTY),
            emptyMap(),
            TestTexts,
            today,
        )
        assertEquals("now", state.current!!.id)
        // A day left with only no-data recordings disappears with them.
        assertEquals(listOf("Today · Oct 28"), state.days.map { it.label })
        assertEquals(listOf("kept"), state.days.single().recordings.map { it.id })

        val none = RecordingMapper.state(
            RecorderUiState(RecorderPhase.Idle, 0, 0), listOf(rec("e", ms(28, 8))), mapOf("e" to GatewaySessionUploadState.EMPTY), emptyMap(), TestTexts, today,
        )
        assertEquals(null, none.current)
        assertEquals(emptyList<Any>(), none.days)
    }

    @Test
    fun anUploadTheCoreHasNotReportedIsNeverShownAsUploaded() {
        val r = rec("x", 1_000_000)
        assertEquals(UploadStatus.Unknown, RecordingMapper.summary(r, null, null).upload)
        assertEquals(UploadStatus.Empty, RecordingMapper.summary(r, GatewaySessionUploadState.EMPTY, null).upload)
        assertEquals(UploadStatus.Uploaded, RecordingMapper.summary(r, GatewaySessionUploadState.UPLOADED, null).upload)
        // The active recording is uploading whatever the map says.
        assertEquals(UploadStatus.Uploading, RecordingMapper.summary(rec("n", 1, active = true), null, null).upload)
    }

    @Test
    fun dayLabels() {
        val today = LocalDate.of(2026, 10, 28)
        assertEquals("Today · Oct 28", RecordingMapper.dayLabel(today, today, TestTexts))
        assertEquals("Dec 3, 2025", RecordingMapper.dayLabel(LocalDate.of(2025, 12, 3), today, TestTexts))
    }
}

class NoticesTest {
    private fun text(id: Int?) = id?.let { TestTexts.string(it) }

    @Test
    fun copyIsExact() {
        assertEquals("Bluetooth is off. Turn it on to keep scanning", text(Notices.scanner(ScanOperatingState.BLUETOOTH_OFF)))
        assertEquals("Bluetooth scanning is unavailable. Retrying", text(Notices.scanner(ScanOperatingState.SCANNER_UNAVAILABLE)))
        assertEquals("Saving data. Scanning resumes automatically", text(Notices.scanner(ScanOperatingState.PAUSED_BACKPRESSURE)))
        assertEquals("Can't start scanning. Check Bluetooth permissions", text(Notices.scanner(ScanOperatingState.FAILED)))
        assertNull(Notices.scanner(ScanOperatingState.SCANNING))
        assertEquals("Local database unavailable. History isn't being recorded", text(Notices.data(LocalDataStatus.State.UNAVAILABLE)))
        assertEquals("Product identification unavailable", text(Notices.data(LocalDataStatus.State.IDENTIFICATION_UNAVAILABLE)))
        assertNull(Notices.data(LocalDataStatus.State.READY))
    }

    @Test
    fun locationNoticeOnlyWhileRecordingAndOnlyForNoFixOrAStaleFix() {
        assertNull(Notices.location(recording = false, fixAgeMs = null))
        assertEquals("No location fix yet. The route isn't being recorded", text(Notices.location(true, null)))
        assertNull(Notices.location(true, 59_000))
        assertNull(Notices.location(true, 60_000))
        assertEquals("No location update for over a minute. The route is paused", text(Notices.location(true, 60_001)))
    }
}

class RecordingOutcomeTest {
    @Test
    fun aSealedRecordingSaysNothing() {
        // No message for success, however the stop was reached.
        for (previous in ScanTransitionState.entries) {
            for (startSeen in listOf(true, false)) {
                assertEquals(
                    RecordingOutcome.Kind.None,
                    RecordingOutcome.atStop(previous, startSeen, recorded = true),
                )
            }
        }
    }

    @Test
    fun aStartThatNeverRanIsAFailedStart() {
        assertEquals(
            RecordingOutcome.Kind.StartFailed,
            RecordingOutcome.atStop(ScanTransitionState.STARTING, startSeen = true, recorded = false),
        )
    }

    @Test
    fun aRejectedScannerIsReportedOnlyWhenTheStartWasSeen() {
        assertEquals(
            RecordingOutcome.Kind.ScannerRejected,
            RecordingOutcome.atStop(ScanTransitionState.FINALIZING, startSeen = true, recorded = false),
        )
        // A ViewModel created mid-finalization joins at FINALIZING and never saw a start: say nothing.
        assertEquals(
            RecordingOutcome.Kind.None,
            RecordingOutcome.atStop(ScanTransitionState.FINALIZING, startSeen = false, recorded = false),
        )
    }
}
