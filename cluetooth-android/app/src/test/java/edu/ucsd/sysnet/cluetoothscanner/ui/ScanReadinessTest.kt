package edu.ucsd.sysnet.cluetoothscanner.ui

import edu.ucsd.sysnet.cluetoothscanner.service.ScannerStartOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanReadinessTest {
    @Test
    fun scanStartsOnlyWhenPermissionsAndBluetoothAreReady() {
        assertFalse(ScanReadiness(false, true, true).canStart)
        assertFalse(ScanReadiness(true, false, false).canStart)
        assertFalse(ScanReadiness(true, true, false).canStart)
        assertTrue(ScanReadiness(true, true, true).canStart)
    }

    @Test
    fun unavailableAndBluetoothOffStatesAreNonStartingTransitions() {
        assertEquals(
            ScanReadinessAction.BLUETOOTH_UNAVAILABLE,
            ScanReadiness(true, false, false).action,
        )
        assertEquals(
            ScanReadinessAction.REQUEST_BLUETOOTH,
            ScanReadiness(true, true, false).action,
        )
    }

    @Test
    fun resumeTransitionStartsAfterPermissionOrBluetoothSettingsChange() {
        val permissionMissing = ScanReadiness(false, true, true)
        val bluetoothOff = ScanReadiness(true, true, false)
        val resumedReady = ScanReadiness(true, true, true)

        assertEquals(ScanReadinessAction.REQUEST_PERMISSIONS, permissionMissing.action)
        assertEquals(ScanReadinessAction.REQUEST_BLUETOOTH, bluetoothOff.action)
        assertEquals(ScanReadinessAction.START_SERVICES, resumedReady.action)
    }

    @Test
    fun bluetoothOffResumeChecksRetainIntentAndSessionUntilCoordinatedRecovery() = runTest {
        var scannerReady = false
        var scannerIntent = false
        var sessionStarts = 0
        var scannerStarts = 0
        var fences = 0
        var finishes = 0
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessionStarts++; true },
            startScanner = {
                scannerStarts++
                scannerIntent = true
                if (scannerReady) {
                    ScannerStartOutcome.STARTED
                } else {
                    ScannerStartOutcome.DEFERRED_INTENT_RETAINED
                }
            },
            stopScannerAndFence = {
                scannerIntent = false
                fences++
            },
            finishSession = { finishes++; true },
        )
        val bluetoothOff = ScanReadiness(
            permissionsGranted = true,
            bluetoothSupported = true,
            bluetoothEnabled = false,
        )

        reconcileScanReadiness(bluetoothOff, userWantsScanning = true, coordinator)
        assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
        assertTrue(coordinator.state.value.isRequested)
        assertTrue(scannerIntent)

        repeat(2) {
            reconcileScanReadiness(bluetoothOff, userWantsScanning = true, coordinator)
            assertEquals(ScanTransitionState.DEFERRED, coordinator.state.value)
            assertTrue(scannerIntent)
        }
        assertEquals(1, sessionStarts)
        assertEquals(3, scannerStarts)

        scannerReady = true
        assertTrue(coordinator.resumeDeferred())
        assertEquals(ScanTransitionState.SCANNING, coordinator.state.value)
        assertEquals(1, sessionStarts)

        assertTrue(coordinator.stop())
        assertTrue(coordinator.stop())
        assertFalse(scannerIntent)
        assertEquals(1, fences)
        assertEquals(1, finishes)
        assertEquals(ScanTransitionState.STOPPED, coordinator.state.value)
    }

    @Test
    fun nullAdapterAndNullScannerReadinessRetainRecoverableRequest() = runTest {
        listOf("adapter", "scanner").forEach { unavailableComponent ->
            var scannerAvailable = false
            var sessionStarts = 0
            var scannerStarts = 0
            var scannerIntent = false
            val coordinator = ScanTransitionCoordinator(
                startSession = { sessionStarts++; true },
                startScanner = {
                    scannerStarts++
                    scannerIntent = true
                    if (scannerAvailable) {
                        ScannerStartOutcome.STARTED
                    } else {
                        ScannerStartOutcome.DEFERRED_INTENT_RETAINED
                    }
                },
                stopScannerAndFence = { scannerIntent = false },
                finishSession = { true },
            )
            val unavailable = ScanReadiness(
                permissionsGranted = true,
                bluetoothSupported = unavailableComponent != "adapter",
                bluetoothEnabled = unavailableComponent == "scanner",
            )

            reconcileScanReadiness(unavailable, userWantsScanning = true, coordinator)
            reconcileScanReadiness(unavailable, userWantsScanning = true, coordinator)
            assertEquals(unavailableComponent, 1, sessionStarts)
            assertEquals(unavailableComponent, 2, scannerStarts)
            assertTrue(unavailableComponent, scannerIntent)
            assertEquals(unavailableComponent, ScanTransitionState.DEFERRED, coordinator.state.value)

            scannerAvailable = true
            assertTrue(unavailableComponent, coordinator.resumeDeferred())
            assertEquals(unavailableComponent, ScanTransitionState.SCANNING, coordinator.state.value)
            assertEquals(unavailableComponent, 1, sessionStarts)
        }
    }

    @Test
    fun permanentPermissionRejectionFinalizesOnceAndClearedIntentDoesNotRestart() = runTest {
        var sessionStarts = 0
        var fences = 0
        var finishes = 0
        val coordinator = ScanTransitionCoordinator(
            startSession = { sessionStarts++; true },
            startScanner = { ScannerStartOutcome.DEFERRED_INTENT_RETAINED },
            stopScannerAndFence = { fences++ },
            finishSession = { finishes++; true },
        )

        reconcileScanReadiness(
            ScanReadiness(true, true, false),
            userWantsScanning = true,
            coordinator,
        )
        reconcileScanReadiness(
            ScanReadiness(false, true, false),
            userWantsScanning = false,
            coordinator,
        )
        reconcileScanReadiness(
            ScanReadiness(false, true, false),
            userWantsScanning = false,
            coordinator,
        )
        reconcileScanReadiness(
            ScanReadiness(true, true, true),
            userWantsScanning = false,
            coordinator,
        )

        assertEquals(1, sessionStarts)
        assertEquals(1, fences)
        assertEquals(1, finishes)
        assertFalse(coordinator.state.value.isRequested)
        assertEquals(ScanTransitionState.STOPPED, coordinator.state.value)
    }
}
