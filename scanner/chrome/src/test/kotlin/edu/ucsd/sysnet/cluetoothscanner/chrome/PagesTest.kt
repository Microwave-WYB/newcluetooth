package edu.ucsd.sysnet.cluetoothscanner.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import edu.ucsd.sysnet.cluetoothscanner.api.core.ExportFormat
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Settings
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class PageRulesTest {
    /** P-5 texts and fixes. */
    @Test fun locationMessages() {
        assertNull(locationMessage(LocationStatus.Ok))
        assertEquals("Location permission is off", locationMessage(LocationStatus(LocationStatus.State.Denied, true, action = LocationStatus.Action.Allow)))
        assertEquals("Location is turned off", locationMessage(LocationStatus(LocationStatus.State.Off, true, action = LocationStatus.Action.TurnOn)))
        assertEquals("Waiting for location…", locationMessage(LocationStatus(LocationStatus.State.Waiting, true)))
        assertEquals("Location is 4 min old", locationMessage(LocationStatus(LocationStatus.State.Stale, true, ageMin = 4)))
        assertEquals("Allow", locationActionLabel(LocationStatus.Action.Allow))
        assertEquals("Turn on", locationActionLabel(LocationStatus.Action.TurnOn))
    }

    /** O-2: four steps on first launch; later only the missing piece. */
    @Test fun onboardingSteps() {
        val ok = PermissionState(Perm.Granted, Perm.Granted, Perm.Denied, bluetoothOn = true)
        assertEquals(OnboardingStep.entries, onboardingSteps(true, ok))
        assertEquals(emptyList<OnboardingStep>(), onboardingSteps(false, ok))
        assertEquals(listOf(OnboardingStep.Bluetooth), onboardingSteps(false, ok.copy(bluetoothOn = false)))
        assertEquals(listOf(OnboardingStep.Permissions), onboardingSteps(false, ok.copy(location = Perm.PermanentlyDenied)))
        val ui = OnboardingUi(3, ok, "ab", userIdValid = false, autoRecord = true)
        assertFalse(onboardingCanContinue(OnboardingStep.UserId, ui))
        assertTrue(onboardingCanContinue(OnboardingStep.UserId, ui.copy(userIdValid = true)))
        assertTrue(onboardingCanContinue(OnboardingStep.Permissions, ui)) // notifications are optional
        assertFalse(onboardingCanContinue(OnboardingStep.Permissions, ui.copy(permissions = ok.copy(nearby = Perm.Denied))))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val settings = Settings("wyb01", recordOnOpen = true, wifiOnlyUpload = false, scanCodedPhy = true, hideUnnamedApple = true, appVersion = "0.1.0 (debug)")
    private val uploads = UploadStatus(3, 4_200_000, 0, null)
    private fun storage(confirm: DeleteConfirm? = null, format: ExportFormat? = null) =
        StorageUi(12_000_000, 340_000_000, 48_000_000, 3, Scope.session("r1"), format, false, confirm)

    private fun settingsShot(name: String, dark: Boolean) {
        var st by mutableStateOf(storage())
        var s by mutableStateOf(settings)
        val deletes = mutableListOf<Boolean>()
        val a = SettingsActions(
            onBack = {}, onSettings = { s = it(s) }, onUploadNow = {}, onExportScope = { st = st.copy(exportScope = it) },
            onExportFormat = { st = st.copy(exportFormat = it) }, onShare = {}, onSaveToDownloads = {},
            onDeleteRequest = { st = st.copy(deleteConfirm = DeleteConfirm.Confirm) },
            onDeleteConfirm = { st = st.copy(deleteConfirm = if (st.pendingCount > 0) DeleteConfirm.LoseUnuploaded else null) },
            onDelete = { deletes += it; st = st.copy(deleteConfirm = null) }, onDeleteCancel = { st = st.copy(deleteConfirm = null) },
        )
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                SettingsPage(true, SettingsUi(s, uploads, st), a, sessionLabel = "6:05 PM", sessionScope = Scope.session("r1"))
            }
        }
        val sch = if (dark) "dark" else "light"
        compose.onRoot().captureRoboImage("src/test/screenshots/settings/$name-$sch-top.png")
        compose.onNodeWithText("Upload over Wi-Fi only").assertIsDisplayed()
        compose.onNodeWithContentDescription("Upload over Wi-Fi only").performClick()
        compose.runOnIdle { assertTrue(s.wifiOnlyUpload) }
        compose.onNodeWithText("Database 12 MB · Raw files 340 MB · Map cache 48 MB").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("400 MB").assertIsDisplayed()
        compose.onNodeWithText("Table (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Delete 6:05 PM?").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings/$name-$sch-confirm.png")
        compose.onNodeWithText("Delete now").assertDoesNotExist()
        // the confirmation's Delete: the last node labelled Delete (the first is the request button's place)
        compose.onAllNodesWithText("Delete").let { it[it.fetchSemanticsNodes().size - 1] }.performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("3 files not uploaded yet").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings/$name-$sch-lose.png")
        compose.onNodeWithText("Keep until uploaded").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(listOf(false), deletes) // the default keeps un-uploaded data (§2.8)
        assertEquals(ExportFormat.Csv, st.exportFormat)
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun settingsPhoneLight() = settingsShot("412x915", false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun settingsPhoneDark() = settingsShot("412x915", true)
    @Config(sdk = [35], qualifiers = "w1024dp-h768dp-xhdpi") @Test fun settingsTabletLight() = settingsShot("1024x768", false)

    private fun popovers(dark: Boolean) {
        compose.setContent {
            CluetoothTheme(darkTheme = dark, reducedMotion = true) {
                Column(Modifier.fillMaxSize().background(CluetoothTheme.colors.fill2).padding(12.dp)) {
                    LocationPopover(LocationStatus(LocationStatus.State.Denied, true, action = LocationStatus.Action.Allow), {})
                    Box(Modifier.padding(8.dp))
                    LocationPopover(LocationStatus(LocationStatus.State.Off, true, action = LocationStatus.Action.TurnOn), {})
                    Box(Modifier.padding(8.dp))
                    LocationPopover(LocationStatus(LocationStatus.State.Stale, true, ageMin = 4), {})
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/popover/412x915-${if (dark) "dark" else "light"}.png")
        compose.onNodeWithText("Turn on").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun popoverLight() = popovers(false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun popoverDark() = popovers(true)

    private fun onboarding(dark: Boolean) {
        var ui by mutableStateOf(OnboardingUi(1, PermissionState(Perm.Granted, Perm.Denied, Perm.PermanentlyDenied, false), "wyb", true, true))
        val a = OnboardingActions(
            onRequest = { ui = ui.copy(permissions = ui.permissions.copy(location = Perm.Granted)) }, onOpenSettings = {},
            onEnableBluetooth = { ui = ui.copy(permissions = ui.permissions.copy(bluetoothOn = true)) },
            onUserId = { ui = ui.copy(userId = it, userIdValid = Settings.isValidUserId(it)) }, onAutoRecord = { ui = ui.copy(autoRecord = it) },
            onBack = { ui = ui.copy(step = ui.step - 1) }, onNext = { ui = ui.copy(step = ui.step + 1) }, onDone = {},
        )
        compose.setContent { CluetoothTheme(darkTheme = dark, reducedMotion = true) { Onboarding(ui, OnboardingStep.entries, a) } }
        val sch = if (dark) "dark" else "light"
        fun shot(n: Int) = compose.onRoot().captureRoboImage("src/test/screenshots/onboarding/412x915-$sch-$n.png")
        compose.onNodeWithText("1 of 4").assertIsDisplayed(); compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsNotEnabled()
        shot(1)
        compose.onNodeWithText("Allow").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Next").assertIsEnabled().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Next").assertIsNotEnabled(); shot(2)
        compose.onNodeWithText("Turn on Bluetooth").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Next").performClick(); compose.waitForIdle(); shot(3)
        compose.onNodeWithText("Next").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Done").assertIsDisplayed(); shot(4)
        compose.onNodeWithText("Back").performClick(); compose.waitForIdle()
        compose.onNodeWithText("3 of 4").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun onboardingLight() = onboarding(false)
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi") @Test fun onboardingDark() = onboarding(true)
}
