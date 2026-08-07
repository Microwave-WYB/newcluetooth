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

        assertFalse(viewModel.contains("bleScanService.startScanning()"))
        assertFalse(viewModel.contains("bleScanService.stopScanning()"))
        assertFalse(viewModel.contains("stopScanningAfterCallbackFence"))
        assertFalse(activity.contains(".startScanning()"))
        assertFalse(activity.contains(".stopScanning()"))
        assertEquals(1, "_isScanning.value =".toRegex().findAll(viewModel).count())
        assertTrue(viewModel.contains("_isScanning.value = transition.isRequested"))
        assertTrue(viewModel.contains("scanTransitionCoordinator.stop()"))
        assertFalse(service.contains("fun cleanup() {\n        stopScanning()"))
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
