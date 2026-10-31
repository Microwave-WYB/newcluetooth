package edu.ucsd.sysnet.cluetoothscanner.gatt

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun uuid16(short: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

/** Device Information (read, read+descriptor), Battery (read+notify+CCCD), one write-only characteristic. */
fun sampleServices(): List<GattServiceSnapshot> = listOf(
    GattServiceSnapshot(
        uuid = uuid16(0x180A),
        primary = true,
        instance = 12,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                uuid = uuid16(0x2A29),
                instance = 14,
                propertyMask = 0x02,
                descriptors = listOf(GattDescriptorSnapshot(uuid16(0x2901))),
            ),
            GattCharacteristicSnapshot(uuid16(0x2A25), 16, 0x02, emptyList()),
        ),
    ),
    GattServiceSnapshot(
        uuid = uuid16(0x180F),
        primary = true,
        instance = 20,
        characteristics = listOf(
            GattCharacteristicSnapshot(
                uuid = uuid16(0x2A19),
                instance = 22,
                propertyMask = 0x12,
                descriptors = listOf(GattDescriptorSnapshot(uuid16(0x2902))),
            ),
            GattCharacteristicSnapshot(
                uuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
                instance = 24,
                propertyMask = 0x08,
                descriptors = emptyList(),
            ),
        ),
    ),
)

/** Reads the sample tree yields in order: 2A29, 2901, 2A25, 2A19, 2902 (the write-only one is skipped). */
val SAMPLE_READ_ORDER = listOf(
    AttributeRef(0, 0),
    AttributeRef(0, 0, 0),
    AttributeRef(0, 1),
    AttributeRef(1, 0),
    AttributeRef(1, 0, 0),
)

/**
 * A scripted peripheral. Every callback in [behavior] may deliver events immediately
 * (the engine's channel buffers them) or stay silent to model a timeout.
 */
class FakeLink(
    private val sink: GattEventSink,
    private val services: List<GattServiceSnapshot>,
    private val behavior: Behavior,
    private val scope: CoroutineScope? = null,
) : GattLink {
    interface Behavior {
        fun onConnect(link: FakeLink) {
            link.emit(GattEvent.Connected)
        }

        fun onMtu(link: FakeLink, mtu: Int): Boolean {
            link.emit(GattEvent.MtuChanged(247, GattStatus.SUCCESS))
            return true
        }

        fun onDiscover(link: FakeLink): Boolean {
            link.emit(GattEvent.ServicesDiscovered(GattStatus.SUCCESS))
            return true
        }

        fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
            link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
            return true
        }

        fun onDisconnect(link: FakeLink) {
            link.emit(GattEvent.Disconnected(GattStatus.SUCCESS))
        }
    }

    /**
     * Like `BluetoothGatt`, the stack takes one read at a time: while a request is
     * outstanding (not yet answered by a callback) every new read is refused.
     */
    var outstanding: AttributeRef? = null
        private set
    var refusedWhileOutstanding = 0
        private set
    val reads = mutableListOf<AttributeRef>()
    var mtuRequests = 0
    var disconnects = 0
    var closes = 0
    var phyReads = 0

    fun emit(event: GattEvent) {
        if (event is GattEvent.AttributeRead && event.ref == outstanding) outstanding = null
        if (event is GattEvent.Disconnected) outstanding = null
        sink.deliver(event)
    }

    /** Delivers [block] after [delayMs] of (virtual) time; needs the factory's scope. */
    fun after(delayMs: Long, block: () -> Unit) {
        checkNotNull(scope) { "the factory needs a scope to schedule late callbacks" }.launch {
            delay(delayMs)
            block()
        }
    }

    init {
        behavior.onConnect(this)
    }

    override fun requestMtu(mtu: Int): Boolean {
        mtuRequests++
        return behavior.onMtu(this, mtu)
    }

    override fun readPhy() {
        phyReads++
        emit(GattEvent.PhyRead(2, 2, GattStatus.SUCCESS))
    }

    override fun discoverServices(): Boolean = behavior.onDiscover(this)

    override fun services(): List<GattServiceSnapshot> = services

    override fun read(ref: AttributeRef): Boolean {
        if (outstanding != null) {
            refusedWhileOutstanding++
            return false
        }
        reads += ref
        outstanding = ref
        val accepted = behavior.onRead(this, ref)
        if (!accepted) outstanding = null
        return accepted
    }

    override fun disconnect() {
        disconnects++
        behavior.onDisconnect(this)
    }

    override fun close() {
        closes++
    }
}

/** Deterministic bytes per attribute: [index of ref in the read order + 1] repeated. */
fun valueFor(ref: AttributeRef): ByteArray = byteArrayOf(
    (ref.service * 16 + ref.characteristic * 4 + (ref.descriptor ?: 3) + 0x40).toByte(),
)

class FakeFactory(
    private val services: List<GattServiceSnapshot> = sampleServices(),
    private val scope: CoroutineScope? = null,
    private vararg val attempts: FakeLink.Behavior,
) : GattLinkFactory {
    val links = mutableListOf<FakeLink>()

    override fun connect(sink: GattEventSink): GattLink {
        val behavior = attempts.getOrElse(links.size) { attempts.last() }
        return FakeLink(sink, services, behavior, scope).also { links += it }
    }
}

/** Plain defaults: connect, MTU 247, discover and every read succeed. */
object Cooperative : FakeLink.Behavior
