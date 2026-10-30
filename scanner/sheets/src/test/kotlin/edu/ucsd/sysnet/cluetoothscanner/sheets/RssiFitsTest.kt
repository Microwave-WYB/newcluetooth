package edu.ucsd.sysnet.cluetoothscanner.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdStructure
import edu.ucsd.sysnet.cluetoothscanner.api.core.AdvFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.Format
import edu.ucsd.sysnet.cluetoothscanner.designsystem.components.DeviceRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Three-digit RSSI values are never clipped, at a large font scale too (the owner's "−10(" for −101). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class RssiFitsTest {
    @get:Rule val compose = createComposeRule()

    private fun scaled(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
            CluetoothTheme(darkTheme = false, reducedMotion = true) { content() }
        }
    }

    /**
     * The text fits its node: one line, not ellipsized, and its natural width (the max intrinsic width of its
     * paragraph, as it would draw unconstrained) no wider than its box, which is no wider than the node. Glyph boxes
     * can't tell: a constrained single line reports them squeezed into the box.
     */
    private fun assertFits(node: SemanticsNode, what: String) {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.single()
        val natural = layout.multiParagraph.intrinsics.maxIntrinsicWidth
        assertTrue("$what: one line", layout.lineCount == 1)
        assertFalse("$what: ellipsized", layout.isLineEllipsized(0))
        assertTrue("$what: natural width $natural > box ${layout.size.width}", natural <= layout.size.width + 0.5f)
        assertTrue("$what: box ${layout.size.width} > node ${node.size.width}", layout.size.width <= node.size.width)
    }

    @Test
    fun listRowsShowThreeDigitRssiWhole_atFontScale1_3() {
        val long = "Govee_H6199_RGBIC_LED_Strip_Living_Room_Cabinet"
        scaled {
            Column(Modifier.width(412.dp)) {
                for (rssi in listOf(-101, -127, -97)) DeviceRow(long, "A8:7D:4A:D7:4F:E4", rssi, {})
            }
        }
        for (rssi in listOf(-101, -127, -97)) {
            assertFits(compose.onNodeWithText(Format.signed(rssi), useUnmergedTree = true).fetchSemanticsNode(), "RSSI $rssi")
        }
    }

    @Test
    fun theDeviceHeaderShowsDbmWhole_atFontScale1_3() {
        val d = DeviceDetail(
            key = 1, addr = "AA:BB:CC:DD:EE:FF", name = "AirPods Pro", page = null, categories = emptyList(), traits = emptyList(),
            readable = false, vulnerable = false, vulnerabilities = emptyList(), wikiUrl = null, icon = null, rssi = -127,
            inRange = true, live = false, connectable = true, format = AdvFormat.Legacy, companyId = null, companyName = null,
            serviceUuid = null, txPower = null, structures = listOf(AdStructure(2, 1, byteArrayOf(6))), tail = null, decoded = null, lastSeenMs = 0,
        )
        scaled { Column(Modifier.width(318.dp)) { DeviceHead(d, -127) } } // the phone header's content width
        assertFits(compose.onNodeWithTag(DeviceTags.HEAD_RSSI, useUnmergedTree = true).fetchSemanticsNode(), "−127 dBm")
        compose.onNodeWithText(Format.dbm(-127)).assertExists()
    }
}
