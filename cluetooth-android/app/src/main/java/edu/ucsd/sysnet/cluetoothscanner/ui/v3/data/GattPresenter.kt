package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

import androidx.annotation.StringRes
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattCharacteristicResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattDiscoveryResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattOutcome
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattProgress
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattReadValue
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattServiceResult
import edu.ucsd.sysnet.cluetoothscanner.gatt.GattStage
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattCharState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattDescriptorState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattServiceState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.GattUiState
import edu.ucsd.sysnet.cluetoothscanner.ui.v3.model.StepStatus
import java.util.Locale

/** Turns the GATT engine's progress and result into the detail page's state. */
object GattPresenter {
    @StringRes private val labels = listOf(
        R.string.gatt_step_connect, R.string.gatt_step_mtu, R.string.gatt_step_discover,
        R.string.gatt_step_read, R.string.gatt_step_disconnect,
    )
    private val stages = GattStage.entries

    fun running(progress: GattProgress, texts: Texts): GattUiState.InProgress {
        val current = progress.stage.ordinal
        return GattUiState.InProgress(
            labels.mapIndexed { i, id ->
                val label = texts.string(id)
                when {
                    i < current -> GattStepState(label, StepStatus.Done)
                    i == current -> GattStepState(label, StepStatus.Active, activeDetail(progress, texts))
                    else -> GattStepState(label, StepStatus.Pending)
                }
            },
        )
    }

    private fun activeDetail(p: GattProgress, texts: Texts): String? = when (p.stage) {
        GattStage.READ -> p.readsTotal?.let { "${p.readsDone}/$it" }
        GattStage.CONNECT -> if (p.attempt > 1) texts.string(R.string.gatt_attempt, p.attempt) else null
        else -> null
    }

    fun finished(result: GattDiscoveryResult, texts: Texts): GattUiState.Done {
        val reached = result.stageReached.ordinal
        val clean = result.outcome == GattOutcome.COMPLETE
        val characteristics = result.services.sumOf { it.characteristics.size }
        val details = listOf(
            result.timings.connectMs?.let(::seconds),
            result.mtu?.toString(),
            if (result.services.isNotEmpty()) texts.plural(R.plurals.gatt_services, result.services.size, result.services.size) else null,
            if (result.services.isNotEmpty()) texts.plural(R.plurals.gatt_characteristics, characteristics, characteristics) else null,
            result.disconnect?.initiator?.wire,
        )
        val steps = labels.mapIndexed { i, id ->
            val label = texts.string(id)
            when {
                clean || i < reached -> GattStepState(label, StepStatus.Done, details[i])
                i == reached -> GattStepState(label, StepStatus.Failed, if (result.outcome == GattOutcome.CANCELLED) texts.string(R.string.gatt_cancelled) else result.error?.name)
                else -> GattStepState(label, StepStatus.Pending)
            }
        }
        val summary = when (result.outcome) {
            GattOutcome.COMPLETE -> null
            GattOutcome.PARTIAL -> withError(texts, R.string.gatt_summary_partial, result)
            GattOutcome.FAILED -> withError(texts, R.string.gatt_summary_failed, result)
            GattOutcome.CANCELLED -> texts.string(R.string.gatt_summary_cancelled)
        }
        return GattUiState.Done(steps, result.services.map { service(it, texts) }, summary)
    }

    private fun withError(texts: Texts, @StringRes head: Int, result: GattDiscoveryResult): String =
        result.error?.let { texts.string(R.string.gatt_summary_with_error, texts.string(head), it.name) } ?: texts.string(head)

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

    private fun service(s: GattServiceResult, texts: Texts) =
        GattServiceState(s.uuid, GattNames.service(s.uuid), s.characteristics.map { characteristic(it, texts) })

    private fun characteristic(c: GattCharacteristicResult, texts: Texts): GattCharState {
        val v = c.value
        val value = if (v.status == GattReadValue.OK) v.hex?.let { GattNames.valueText(c.uuid, it, texts) } else null
        return GattCharState(
            uuid = c.uuid,
            name = GattNames.characteristic(c.uuid),
            properties = c.properties.joinToString(" ") { GattNames.property(it, texts) },
            value = value,
            error = errorText(v.status, texts),
            descriptors = c.descriptors.map { d ->
                GattDescriptorState(
                    uuid = d.uuid,
                    name = GattNames.descriptor(d.uuid),
                    value = if (d.value.status == GattReadValue.OK) d.value.hex?.let { GattNames.valueText(d.uuid, it, texts) } else null,
                    error = errorText(d.value.status, texts),
                )
            },
        )
    }

