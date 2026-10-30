package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import java.util.UUID

/** Stages of one manual discovery, in order (docs/payload-schema-v3.md, `gatt/v1`). */
enum class GattStage(val wire: String) {
    CONNECT("connect"),
    MTU("mtu"),
    DISCOVER("discover"),
    READ("read"),
    DISCONNECT("disconnect"),
}

enum class GattOutcome(val wire: String) {
    /** Everything readable was attempted and the connection stayed up. */
    COMPLETE("complete"),

    /** Some results were kept, but the discovery stopped early. */
    PARTIAL("partial"),

    /** No services were obtained. */
    FAILED("failed"),

    /** The operator stopped it; whatever was collected is kept. */
    CANCELLED("cancelled"),
}

enum class DisconnectInitiator(val wire: String) { LOCAL("local"), REMOTE("remote"), TIMEOUT("timeout") }

data class GattReadValue(val hex: String?, val status: String) {
    companion object {
        const val OK = "ok"
        const val INSUFFICIENT_AUTHENTICATION = "insufficient_authentication"
        const val INSUFFICIENT_ENCRYPTION = "insufficient_encryption"
        const val READ_NOT_PERMITTED = "read_not_permitted"
        const val TIMEOUT = "timeout"
        const val NOT_READ = "not_read"

        val NOT_READ_VALUE = GattReadValue(null, NOT_READ)

        /** ATT attribute values are at most 512 bytes. */
        const val MAX_VALUE_BYTES = 512

        /**
         * A longer value breaks the protocol; it is recorded as an error rather than
         * clipped, because a clipped value would claim to be complete.
         */
        fun ok(bytes: ByteArray) =
            if (bytes.size > MAX_VALUE_BYTES) failed(GattStatus.INVALID_ATTRIBUTE_LENGTH) else GattReadValue(bytes.toHex(), OK)

        /** Android `BluetoothGatt` read status to the contract's status vocabulary. */
        fun failed(androidStatus: Int) = GattReadValue(
            null,
            when (androidStatus) {
                GattStatus.INSUFFICIENT_AUTHENTICATION -> INSUFFICIENT_AUTHENTICATION
                GattStatus.INSUFFICIENT_ENCRYPTION -> INSUFFICIENT_ENCRYPTION
                GattStatus.READ_NOT_PERMITTED -> READ_NOT_PERMITTED
                else -> "error:$androidStatus"
            },
        )
    }
}

/** `BluetoothGatt` status codes the engine interprets (androidx-free so JVM tests can use them). */
object GattStatus {
    const val SUCCESS = 0
    const val READ_NOT_PERMITTED = 2
    const val INVALID_ATTRIBUTE_LENGTH = 13
    const val INSUFFICIENT_AUTHENTICATION = 5
    const val INSUFFICIENT_ENCRYPTION = 15
    const val CONN_TIMEOUT = 8
    const val CONN_TERMINATE_PEER_USER = 19
    const val CONN_TERMINATE_LOCAL_HOST = 22
    const val CONN_FAIL_ESTABLISH = 62
    const val ERROR = 133

    /** `BluetoothGatt.GATT_FAILURE`: the stack refused the call itself. */
    const val FAILURE = 257

    /** Engine-side marker: the stage timed out, there is no Android status. */
    const val TIMED_OUT = -1

    /** A read never answered and blocks every later read (Android allows one outstanding request). */
    const val READ_QUEUE_STALLED = GattRunner.GATT_READ_QUEUE_STALLED

    /**
     * The engine itself threw; the discovery is stored as failed rather than lost. The runner's code, so it never
     * reads as [GattRunner.GATT_UNAVAILABLE] (−4).
     */
    const val INTERNAL_ERROR = GattRunner.GATT_INTERNAL_ERROR

    /** Recorded for a discovery that was in flight when the process died. */
    const val PROCESS_DEATH = GattRunner.GATT_PROCESS_DEATH

    fun name(status: Int): String = when (status) {
        SUCCESS -> "GATT_SUCCESS"
        READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
        INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
        INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
        INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
        CONN_TIMEOUT -> "GATT_CONN_TIMEOUT"
        CONN_TERMINATE_PEER_USER -> "GATT_CONN_TERMINATE_PEER_USER"
        CONN_TERMINATE_LOCAL_HOST -> "GATT_CONN_TERMINATE_LOCAL_HOST"
        CONN_FAIL_ESTABLISH -> "GATT_CONN_FAIL_ESTABLISH"
        ERROR -> "GATT_ERROR"
        FAILURE -> "GATT_FAILURE"
        TIMED_OUT -> "TIMEOUT"
        PROCESS_DEATH -> "PROCESS_DEATH"
        READ_QUEUE_STALLED -> "READ_QUEUE_STALLED"
        INTERNAL_ERROR -> "INTERNAL_ERROR"
        else -> "STATUS_$status"
    }
}

