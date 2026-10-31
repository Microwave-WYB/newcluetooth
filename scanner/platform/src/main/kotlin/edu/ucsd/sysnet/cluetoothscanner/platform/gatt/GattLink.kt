package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import java.util.UUID

/** Position of a service, characteristic or descriptor in the discovered tree. */
data class AttributeRef(val service: Int, val characteristic: Int, val descriptor: Int? = null)

data class GattDescriptorSnapshot(val uuid: UUID)

data class GattCharacteristicSnapshot(
    val uuid: UUID,
    val instance: Int,
    val propertyMask: Int,
    val descriptors: List<GattDescriptorSnapshot>,
)

data class GattServiceSnapshot(
    val uuid: UUID,
    val primary: Boolean,
    val instance: Int,
    val characteristics: List<GattCharacteristicSnapshot>,
)

/** What the platform tells the engine; always delivered through [GattEventSink]. */
sealed interface GattEvent {
    /** `onConnectionStateChange(CONNECTED, status = SUCCESS)`. */
    data object Connected : GattEvent

    /** Disconnected, or a connection attempt that ended with this status. */
    data class Disconnected(val status: Int) : GattEvent
    data class MtuChanged(val mtu: Int, val status: Int) : GattEvent
    data class PhyRead(val tx: Int, val rx: Int, val status: Int) : GattEvent
    data class ServicesDiscovered(val status: Int) : GattEvent
    data class AttributeRead(val ref: AttributeRef, val value: ByteArray?, val status: Int) : GattEvent
}

/** Callbacks may arrive on any thread. */
fun interface GattEventSink {
    fun deliver(event: GattEvent)
}

/**
 * The slice of `BluetoothGatt` the engine drives. Implementations never write, never
 * enable notifications and never bond; they only expose the calls below.
 */
interface GattLink {
    /** False when the stack refused the request. */
    fun requestMtu(mtu: Int): Boolean
    fun readPhy()
    fun discoverServices(): Boolean
    fun services(): List<GattServiceSnapshot>
    fun read(ref: AttributeRef): Boolean
    fun disconnect()

    /** Must always be called exactly once per link; releases the client interface. */
    fun close()
}

fun interface GattLinkFactory {
    /** Starts an LE, `autoConnect = false` connection; the result arrives on [sink]. */
    fun connect(sink: GattEventSink): GattLink?
}
