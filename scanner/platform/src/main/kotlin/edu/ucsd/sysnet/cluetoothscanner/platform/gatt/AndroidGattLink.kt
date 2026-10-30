package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.IdentityHashMap

/**
 * `BluetoothGatt` behind the engine's [GattLink]: LE transport, `autoConnect = false`.
 * Only connect, MTU, PHY read, service discovery, reads and disconnect are reachable from
 * here — there is no write, notification or bonding call in this class.
 */
@SuppressLint("MissingPermission") // The manager checks BLUETOOTH_CONNECT before starting.
class AndroidGattLinkFactory(
    private val context: Context,
    private val device: BluetoothDevice,
) : GattLinkFactory {
    /** Null when the stack refused the connection (`connectGatt` returned null): the engine records CALL_REJECTED. */
    override fun connect(sink: GattEventSink): GattLink? = AndroidGattLink(context, device, sink).takeIf { it.opened }
}

@SuppressLint("MissingPermission")
internal class AndroidGattLink(
    context: Context,
    device: BluetoothDevice,
    private val sink: GattEventSink,
) : GattLink {
    private val characteristicRefs = IdentityHashMap<BluetoothGattCharacteristic, AttributeRef>()
    private val descriptorRefs = IdentityHashMap<BluetoothGattDescriptor, AttributeRef>()
    private val characteristicsByRef = HashMap<AttributeRef, BluetoothGattCharacteristic>()
    private val descriptorsByRef = HashMap<AttributeRef, BluetoothGattDescriptor>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                    sink.deliver(GattEvent.Connected)
                // A failed attempt arrives as DISCONNECTED (or CONNECTED with an error status).
                newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS ->
                    sink.deliver(GattEvent.Disconnected(status))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            sink.deliver(GattEvent.MtuChanged(mtu, status))
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            sink.deliver(GattEvent.PhyRead(txPhy, rxPhy, status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            sink.deliver(GattEvent.ServicesDiscovered(status))
        }

        // API 33+ delivers the value as an argument; older systems use the deprecated pair.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            characteristicRefs[characteristic]?.let { sink.deliver(GattEvent.AttributeRead(it, value, status)) }
        }

        @Deprecated("Deprecated in API 33; used below it")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            characteristicRefs[characteristic]?.let {
                sink.deliver(GattEvent.AttributeRead(it, characteristic.value?.copyOf(), status))
            }
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            descriptorRefs[descriptor]?.let { sink.deliver(GattEvent.AttributeRead(it, value, status)) }
        }

        @Deprecated("Deprecated in API 33; used below it")
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            descriptorRefs[descriptor]?.let {
                sink.deliver(GattEvent.AttributeRead(it, descriptor.value?.copyOf(), status))
            }
        }
    }

    private val gatt: BluetoothGatt? = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

    /** False when `connectGatt` returned null; the factory then hands out no link. */
    val opened: Boolean get() = gatt != null

    override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) ?: false

    override fun readPhy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) gatt?.readPhy()
    }

    override fun discoverServices(): Boolean = gatt?.discoverServices() ?: false

    override fun services(): List<GattServiceSnapshot> {
        val services = gatt?.services.orEmpty()
        return services.mapIndexed { s, service ->
            GattServiceSnapshot(
                uuid = service.uuid,
                primary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
                instance = service.instanceId,
                characteristics = service.characteristics.mapIndexed { c, characteristic ->
                    val characteristicRef = AttributeRef(s, c)
                    characteristicRefs[characteristic] = characteristicRef
                    characteristicsByRef[characteristicRef] = characteristic
                    GattCharacteristicSnapshot(
                        uuid = characteristic.uuid,
                        instance = characteristic.instanceId,
                        propertyMask = characteristic.properties,
                        descriptors = characteristic.descriptors.mapIndexed { d, descriptor ->
                            val descriptorRef = AttributeRef(s, c, d)
                            descriptorRefs[descriptor] = descriptorRef
                            descriptorsByRef[descriptorRef] = descriptor
                            GattDescriptorSnapshot(descriptor.uuid)
                        },
                    )
                },
            )
        }
    }

    override fun read(ref: AttributeRef): Boolean {
        val descriptor = ref.descriptor
        return if (descriptor == null) {
            characteristicsByRef[ref]?.let { gatt?.readCharacteristic(it) } ?: false
        } else {
            descriptorsByRef[ref]?.let { gatt?.readDescriptor(it) } ?: false
        }
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun close() {
        gatt?.close()
    }
}
