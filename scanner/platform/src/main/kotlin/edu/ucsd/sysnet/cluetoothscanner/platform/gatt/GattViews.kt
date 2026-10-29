package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView

/**
 * SPEC D-13 rows from engine progress, for the views a running discovery emits. The final view comes from
 * `core.ingestGatt`; [resultView] stands in only when the core could not store the result yet.
 */
internal class GattStepTracker {
    private var readsTotal: Int? = null

    fun steps(progress: GattProgress): List<GattStep> {
        progress.readsTotal?.let { readsTotal = it }
        val connecting = done(GattStepKind.Connecting, durationMs = progress.connectMs)
        val discovering = done(GattStepKind.DiscoveringServices, services = progress.services)
        return when (progress.stage) {
            GattStage.CONNECT, GattStage.MTU -> listOf(running(GattStepKind.Connecting))
            GattStage.DISCOVER -> listOf(connecting, running(GattStepKind.DiscoveringServices))
            GattStage.READ -> listOf(
                connecting,
                discovering,
                running(GattStepKind.ReadingCharacteristics, read = progress.readsDone, readable = progress.readsTotal),
            )
            GattStage.DISCONNECT -> listOf(
                connecting,
                discovering,
                done(GattStepKind.ReadingCharacteristics, read = readsTotal, readable = readsTotal),
                running(GattStepKind.Disconnected),
            )
        }
    }
}

internal fun progressView(discoveryId: String, key: DeviceKey, startedMs: Long, steps: List<GattStep>) =
    GattView(discoveryId, key, startedMs, ok = false, steps = steps, services = emptyList())

/** A failed Connecting step before anything connected (§4.3 preconditions). */
internal fun failedBeforeConnect(discoveryId: String, key: DeviceKey, startedMs: Long, failure: GattFailure) =
    progressView(discoveryId, key, startedMs, listOf(GattStep(GattStepKind.Connecting, GattStepState.Failed, null, null, null, null, failure)))

/**
 * The final view of a run a newer discovery superseded: the running step fails as `Cancelled`, or a cancelled
 * Connecting step when nothing was shown yet; null when the last view already was final.
 */
internal fun supersededView(last: GattView?, key: DeviceKey, nowMs: Long): GattView? {
    if (last == null) return failedBeforeConnect("", key, nowMs, GattFailure.Cancelled)
    if (last.steps.none { it.state == GattStepState.Running }) return null
    return last.copy(
        ok = false,
        steps = last.steps.map { if (it.state == GattStepState.Running) it.copy(state = GattStepState.Failed, failure = GattFailure.Cancelled) else it },
        services = emptyList(),
    )
}

/**
 * §2.7 for a finished result: `complete` is `ok` with every step done; `partial` and `failed` stop at the failed step.
 * MTU is folded into Connecting.
 */
internal fun resultView(result: GattDiscoveryResult, key: DeviceKey): GattView {
    val attributes = result.services.flatMap { service ->
        service.characteristics.flatMap { characteristic -> listOf(characteristic.value) + characteristic.descriptors.map { it.value } }
    }
    val readable = attributes.count { it.status != GattReadValue.NOT_READ }
    val read = attributes.count { it.status == GattReadValue.OK }
    val timings = result.timings
    val all = listOf(
        done(GattStepKind.Connecting, durationMs = timings.connectMs?.let { it + (timings.mtuMs ?: 0) }),
        done(GattStepKind.DiscoveringServices, durationMs = timings.discoverMs, services = result.services.size),
        done(GattStepKind.ReadingCharacteristics, durationMs = timings.readMs, read = read, readable = readable),
        done(GattStepKind.Disconnected),
    )
    val ok = result.outcome == GattOutcome.COMPLETE
    val steps = if (ok) {
        all
    } else {
        val failedAt = stepOf(result.error?.stage ?: result.stageReached)
        all.subList(0, failedAt.ordinal) + all[failedAt.ordinal].copy(state = GattStepState.Failed, failure = failure(result))
    }
    // D-13.6: services show only after a success.
    return GattView(result.request.id, key, result.startedAtMs, ok, steps, if (ok) services(result) else emptyList())
}

/** The §2.7 failure table. */
internal fun failure(result: GattDiscoveryResult): GattFailure {
    if (result.outcome == GattOutcome.CANCELLED) return GattFailure.Cancelled
    val error = result.error
    val status = error?.status ?: result.disconnect?.status ?: GattStatus.ERROR
    return when {
        result.disconnect?.initiator == DisconnectInitiator.TIMEOUT || status == GattStatus.TIMED_OUT -> GattFailure.TimedOut
        error?.stage == GattStage.CONNECT -> GattFailure.ConnectionFailed
        result.disconnect?.initiator == DisconnectInitiator.REMOTE -> GattFailure.DisconnectedUnexpectedly
        else -> GattFailure.Other(status)
    }
}

private fun stepOf(stage: GattStage) = when (stage) {
    GattStage.CONNECT, GattStage.MTU -> GattStepKind.Connecting
    GattStage.DISCOVER -> GattStepKind.DiscoveringServices
    GattStage.READ -> GattStepKind.ReadingCharacteristics
    GattStage.DISCONNECT -> GattStepKind.Disconnected
}

private fun services(result: GattDiscoveryResult) = result.services.map { service ->
    GattServiceView(
        uuid = service.uuid,
        name = null,
        characteristics = service.characteristics.map { characteristic ->
            GattCharView(characteristic.uuid, null, characteristic.properties, characteristic.value.hex?.let(::displayText))
        },
    )
}

/** Printable UTF-8 as text, anything else as hex (the core's richer formatting replaces this once stored). */
private fun displayText(hex: String): String {
    val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    return if (text != null && text.isNotEmpty() && text.all { !it.isISOControl() }) text else hex
}

private fun done(kind: GattStepKind, durationMs: Long? = null, services: Int? = null, read: Int? = null, readable: Int? = null) =
    GattStep(kind, GattStepState.Done, durationMs?.toInt(), services, read, readable, null)

private fun running(kind: GattStepKind, read: Int? = null, readable: Int? = null) =
    GattStep(kind, GattStepState.Running, null, null, read, readable, null)
