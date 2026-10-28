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

    private fun mainSource(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java", relativePath),
            File("app/src/main/java", relativePath),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate Android main source: $relativePath")
    }
}
