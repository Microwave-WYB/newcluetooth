package edu.ucsd.sysnet.cluetoothscanner.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Strings
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.CoverInsets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P-4, P-5, T-8: the locate button and the warning, as :designsystem FloatButtons in the right spot. */
@RunWith(AndroidJUnit4::class)
class LocateControlTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val hidden = MapUiState(selection = null, routesFaint = false, cover = CoverInsets(0, 0, 0, 0), showLocate = false, showWarning = false)
    private val ui = mutableStateOf(hidden)
    private val placement = mutableStateOf<LocatePlacement>(LocatePlacement.Phone(78.dp))
    private val taps = mutableListOf<String>()

    private fun setUp() = rule.setContent {
        CluetoothTheme(darkTheme = false) {
            Box(Modifier.size(360.dp, 640.dp)) {
                LocateControl(ui.value, placement.value, onLocate = { taps += "locate" }, onWarning = { taps += "warn" })
            }
        }
    }

    private fun exists(label: String) = rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().isNotEmpty()

    @Test fun p4_hiddenByDefault_showsOnRequest_andLocates() {
        setUp()
        assertEquals(false, exists(Strings.ShowMyLocation))
        assertEquals(false, exists(Strings.LocationProblem))
        rule.runOnIdle { ui.value = hidden.copy(showLocate = true) }
        rule.onNodeWithContentDescription(Strings.ShowMyLocation).performClick()
        assertEquals(listOf("locate"), taps)
    }

    @Test fun p5_theWarningOverridesTheLocateButton() {
        setUp()
        rule.runOnIdle { ui.value = hidden.copy(showLocate = true, showWarning = true) }
        rule.waitForIdle()
        assertEquals(false, exists(Strings.ShowMyLocation))
        rule.onNodeWithContentDescription(Strings.LocationProblem).performClick()
        assertEquals(listOf("warn"), taps)
    }

    @Test fun p4_phonePlacement_bottomLeft_centredOnTheFab() {
        setUp()
        rule.runOnIdle { ui.value = hidden.copy(showWarning = true) }
        // left --edge; bottom = peek 78 + --edge 12 + 12 + (56 − 44) / 2 = 108, so the top is 640 − 108 − 44
        node().assertLeftPositionInRootIsEqualTo(12.dp).assertTopPositionInRootIsEqualTo(640.dp - 108.dp - 44.dp)
            .assertHeightIsEqualTo(44.dp)
    }

    @Test fun t8_tabletPlacement_bottomRight_12dpAboveTheRecorder() {
        setUp()
        rule.runOnIdle { ui.value = hidden.copy(showWarning = true); placement.value = LocatePlacement.Tablet(recorderHeight = 56.dp) }
        // right --edge + 6 = 18, bottom --edge + 12 + 56 + 12 = 92
        node().assertLeftPositionInRootIsEqualTo(360.dp - 18.dp - 44.dp).assertTopPositionInRootIsEqualTo(640.dp - 92.dp - 44.dp)
    }

    private fun node() = rule.onNodeWithContentDescription(Strings.LocationProblem)
}
