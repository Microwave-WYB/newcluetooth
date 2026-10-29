package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.chrome.gallery.chromeCells
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.GalleryBox
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every :chrome gallery cell, light and dark, at 2.625 (the prototype's capture density), named like its crop. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class ChromeCellsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(dark: Boolean) {
        var index by mutableIntStateOf(0)
        var count = 0
        var slug = ""
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                val cells = chromeCells()
                count = cells.size
                slug = cells[index].slug
                Box(Modifier.wrapContentSize(Alignment.TopStart).background(CluetoothTheme.colors.bg).testTag("cell")) { GalleryBox(cells[index]) }
            }
        }
        compose.waitForIdle()
        for (i in 0 until count) {
            index = i
            compose.waitForIdle()
            compose.onNodeWithTag("cell").captureRoboImage("src/test/screenshots/cells/${if (dark) "dark" else "light"}/$slug.png")
        }
    }

    @Test fun light() = shoot(false)
    @Test fun dark() = shoot(true)
}
