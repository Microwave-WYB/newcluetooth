package edu.ucsd.sysnet.cluetoothscanner.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailSourceInvariantTest {
    @Test
    fun uploadStatusScreenUsesPlainLanguageExceptionalStatesOnly() {
        val source = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/screen/UploadStatusScreen.kt").readText()

        assertTrue(source.contains("Some scans could not be uploaded. Try again."))
        assertTrue(source.contains("Route map unavailable."))
        assertTrue(source.contains("Choose a file format."))
        assertTrue(source.contains("stored on device"))

        assertFalse(source.contains("add a restricted Android Maps SDK key"))
        assertFalse(source.contains("Choose an export format"))
        assertFalse(source.contains("hexadecimal"))
        assertFalse(source.contains("error.lineSequence()"))
        assertFalse(source.contains("take(160)"))
        assertFalse(source.contains("Retained local size"))
        assertFalse(source.contains("retained locally"))
        assertFalse(source.contains("Retained legacy scan session"))
    }

    @Test
    fun sessionSelectionIsByIdAndDerivedFromLatestSessions() {
        val source = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/screen/UploadStatusScreen.kt").readText()

        assertTrue(source.contains("var selectedId by remember { mutableStateOf<String?>(null) }"))
        assertTrue(source.contains("sessions.firstOrNull { it.id == id }"))
        assertTrue(source.contains("selectedId = null"))
        assertTrue(source.contains("onOpen = { selectedId = item.id }"))
        assertTrue(source.contains("rememberMinuteTicker(isActive = session?.status == GatewaySessionStatus.ACTIVE)"))
        assertTrue(source.contains("if (!isActive) return@LaunchedEffect"))
    }

    @Test
    fun detailUsesSummaryTableAndKeepsAnalystFacingStructures() {
        val source = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/screen/UploadStatusScreen.kt").readText()

        assertTrue(source.contains("SectionHeading(\"Session summary\")"))
        assertTrue(source.contains("SummaryTable(sessionSummaryRows(item, nowMs = nowMs))"))
        assertTrue(source.contains("SectionHeading(\"Advertisement structures\")"))
        assertTrue(source.contains("AdvertisementStructureTable("))
        assertTrue(source.contains("heightIn(min = 48.dp)"))
        assertTrue(source.contains("secondaryContainer"))
        assertTrue(source.contains("session.clusters.isNotEmpty()"))
    }

    @Test
    fun sessionDetailModelAvoidsInternalJargon() {
        val source = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/screen/SessionDetailModel.kt").readText()

        assertTrue(source.contains("\"Imported scan\""))
        assertTrue(source.contains("\"Stored on device\""))
        assertTrue(source.contains("\"In progress\""))
        assertTrue(source.contains("\"Not available\""))

        assertFalse(source.contains("\"Legacy\""))
        assertFalse(source.contains("Retained local size"))
        assertFalse(source.contains("retained locally"))
        assertFalse(source.contains("\"BLE:\""))
    }

    private fun mainSource(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java", relativePath),
            File("app/src/main/java", relativePath),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate Android main source: $relativePath")
    }
}
