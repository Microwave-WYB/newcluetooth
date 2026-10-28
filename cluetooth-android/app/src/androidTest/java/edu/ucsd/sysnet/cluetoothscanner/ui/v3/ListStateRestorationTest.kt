package edu.ucsd.sysnet.cluetoothscanner.ui.v3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.FakeData
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.MapListRowState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.HistoryContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapActions
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapContent
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.screen.MapSheetMemory
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.theme.V3Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening an item from a list and coming back finds the list where it was: the list's state lives above the
 * page or sheet switch, and nothing scrolls it on the way back.
 */
@RunWith(AndroidJUnit4::class)
class ListStateRestorationTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theMapSheetListKeepsItsPlaceAcrossADevice() {
        val rows = List(60) { i -> MapListRowState("d$i", "Device $i", "AA:00:00:00:00:%02X".format(i), "Scanned now", -40 - i) }
        val list = FakeData.recordingMap.copy(rows = rows)
        val listState = LazyListState()
        val memory = MapSheetMemory()
        var selected by mutableStateOf(false)
        rule.setContent {
            V3Theme {
                val state = if (selected) list.copy(selected = FakeData.detailHalf) else list
                MapContent(state, MapActions(), PaddingValues(), listState = listState, sheetMemory = memory) { Box(Modifier.fillMaxSize()) }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { runBlocking { listState.scrollToItem(30) } }
        rule.waitForIdle()
        val before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        rule.runOnIdle { selected = true } // a device opens in the sheet
        rule.waitForIdle()
        rule.runOnIdle { selected = false } // and closes: back to the list
        rule.waitForIdle()
        assertEquals(before, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun historyKeepsItsPlaceAcrossARecordingPage() {
        val days = FakeData.recordings.days
        val many = days.map { d -> d.copy(recordings = List(15) { i -> d.recordings.first().copy(id = "${d.label}-$i") }) }
        val state = FakeData.recordings.copy(days = many)
        val listState = LazyListState()
        var onDetail by mutableStateOf(false)
        rule.setContent {
            V3Theme {
                if (onDetail) Text("recording") else HistoryContent(state, PaddingValues(), listState = listState)
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { runBlocking { listState.scrollToItem(20) } }
        rule.waitForIdle()
        val before = listState.firstVisibleItemIndex
        rule.runOnIdle { onDetail = true } // the recording's page replaces the list
        rule.waitForIdle()
        rule.runOnIdle { onDetail = false } // Back
        rule.waitForIdle()
        assertEquals(before, listState.firstVisibleItemIndex)
        assertEquals(20, before)
    }
}
