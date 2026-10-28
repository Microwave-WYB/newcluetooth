package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.RecordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.devicesText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.formatDuration
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.recordPlacement
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.components.rowsText
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.AppTab
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.RecorderPhase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPlacementTest {
    @Test
    fun idleIsAlwaysTheRoundButton() {
        assertEquals(RecordPlacement.Round, recordPlacement(RecorderPhase.Idle, roomForExpanded = true))
        assertEquals(RecordPlacement.Round, recordPlacement(RecorderPhase.Idle, roomForExpanded = false))
    }

    @Test
    fun recordingIsExpandedOnlyWhereThereIsRoomAndOtherwiseTheFloatingPill() {
        for (phase in listOf(RecorderPhase.Recording, RecorderPhase.Paused)) {
            assertEquals(RecordPlacement.Expanded, recordPlacement(phase, roomForExpanded = true))
            assertEquals(RecordPlacement.Pill, recordPlacement(phase, roomForExpanded = false))
        }
    }
}

class UiCopyTest {
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun theUiIsEnglishOnly() {
        assertFalse("no CJK text in strings.xml", strings.any { it.code in 0x4e00..0x9fff })
        assertFalse("values-zh must not exist", File("src/main/res/values-zh").exists())
    }

    @Test
    fun noStatusTextSaysRecordingOrNotRecording() {
        // The status lives in the record button, never in a subtitle or label.
        val statusLike = Regex("""^(Not recording|Recording|Paused)( ·.*)?$""")
        val values = Regex(""">([^<]*)</(string|item)>""").findAll(strings).map { it.groupValues[1] }
        values.filter { statusLike.matches(it) && it != "Paused" }.toList().let {
            // The foreground-service notification and its channel are system UI, not the app's screens.
            assertEquals(listOf("Recording", "Recording"), it)
        }
    }

    @Test
    fun theThirdTabIsHistory() {
        assertEquals("History", TestTexts.string(AppTab.History.label))
        assertEquals(listOf("Scan", "Nearby", "History"), AppTab.entries.map { TestTexts.string(it.label) })
    }

    @Test
    fun countsUseOneAndManyForms() {
        assertEquals("1 device", devicesText(1, TestTexts))
        assertEquals("48 devices", devicesText(48, TestTexts))
        assertEquals("1 row", rowsText(1, TestTexts))
        assertEquals("1.4k rows", rowsText(1_400, TestTexts))
        assertEquals("45 s", formatDuration(45, TestTexts))
        assertEquals("23 min", formatDuration(23 * 60, TestTexts))
    }

    @Test
    fun everyDeclaredStringResolves() {
        val names = Regex("""<string name="(\w+)"""").findAll(strings).map { it.groupValues[1] }.toList()
        assertTrue(names.size > 100)
        for (name in names) {
            val id = R.string::class.java.getField(name).getInt(null)
            assertTrue(name, runCatching { TestTexts.string(id, 1, 1, 1) }.isSuccess)
        }
    }
}
