package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** R-10: the storage-full dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageFullScreenTest {
    @get:Rule val compose = createComposeRule()

    private val ui = StorageFullUi(150_000_000, Reclaimable(300_000_000, 40_000_000), emptySet(), freeing = false, freedBytes = null, canResume = false)
    private val calls = mutableListOf<String>()
    private val actions = StorageFullActions(
        onChoose = { c, on -> calls += "$c=$on" }, onFreeUp = { calls += "free" }, onNotNow = { calls += "notNow" }, onResume = { calls += "resume" },
    )

    private fun show(state: StorageFullUi, dark: Boolean = false) =
        compose.setContent { CluetoothTheme(darkTheme = dark, reducedMotion = true) { StorageFullDialog(state, actions) } }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun titleBodyFreeSpaceAndWhatEachSwitchFrees() {
        show(ui)
        compose.onNodeWithText("Storage is full").assertExists()
        compose.onNodeWithText("Recording is paused because the phone is almost out of space. Free up some space to continue.").assertExists()
        compose.onNodeWithText("150 MB free").assertExists()
        compose.onNodeWithText("Delete uploaded raw files").assertExists()
        compose.onNodeWithText("Frees 300 MB").assertExists()
        compose.onNodeWithText("Clear local database").assertExists()
        compose.onNodeWithText("Frees 40 MB").assertExists()
        // both off: Free up space waits for a switch
        compose.onNodeWithTag("freeUp").assertIsNotEnabled()
        compose.onNodeWithTag("switch:UploadedRaw").performClick()
        compose.onNodeWithTag("notNow").performClick()
        compose.runOnIdle { assertEquals(listOf("UploadedRaw=true", "notNow"), calls) }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun aSwitchEnablesFreeUpSpace() {
        show(ui.copy(chosen = setOf(Cleanup.LocalDatabase)))
        compose.onNodeWithTag("freeUp").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("free"), calls) }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun afterFreeingItShowsTheAmountAndResumeAboveTheFloor() {
        show(ui.copy(freeBytes = 450_000_000, freedBytes = 300_000_000, canResume = true))
        compose.onNodeWithText("Freed 300 MB").assertExists()
        compose.onNodeWithTag("freeUp").assertDoesNotExist()
        compose.onNodeWithTag("resume").performClick()
        compose.runOnIdle { assertEquals(listOf("resume"), calls) }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun belowTheFloorItStaysWithFreeUpSpace() {
        show(ui.copy(freeBytes = 170_000_000, freedBytes = 20_000_000))
        compose.onNodeWithText("Freed 20 MB").assertExists()
        compose.onNodeWithTag("resume").assertDoesNotExist()
        compose.onNodeWithTag("freeUp").assertIsNotEnabled()
    }

    /** O-1 item 11: the same two cleanups in Settings › Storage, each with what it frees, a confirmation, and what it freed. */
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
    @Test fun settingsOffersBothCleanups() {
        val settings = Settings("wyb01", recordOnOpen = true, wifiOnlyUpload = false, scanCodedPhy = true, hideUnnamedApple = true, appVersion = "0.1.0")
        val storage = StorageUi(12_000_000, 340_000_000, null, 0, Scope.All, null, false, null,
            reclaimable = Reclaimable(300_000_000, 40_000_000), cleanupConfirm = Cleanup.LocalDatabase, cleanupFreed = null)
        val a = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            onCleanupRequest = { calls += "request:$it" }, onCleanupConfirm = { calls += "confirm" }, onCleanupCancel = { calls += "cancel" })
        compose.setContent {
            CluetoothTheme(reducedMotion = true) { SettingsPage(true, SettingsUi(settings, UploadStatus(0, 0, 0, null), storage), a, sessionLabel = null, sessionScope = null) }
        }
        compose.onNodeWithText("Frees 300 MB").performScrollTo()
        compose.onNodeWithText("Frees 40 MB").performScrollTo()
        compose.onNodeWithText("Clear local database?").performScrollTo()
        // a confirmation is open, so the cleanup buttons wait
        compose.onNodeWithTag("cleanup:UploadedRaw").assertIsNotEnabled()
        compose.onNodeWithTag("primary:Clear", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("confirm"), calls) }
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneLight() = shot(false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun phoneDark() = shot(true)

    private fun shot(dark: Boolean) {
        show(ui.copy(chosen = setOf(Cleanup.UploadedRaw), freedBytes = 20_000_000), dark)
        compose.onRoot().captureRoboImage("src/test/screenshots/storage-full/412x915-${if (dark) "dark" else "light"}.png")
    }
}
