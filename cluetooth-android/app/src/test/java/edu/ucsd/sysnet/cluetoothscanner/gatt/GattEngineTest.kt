package edu.ucsd.sysnet.cluetoothscanner.gatt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattEngineTest {
    private val request = GattRequest(
        id = "0195ca99-5000-7131-b131-313131313131",
        sessionId = null,
        target = GattTarget("AA:BB:CC:DD:EE:FF", "random", "sensor", null),
        location = null,
        appVersion = "0.1.0-test",
        deviceModel = "Pixel 8",
        androidVersion = "15 (API 35)",
    )

    private fun TestScope.engine(factory: GattLinkFactory, progress: MutableList<GattProgress> = mutableListOf()) =
        GattEngine(
            linkFactory = factory,
            timeouts = GattTimeouts(),
            nowMs = { testScheduler.currentTime },
            onProgress = { progress += it },
        )

    private fun GattDiscoveryResult.value(service: Int, characteristic: Int) =
        services[service].characteristics[characteristic].value

    private fun GattDiscoveryResult.descriptor(service: Int, characteristic: Int, descriptor: Int = 0) =
        services[service].characteristics[characteristic].descriptors[descriptor].value

    // --- success -------------------------------------------------------------------------

    @Test
    fun `success reads every readable attribute and finishes complete`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(Cooperative))
        val progress = mutableListOf<GattProgress>()
        val result = engine(factory, progress).run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattStage.DISCONNECT, result.stageReached)
        assertNull(result.error)
        assertEquals(GattStatus.SUCCESS, result.disconnect?.status)
        assertEquals(DisconnectInitiator.LOCAL, result.disconnect?.initiator)
        assertEquals(247, result.mtu)
        assertEquals(GattPhy(2, 2), result.phy)
        assertEquals(listOf("180A", "180F"), result.services.map { it.uuid })
        assertEquals(
            listOf("2A29", "2A25"),
            result.services[0].characteristics.map { it.uuid },
        )
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0))), result.value(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0, 0))), result.descriptor(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 1))), result.value(0, 1))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(1, 0, 0))), result.descriptor(1, 0))
        // The write-only characteristic is never read; the contract records that.
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(1, 1))
        assertEquals(listOf("read", "notify"), result.services[1].characteristics[0].properties)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", result.services[1].characteristics[1].uuid)
        assertEquals(12, result.services[0].instance)
        assertEquals(14, result.services[0].characteristics[0].instance)

        val link = factory.links.single()
        assertEquals(SAMPLE_READ_ORDER, link.reads)
        assertEquals(1, link.mtuRequests)
        assertEquals(1, link.disconnects)
        assertEquals(1, link.closes)
        assertEquals(
            listOf(GattStage.CONNECT, GattStage.MTU, GattStage.DISCOVER, GattStage.READ, GattStage.DISCONNECT),
            progress.map { it.stage }.distinctUntilChangedList(),
        )
        assertEquals(5, progress.last { it.stage == GattStage.READ }.readsDone)
        assertEquals(5, progress.last { it.stage == GattStage.READ }.readsTotal)
        assertEquals(GattStage.DISCONNECT, progress.last().stage)
    }

    private fun <T> List<T>.distinctUntilChangedList(): List<T> =
        fold(mutableListOf()) { acc, item -> if (acc.lastOrNull() != item) acc.add(item); acc }

    @Test
    fun `an empty gatt table completes with no services`() = runTest {
        val result = engine(FakeFactory(emptyList(), Cooperative)).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertTrue(result.services.isEmpty())
    }

    // --- timeouts at each stage ----------------------------------------------------------

    @Test
    fun `connect timeout fails at connect and closes`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattStage.CONNECT, result.stageReached)
        assertEquals(GattError(GattStage.CONNECT, GattStatus.TIMED_OUT, "TIMEOUT"), result.error)
        assertEquals(DisconnectInitiator.TIMEOUT, result.disconnect?.initiator)
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
        assertTrue(result.services.isEmpty())
    }

    @Test
    fun `an unanswered mtu request is fine and the discovery carries on`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean = true // never answered
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertNull(result.mtu)
        assertEquals(3_000L, result.timings.mtuMs)
    }

    @Test
    fun `a refused mtu request is fine too`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean = false
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertNull(result.mtu)
    }

    @Test
    fun `an mtu outside the att range is not recorded`() = runTest {
        for (reported in listOf(22, 518, 0, -1)) {
            val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
                override fun onMtu(link: FakeLink, mtu: Int): Boolean {
                    link.emit(GattEvent.MtuChanged(reported, GattStatus.SUCCESS))
                    return true
                }
            }))
            assertNull("$reported", engine(factory).run(request).mtu)
        }
        for (edge in listOf(23, 517)) {
            val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
                override fun onMtu(link: FakeLink, mtu: Int): Boolean {
                    link.emit(GattEvent.MtuChanged(edge, GattStatus.SUCCESS))
                    return true
                }
            }))
            assertEquals(edge, engine(factory).run(request).mtu)
        }
    }

    @Test
    fun `a phy outside 1 to 3 is not recorded`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean {
                link.emit(GattEvent.PhyRead(0, 4, GattStatus.SUCCESS))
                link.emit(GattEvent.MtuChanged(247, GattStatus.SUCCESS))
                return true
            }
        }))
        // FakeLink.readPhy also reports (2, 2); the invalid pair must not replace it.
        assertEquals(GattPhy(2, 2), engine(factory).run(request).phy)
    }

    @Test
    fun `a clock stepping back never yields negative timings or an end before the start`() = runTest {
        var tick = 0L
        val engine = GattEngine(
            linkFactory = FakeFactory(attempts = arrayOf(Cooperative)),
            nowMs = { 1_000_000L - 100 * tick++ },
        )
        val result = engine.run(request)
        assertTrue(result.endedAtMs >= result.startedAtMs)
        listOf(result.timings.connectMs, result.timings.mtuMs, result.timings.discoverMs, result.timings.readMs)
            .forEach { assertTrue("$it", it == null || it >= 0) }
    }

    @Test
    fun `an over long attribute value is recorded as an error and not clipped`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                link.emit(GattEvent.AttributeRead(ref, ByteArray(513), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattReadValue(null, "error:13"), result.value(0, 0))
        assertEquals(GattOutcome.COMPLETE, result.outcome)
    }

    @Test
    fun `a failed mtu status keeps the default mtu`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean {
                link.emit(GattEvent.MtuChanged(23, GattStatus.ERROR))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertNull(result.mtu)
    }

    @Test
    fun `discovery timeout fails at discover with no services`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink): Boolean = true // never answered
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattStage.DISCOVER, result.stageReached)
        assertEquals(GattError(GattStage.DISCOVER, GattStatus.TIMED_OUT, "TIMEOUT"), result.error)
        assertEquals(DisconnectInitiator.TIMEOUT, result.disconnect?.initiator)
        assertTrue(result.services.isEmpty())
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `a silent read is recorded as timeout and the next attribute is read`() = runTest {
        val silent = AttributeRef(0, 0, 0)
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref != silent) link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattReadValue(null, "timeout"), result.descriptor(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 1))), result.value(0, 1))
        assertEquals(SAMPLE_READ_ORDER, factory.links.single().reads)
    }

    @Test
    fun `the read stage stops at its total budget and keeps what it has`() = runTest {
        // Every read takes the full per-read timeout; ten of them exhaust 30 s.
        val manyServices = listOf(
            GattServiceSnapshot(
                uuid16(0x1800), true, 1,
                (0 until 20).map { index ->
                    GattCharacteristicSnapshot(uuid16(0x2A00 + index), 10 + index, 0x02, emptyList())
                },
            ),
        )
        val factory = FakeFactory(manyServices, object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean = true
        })
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(GattError(GattStage.READ, GattStatus.TIMED_OUT, "TIMEOUT"), result.error)
        assertEquals(DisconnectInitiator.TIMEOUT, result.disconnect?.initiator)
        val values = result.services[0].characteristics.map { it.value.status }
        assertEquals(10, values.count { it == "timeout" })
        assertEquals(10, values.count { it == "not_read" })
        assertEquals(30_000L, result.timings.readMs)
        assertEquals(1, factory.links.single().closes)
    }

    // --- disconnects ---------------------------------------------------------------------

    @Test
    fun `remote disconnect mid read keeps the collected values as a partial result`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == AttributeRef(0, 1)) {
                    link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER))
                } else {
                    link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                }
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(
            GattError(GattStage.READ, 19, "GATT_CONN_TERMINATE_PEER_USER"),
            result.error,
        )
        assertEquals(GattDisconnect(DisconnectInitiator.REMOTE, 19), result.disconnect)
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0))), result.value(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0, 0))), result.descriptor(0, 0))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(1, 0))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.descriptor(1, 0))
        // The stack already dropped the link; the engine still releases it exactly once.
        assertEquals(0, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `disconnect while discovering is a failure not a partial`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink): Boolean {
                link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattStage.DISCOVER, result.stageReached)
        assertEquals(19, result.error?.status)
        assertEquals(1, factory.links.size) // 19 is not retried
    }

    // --- status 133 ----------------------------------------------------------------------

    private val failsWith133 = object : FakeLink.Behavior {
        override fun onConnect(link: FakeLink) {
            link.emit(GattEvent.Disconnected(GattStatus.ERROR))
        }
    }

    @Test
    fun `status 133 is retried once after a backoff and then succeeds`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(failsWith133, Cooperative))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertNull(result.error)
        assertEquals(2, factory.links.size)
        assertTrue(factory.links.all { it.closes == 1 })
        assertEquals(SAMPLE_READ_ORDER, factory.links[1].reads)
        assertEquals(0, factory.links[0].reads.size)
        // The backoff separates the two attempts.
        assertTrue(testScheduler.currentTime >= 1_000)
        // Nothing of the failed attempt leaks into the result.
        assertEquals(247, result.mtu)
    }

    @Test
    fun `status 133 twice fails after exactly one retry`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(failsWith133, failsWith133, Cooperative))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattError(GattStage.CONNECT, 133, "GATT_ERROR"), result.error)
        assertEquals(GattDisconnect(DisconnectInitiator.REMOTE, 133), result.disconnect)
        assertEquals(2, factory.links.size)
        assertTrue(factory.links.all { it.closes == 1 })
    }

    @Test
    fun `status 133 during discovery is also retried`() = runTest {
        val flaky = object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink): Boolean {
                link.emit(GattEvent.ServicesDiscovered(GattStatus.ERROR))
                return true
            }
        }
        val factory = FakeFactory(attempts = arrayOf(flaky, Cooperative))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(2, factory.links.size)
        assertEquals(1, factory.links[0].disconnects)
    }

    @Test
    fun `a 133 after reads started is not retried`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                link.emit(GattEvent.Disconnected(GattStatus.ERROR))
                return true
            }
        }, Cooperative))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(1, factory.links.size)
    }

    // --- cancel --------------------------------------------------------------------------

    @Test
    fun `cancel during a read keeps earlier values and ends as cancelled`() = runTest {
        val hang = AttributeRef(0, 1)
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref != hang) link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val engine = engine(factory)
        val running = async { engine.run(request) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(factory.links.single().reads.contains(hang))
        engine.cancel()
        val result = running.await()

        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0))), result.value(0, 0))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertEquals(GattDisconnect(DisconnectInitiator.LOCAL, GattStatus.SUCCESS), result.disconnect)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
        // Cancelling did not wait for the 3 s read timeout.
        assertTrue(testScheduler.currentTime < 3_000)
    }

    @Test
    fun `cancel while connecting disconnects and closes`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onConnect(link: FakeLink) = Unit
        }))
        val engine = engine(factory)
        val running = async { engine.run(request) }
        advanceTimeBy(500)
        runCurrent()
        engine.cancel()
        val result = running.await()

        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(GattStage.CONNECT, result.stageReached)
        assertNull(result.error)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `cancel during the retry backoff does not start another attempt`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(failsWith133, Cooperative))
        val engine = engine(factory)
        val running = async { engine.run(request) }
        advanceTimeBy(200)
        runCurrent()
        engine.cancel()
        val result = running.await()
        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(1, factory.links.size)
        assertEquals(1, factory.links.single().closes)
    }

    // --- authentication / encryption -----------------------------------------------------

    @Test
    fun `an authentication required read is recorded and the next attribute is read`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                val status = when (ref) {
                    AttributeRef(0, 0) -> GattStatus.INSUFFICIENT_AUTHENTICATION
                    AttributeRef(0, 1) -> GattStatus.INSUFFICIENT_ENCRYPTION
                    AttributeRef(1, 0) -> GattStatus.READ_NOT_PERMITTED
                    AttributeRef(1, 0, 0) -> 14
                    else -> GattStatus.SUCCESS
                }
                link.emit(GattEvent.AttributeRead(ref, if (status == 0) valueFor(ref) else null, status))
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattReadValue(null, "insufficient_authentication"), result.value(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0, 0))), result.descriptor(0, 0))
        assertEquals(GattReadValue(null, "insufficient_encryption"), result.value(0, 1))
        assertEquals(GattReadValue(null, "read_not_permitted"), result.value(1, 0))
        assertEquals(GattReadValue(null, "error:14"), result.descriptor(1, 0))
        assertEquals(SAMPLE_READ_ORDER, factory.links.single().reads)
    }

    @Test
    fun `a read the stack refuses outright is recorded with the failure status`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == AttributeRef(0, 1)) return false
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattReadValue(null, "error:257"), result.value(0, 1))
        assertEquals(SAMPLE_READ_ORDER, factory.links.single().reads)
    }

    // --- stale events and edge cases -----------------------------------------------------

    @Test
    fun `a late read answer for an earlier attribute is ignored`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == AttributeRef(0, 0, 0)) {
                    // Answer the previous (first) attribute again instead of this one,
                    // then the real answer: the engine must wait for its own ref.
                    link.emit(GattEvent.AttributeRead(AttributeRef(0, 0), byteArrayOf(9), GattStatus.SUCCESS))
                }
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0, 0))), result.descriptor(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0))), result.value(0, 0))
    }

    @Test
    fun `an engine runs one discovery and reports the timestamps it was given`() = runTest {
        val result = engine(FakeFactory(attempts = arrayOf(Cooperative))).run(request)
        assertNotNull(result.endedAtMs)
        assertTrue(result.endedAtMs >= result.startedAtMs)
        assertEquals(request, result.request)
    }
}
