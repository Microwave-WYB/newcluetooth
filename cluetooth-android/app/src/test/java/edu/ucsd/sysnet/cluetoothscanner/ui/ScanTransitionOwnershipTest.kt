package edu.ucsd.sysnet.cluetoothscanner.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTransitionOwnershipTest {
    @Test
    fun viewModelAndActivityDoNotBypassCoordinatorForScannerOrUiTransitions() {
        val viewModel = mainSource(
            "edu/ucsd/sysnet/cluetoothscanner/ui/ScanViewModel.kt",
        ).readText()
        val activity = mainSource(
            "edu/ucsd/sysnet/cluetoothscanner/MainActivity.kt",
        ).readText()
        val service = mainSource(
            "edu/ucsd/sysnet/cluetoothscanner/service/BleScanService.kt",
        ).readText()
        val application = mainSource(
            "edu/ucsd/sysnet/cluetoothscanner/CluetoothApplication.kt",
        ).readText()

        assertFalse(viewModel.contains("bleScanService.startScanning()"))
        assertFalse(viewModel.contains("bleScanService.stopScanning()"))
        assertFalse(viewModel.contains("stopScanningAfterCallbackFence"))
        assertFalse(activity.contains(".startScanning()"))
        assertFalse(activity.contains(".stopScanning()"))
        // The process owns the one coordinator; the ViewModel only uses the recording API.
        assertTrue(viewModel.contains("recording.stop()"))
        assertFalse(viewModel.contains("ScanTransitionCoordinator("))
        assertEquals(1, "ScanTransitionCoordinator\\(".toRegex().findAll(application).count())
        assertFalse(service.contains("fun cleanup() {\n        stopScanning()"))
    }

    @Test
    fun theViewModelNeverStopsTheRecordingWhenItIsCleared() {
        val viewModel = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/ScanViewModel.kt").readText()
        val onCleared = viewModel.substringAfter("override fun onCleared()").substringBefore("companion object")
        assertFalse(onCleared.contains("stop()"))
        assertFalse(onCleared.contains("bleScanService.cleanup()"))
        assertFalse(onCleared.contains("locationService.cleanup()"))
    }

    @Test
    fun theV3ViewModelUsesOnlyTheRecordingApiAndDoesNotStopAnythingWhenCleared() {
        val v3 = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/v3/app/V3ViewModel.kt").readText()
        // Recording and scanning belong to the process; the screen's ViewModel only calls the recording API.
        assertFalse(v3.contains("bleScanService.startScanning()"))
        assertFalse(v3.contains("bleScanService.stopScanning()"))
        assertFalse(v3.contains("ScanTransitionCoordinator("))
        assertFalse(v3.contains("bleScanService.cleanup()"))
        assertFalse(v3.contains("locationService.cleanup()"))
        assertTrue(v3.contains("recording.start()"))
        assertTrue(v3.contains("recording.pause()"))
        assertTrue(v3.contains("recording.stop()"))
        val onCleared = v3.substringAfter("override fun onCleared()").substringBefore("}")
        assertFalse(onCleared.contains("stop()"))
        assertFalse(onCleared.contains("recording."))
    }

    @Test
    fun liveCollectionAndThePublishLoopAreGatedOnTheUiBeingStarted() {
        val v3 = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/v3/app/V3ViewModel.kt").readText()
        assertTrue(v3.contains("uiActive.collectLatest"))
        assertEquals(2, "uiActive.collectLatest".toRegex().findAll(v3).count())
        val app = mainSource("edu/ucsd/sysnet/cluetoothscanner/ui/v3/app/V3App.kt").readText()
        assertTrue(app.contains("Lifecycle.Event.ON_START -> vm.setUiActive(true)"))
        assertTrue(app.contains("Lifecycle.Event.ON_STOP -> vm.setUiActive(false)"))
        assertFalse(app.contains(".collectAsState()"))
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
