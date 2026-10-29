package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattCharView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattServiceView
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStep
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.math.ceil

/** What the fake needs to know of a device (`data.js` `DEVICE[addr]`). */
data class FakeGattDevice(val name: String?, val connectable: Boolean, val companyName: String? = null)

/**
 * [GattRunner] following `data.js` `discoverGatt`: a non-connectable device times out while connecting, a device
 * named "ELK-BLEDOM" disconnects while its characteristics are read, every other device reads the three demo
 * services. [device] is the lookup (wired to `:core-fake` in the gallery); null plays a missing GATT target and
 * [available] = false a missing `BLUETOOTH_CONNECT` or Bluetooth off. [onFinished] receives every final view of a
 * run that was not cancelled, so the gallery can feed it back as the device's last result (D-13.7).
 */
class FakeGattRunner(
    private val device: (DeviceKey) -> FakeGattDevice?,
    private val available: () -> Boolean = { true },
    private val stepMs: Long = 600,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onFinished: (GattView) -> Unit = {},
) : GattRunner {
    private var running: Job? = null
    private var discoveries = 0

    override fun discover(key: DeviceKey): Flow<GattView> = flow {
        // One discovery at a time: a new collection cancels the previous one.
        val job = currentCoroutineContext()[Job]
        synchronized(this@FakeGattRunner) {
            running?.takeIf { it != job }?.cancel()
            running = job
        }
        val run = Run(key, id = "fake-gatt-${++discoveries}", startedMs = nowMs())
        if (!available()) return@flow emit(run.failedBeforeConnect(GattFailure.Other(GattRunner.GATT_UNAVAILABLE)))
        val target = device(key)
            ?: return@flow emit(run.failedBeforeConnect(GattFailure.Other(GattRunner.GATT_NO_TARGET)))
        val services = demoServices(target)
        val characteristics = services.sumOf { it.characteristics.size }

        run.step(this, GattStepKind.Connecting)
        if (!target.connectable) {
            return@flow finish(run.fail(GattFailure.TimedOut))
        }
        run.done(GattStep(GattStepKind.Connecting, GattStepState.Done, 800, null, null, null, null))
        run.step(this, GattStepKind.DiscoveringServices)
        run.done(GattStep(GattStepKind.DiscoveringServices, GattStepState.Done, null, services.size, null, null, null))
        val partial = ceil(characteristics * 0.6).toInt()
        run.step(this, GattStepKind.ReadingCharacteristics, read = partial, readable = characteristics)
        if (target.name == DISCONNECTING_NAME) {
            return@flow finish(run.fail(GattFailure.DisconnectedUnexpectedly, read = partial, readable = characteristics))
        }
        run.done(
            GattStep(GattStepKind.ReadingCharacteristics, GattStepState.Done, null, null, characteristics, characteristics, null),
        )
        run.step(this, GattStepKind.Disconnected)
        run.done(GattStep(GattStepKind.Disconnected, GattStepState.Done, null, null, null, null, null))
        finish(run.view(ok = true, services = services))
    }

    private suspend fun FlowCollector<GattView>.finish(view: GattView) {
        emit(view)
        onFinished(view)
    }

    private inner class Run(val key: DeviceKey, val id: String, val startedMs: Long) {
        private val steps = mutableListOf<GattStep>()

        fun view(ok: Boolean = false, services: List<GattServiceView> = emptyList()) =
            GattView(id, key, startedMs, ok, steps.toList(), services)

        /** Shows [kind] running, then waits one step. */
        suspend fun step(collector: FlowCollector<GattView>, kind: GattStepKind, read: Int? = null, readable: Int? = null) {
            collector.emit(
                GattView(id, key, startedMs, false, steps + GattStep(kind, GattStepState.Running, null, null, read, readable, null), emptyList()),
            )
            delay(stepMs)
        }

        fun done(step: GattStep) {
            steps += step
        }

        /** D-13.5: the list stops at the failed row. */
        fun fail(failure: GattFailure, read: Int? = null, readable: Int? = null): GattView {
            val kind = GattStepKind.entries[steps.size]
            steps += GattStep(kind, GattStepState.Failed, null, null, read, readable, failure)
            return view()
        }

        fun failedBeforeConnect(failure: GattFailure) = fail(failure)
    }

    companion object {
        const val DISCONNECTING_NAME = "ELK-BLEDOM"

        /** `data.js` `discoverGatt` services; "No value" and "Unknown …" become null for the UI's own wording. */
        fun demoServices(device: FakeGattDevice) = listOf(
            GattServiceView(
                "1800",
                "Generic Access",
                listOf(
                    GattCharView("2A00", "Device Name", listOf("read"), device.name),
                    GattCharView("2A01", "Appearance", listOf("read"), "Unknown"),
                ),
            ),
            GattServiceView(
                "180A",
                "Device Information",
                listOf(GattCharView("2A29", "Manufacturer Name String", listOf("read"), device.companyName)),
            ),
            GattServiceView(
                "0000fff0-0000-1000-8000-00805f9b34fb",
                null,
                listOf(GattCharView("0000fff1-0000-1000-8000-00805f9b34fb", null, listOf("notify"), null)),
            ),
        )
    }
}
