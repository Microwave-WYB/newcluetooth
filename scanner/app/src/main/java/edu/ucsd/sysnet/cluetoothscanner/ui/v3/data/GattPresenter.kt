package edu.ucsd.sysnet.cluetoothscanner.ui.v3.data

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
    private val labels = listOf("连接", "协商 MTU", "发现服务", "读取特征", "断开")
    private val stages = GattStage.entries

    fun running(progress: GattProgress): GattUiState.InProgress {
        val current = progress.stage.ordinal
        return GattUiState.InProgress(
            labels.mapIndexed { i, label ->
                when {
                    i < current -> GattStepState(label, StepStatus.Done)
                    i == current -> GattStepState(label, StepStatus.Active, activeDetail(progress))
                    else -> GattStepState(label, StepStatus.Pending)
                }
            },
        )
    }

    private fun activeDetail(p: GattProgress): String? = when (p.stage) {
        GattStage.READ -> p.readsTotal?.let { "${p.readsDone}/$it" }
        GattStage.CONNECT -> if (p.attempt > 1) "第 ${p.attempt} 次" else null
        else -> null
    }

    fun finished(result: GattDiscoveryResult): GattUiState.Done {
        val reached = result.stageReached.ordinal
        val clean = result.outcome == GattOutcome.COMPLETE
        val characteristics = result.services.sumOf { it.characteristics.size }
        val details = listOf(
            result.timings.connectMs?.let(::seconds),
            result.mtu?.toString(),
            if (result.services.isNotEmpty()) "${result.services.size} 个服务" else null,
            if (result.services.isNotEmpty()) "$characteristics 个特征" else null,
            result.disconnect?.initiator?.wire,
        )
        val steps = labels.mapIndexed { i, label ->
            when {
                clean || i < reached -> GattStepState(label, StepStatus.Done, details[i])
                i == reached -> GattStepState(label, StepStatus.Failed, if (result.outcome == GattOutcome.CANCELLED) "已取消" else result.error?.name)
                else -> GattStepState(label, StepStatus.Pending)
            }
        }
        val summary = when (result.outcome) {
            GattOutcome.COMPLETE -> null
            GattOutcome.PARTIAL -> "部分完成" + (result.error?.let { " · ${it.name}" } ?: "")
            GattOutcome.FAILED -> "失败" + (result.error?.let { " · ${it.name}" } ?: "")
            GattOutcome.CANCELLED -> "已取消，已保留已读取的部分"
        }
        return GattUiState.Done(steps, result.services.map(::service), summary)
    }

    private fun seconds(ms: Long) = String.format(Locale.US, "%.1f s", ms / 1000.0)

    private fun service(s: GattServiceResult) =
        GattServiceState(s.uuid, GattNames.service(s.uuid), s.characteristics.map(::characteristic))

    private fun characteristic(c: GattCharacteristicResult): GattCharState {
        val v = c.value
        val value = if (v.status == GattReadValue.OK) v.hex?.let { GattNames.valueText(c.uuid, it) } else null
        return GattCharState(
            uuid = c.uuid,
            name = GattNames.characteristic(c.uuid),
            properties = c.properties.joinToString(" ") { GattNames.property(it) },
            value = value,
            error = errorText(v.status),
            descriptors = c.descriptors.map { d ->
                GattDescriptorState(
                    uuid = d.uuid,
                    name = GattNames.descriptor(d.uuid),
                    value = if (d.value.status == GattReadValue.OK) d.value.hex?.let { GattNames.valueText(d.uuid, it) } else null,
                    error = errorText(d.value.status),
                )
            },
        )
    }

    /** The contract's read status to words; null when there is nothing wrong to show. */
    fun errorText(status: String): String? = when (status) {
        GattReadValue.OK, GattReadValue.NOT_READ -> null
        GattReadValue.INSUFFICIENT_AUTHENTICATION -> "需要认证"
        GattReadValue.INSUFFICIENT_ENCRYPTION -> "需要加密"
        GattReadValue.READ_NOT_PERMITTED -> "不允许读取"
        GattReadValue.TIMEOUT -> "读取超时"
        else -> if (status.startsWith("error:")) "错误 ${status.removePrefix("error:")}" else status
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
        "broadcast" to "广播", "read" to "读", "write_no_response" to "无响应写", "write" to "写",
        "notify" to "通知", "indicate" to "指示", "signed_write" to "签名写", "extended" to "扩展",
    )

    private val descriptors = mapOf(
        "2900" to "Characteristic Extended Properties", "2901" to "Characteristic User Description",
        "2902" to "Client Characteristic Configuration", "2903" to "Server Characteristic Configuration",
        "2904" to "Characteristic Presentation Format",
    )

    fun descriptor(uuid: String): String? = descriptors[uuid]
    fun service(uuid: String): String? = services[uuid]
    fun characteristic(uuid: String): String? = characteristics[uuid]
    fun property(wire: String): String = properties[wire] ?: wire

    /** ASCII text in quotes when every byte is printable, a few well-known numeric values, otherwise hex. */
    fun valueText(uuid: String, hex: String): String {
        if (hex.isEmpty()) return "(空)"
        val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (uuid == "2A19" && bytes.size == 1) return "${bytes[0]} %"
        if (bytes.all { it in 0x20..0x7e }) return "\"" + String(CharArray(bytes.size) { bytes[it].toChar() }) + "\""
        val shown = bytes.take(32).joinToString(" ") { "%02x".format(it) }
        return if (bytes.size > 32) "$shown … (${bytes.size} 字节)" else shown
    }
}
