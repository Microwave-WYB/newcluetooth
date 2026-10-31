package edu.ucsd.sysnet.cluetoothscanner.designsystem




import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.DesignSystemGallery
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.GalleryBox
import edu.ucsd.sysnet.cluetoothscanner.designsystem.gallery.designSystemCells
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every :designsystem gallery cell, light and dark, at the 412 × 915 phone's density (2.625, as
 * the prototype is captured), and the gallery screen at 412 × 915 and 1024 × 768. References live in
 * src/test/screenshots; `-Pcluetooth.record=true` rewrites them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class GalleryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private fun shoot(dark: Boolean) {
        val scheme = if (dark) "dark" else "light"
        var index by mutableStateOf(0)
        var count = 0
        var slug = ""
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                val cells = designSystemCells()
                count = cells.size
                slug = cells[index].slug
                Box(Modifier.wrapContentSize(Alignment.TopStart).background(CluetoothTheme.colors.bg).testTag("cell")) { GalleryBox(cells[index]) }
            }
        }
        compose.waitForIdle()
        for (i in 0 until count) {
            index = i
            compose.waitForIdle()
            compose.onNodeWithTag("cell").captureRoboImage("src/test/screenshots/cells/$scheme/$slug.png", roborazziOptions = options)
        }
    }

    @Test fun cellsLight() = shoot(dark = false)

    @Test fun cellsDark() = shoot(dark = true)
}

/** The whole gallery screen at both review sizes, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(name: String, dark: Boolean) {
        compose.setContent { CluetoothTheme(darkTheme = dark, reducedMotion = true) { DesignSystemGallery() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/screens/$name.png")
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneLight() = screen("gallery-412x915-light", false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneDark() = screen("gallery-412x915-dark", true)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletLight() = screen("gallery-1024x768-light", false)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun tabletDark() = screen("gallery-1024x768-dark", true)
}