    /** The contract's read status to words; null when there is nothing wrong to show. */
    fun errorText(status: String, texts: Texts): String? = when (status) {
        GattReadValue.OK, GattReadValue.NOT_READ -> null
        GattReadValue.INSUFFICIENT_AUTHENTICATION -> texts.string(R.string.gatt_error_authentication)
        GattReadValue.INSUFFICIENT_ENCRYPTION -> texts.string(R.string.gatt_error_encryption)
        GattReadValue.READ_NOT_PERMITTED -> texts.string(R.string.gatt_error_not_permitted)
        GattReadValue.TIMEOUT -> texts.string(R.string.gatt_error_timeout)
        else -> if (status.startsWith("error:")) texts.string(R.string.gatt_error_code, status.removePrefix("error:")) else status
    }
}

/** A few Bluetooth SIG names, and value formatting; unknown UUIDs show without a name. */
object GattNames {
    private val services = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "1802" to "Immediate Alert",
        "1803" to "Link Loss", "1804" to "Tx Power", "180A" to "Device Information",
        "180D" to "Heart Rate", "180F" to "Battery Service", "1812" to "Human Interface Device",
        "1816" to "Cycling Speed and Cadence", "181A" to "Environmental Sensing",
        "181C" to "User Data", "181D" to "Weight Scale", "1826" to "Fitness Machine",
    )
    private val characteristics = mapOf(
        "2A00" to "Device Name", "2A01" to "Appearance", "2A04" to "Peripheral Preferred Connection Parameters",
        "2A05" to "Service Changed", "2A19" to "Battery Level", "2A23" to "System ID",
        "2A24" to "Model Number String", "2A25" to "Serial Number String", "2A26" to "Firmware Revision String",
        "2A27" to "Hardware Revision String", "2A28" to "Software Revision String", "2A29" to "Manufacturer Name String",
        "2A37" to "Heart Rate Measurement", "2A38" to "Body Sensor Location", "2A4A" to "HID Information",
        "2A4B" to "Report Map", "2A4D" to "Report", "2A50" to "PnP ID", "2A6E" to "Temperature", "2A6F" to "Humidity",
        "2AA6" to "Central Address Resolution",
    )
    private val properties = mapOf(
        "broadcast" to R.string.gatt_property_broadcast, "read" to R.string.gatt_property_read,
        "write_no_response" to R.string.gatt_property_write_no_response, "write" to R.string.gatt_property_write,
        "notify" to R.string.gatt_property_notify, "indicate" to R.string.gatt_property_indicate,
        "signed_write" to R.string.gatt_property_signed_write, "extended" to R.string.gatt_property_extended,
    )

    private val descriptors = mapOf(
        "2900" to "Characteristic Extended Properties", "2901" to "Characteristic User Description",
        "2902" to "Client Characteristic Configuration", "2903" to "Server Characteristic Configuration",
        "2904" to "Characteristic Presentation Format",
    )

    fun descriptor(uuid: String): String? = descriptors[uuid]
    fun service(uuid: String): String? = services[uuid]
    fun characteristic(uuid: String): String? = characteristics[uuid]
    fun property(wire: String, texts: Texts): String = properties[wire]?.let { texts.string(it) } ?: wire

    /** ASCII text in quotes when every byte is printable, a few well-known numeric values, otherwise hex. */
    fun valueText(uuid: String, hex: String, texts: Texts): String {
        if (hex.isEmpty()) return texts.string(R.string.gatt_value_empty)
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (uuid == "2A19" && bytes.size == 1) return "${bytes[0]} %"
        if (bytes.all { it in 0x20..0x7e }) return "\"" + String(CharArray(bytes.size) { bytes[it].toChar() }) + "\""
        val shown = bytes.take(32).joinToString(" ") { "%02x".format(it) }
        return if (bytes.size > 32) texts.string(R.string.gatt_value_truncated, shown, texts.plural(R.plurals.bytes, bytes.size, bytes.size)) else shown
    }
}
