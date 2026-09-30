package edu.ucsd.sysnet.cluetoothscanner.ui

internal enum class ScanReadinessAction {
    REQUEST_PERMISSIONS,
    BLUETOOTH_UNAVAILABLE,
    REQUEST_BLUETOOTH,
    START_SERVICES,
}

internal data class ScanReadiness(
    val permissionsGranted: Boolean,
    val bluetoothSupported: Boolean,
    val bluetoothEnabled: Boolean,
) {
    val action: ScanReadinessAction
        get() = when {
            !permissionsGranted -> ScanReadinessAction.REQUEST_PERMISSIONS
            !bluetoothSupported -> ScanReadinessAction.BLUETOOTH_UNAVAILABLE
            !bluetoothEnabled -> ScanReadinessAction.REQUEST_BLUETOOTH
            else -> ScanReadinessAction.START_SERVICES
        }

    val canStart: Boolean
        get() = action == ScanReadinessAction.START_SERVICES
}

/**
 * Reconciles Activity readiness without bypassing serialized session ownership.
 *
 * Bluetooth availability is deliberately left to [ScanTransitionCoordinator]'s scanner start:
 * the service records a deferred request so adapter/scanner recovery can resume the same session.
 * Missing runtime permission is a rejection boundary and is finalized by the coordinator.
 */
internal suspend fun reconcileScanReadiness(
    readiness: ScanReadiness,
    userWantsScanning: Boolean,
    coordinator: ScanTransitionCoordinator,
) {
    if (!readiness.permissionsGranted) {
        coordinator.stop()
        return
    }
    if (userWantsScanning) coordinator.start()
}