data class GattDescriptorResult(val uuid: String, val value: GattReadValue)

data class GattCharacteristicResult(
    val uuid: String,
    val instance: Int,
    val properties: List<String>,
    val value: GattReadValue,
    val descriptors: List<GattDescriptorResult>,
)

data class GattServiceResult(
    val uuid: String,
    val primary: Boolean,
    val instance: Int,
    val characteristics: List<GattCharacteristicResult>,
)

data class GattError(val stage: GattStage, val status: Int, val name: String)

data class GattDisconnect(val initiator: DisconnectInitiator, val status: Int?)

data class GattPhy(val tx: Int, val rx: Int)

data class GattTimings(val connectMs: Long?, val mtuMs: Long?, val discoverMs: Long?, val readMs: Long?)

/** The advertisement the operator picked the target from. */
data class GattTriggerAdv(
    val raw: ByteArray,
    val rssi: Int?,
    val scannedAtMs: Long,
    val isLegacy: Boolean?,
    val primaryPhy: Int?,
    val secondaryPhy: Int?,
    val advSid: Int?,
)

data class GattTarget(
    val addr: String,
    /** `public`, `random` or null when the platform did not say. */
    val addrType: String?,
    val name: String?,
    val triggerAdv: GattTriggerAdv?,
)

data class GattLocation(val lat: Double, val lon: Double, val accuracy: Double) {
    companion object {
        /** A fix the server would accept (finite, in range, nonnegative accuracy), or null. */
        fun validOrNull(lat: Double, lon: Double, accuracy: Double): GattLocation? =
            if (lat.isFinite() && lon.isFinite() && accuracy.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 && accuracy >= 0.0
            ) {
                GattLocation(lat, lon, accuracy)
            } else {
                null
            }
    }
}

/** Everything the engine needs to know to run one discovery; metadata is copied into the result. */
data class GattRequest(
    val id: String,
    val sessionId: String?,
    val target: GattTarget,
    val location: GattLocation?,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
)

data class GattDiscoveryResult(
    val request: GattRequest,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val outcome: GattOutcome,
    val stageReached: GattStage,
    val error: GattError?,
    val disconnect: GattDisconnect?,
    val mtu: Int?,
    val phy: GattPhy?,
    val timings: GattTimings,
    val services: List<GattServiceResult>,
)

/** Live progress for the UI. */
data class GattProgress(
    val stage: GattStage,
    /** Reads finished so far, and the total once discovery has listed them. */
    val readsDone: Int = 0,
    val readsTotal: Int? = null,
    val attempt: Int = 1,
    /** Connect plus MTU time once connected (D-13 "0.8 s"; §2.7 folds MTU into Connecting). */
    val connectMs: Long? = null,
    /** Services found, once discovery finished (D-13 "N services"). */
    val services: Int? = null,
)

private val HEX = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        out[index * 2] = HEX[value ushr 4]
        out[index * 2 + 1] = HEX[value and 0x0f]
    }
    return String(out)
}

private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** 16-bit Bluetooth-base UUIDs as four uppercase hex digits; everything else lowercase 128-bit. */
fun contractUuid(uuid: UUID): String {
    val text = uuid.toString().lowercase()
    return if (text.startsWith("0000") && text.endsWith(BASE_UUID_SUFFIX)) {
        text.substring(4, 8).uppercase()
    } else {
        text
    }
}

/** Characteristic property bits in the contract's fixed order. */
fun propertyNames(mask: Int): List<String> = buildList {
    if (mask and 0x01 != 0) add("broadcast")
    if (mask and 0x02 != 0) add("read")
    if (mask and 0x04 != 0) add("write_no_response")
    if (mask and 0x08 != 0) add("write")
    if (mask and 0x10 != 0) add("notify")
    if (mask and 0x20 != 0) add("indicate")
    if (mask and 0x40 != 0) add("signed_write")
    if (mask and 0x80 != 0) add("extended_props")
}

const val PROPERTY_READ = 0x02
