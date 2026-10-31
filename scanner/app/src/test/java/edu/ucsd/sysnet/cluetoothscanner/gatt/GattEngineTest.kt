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
        val result = engine(FakeFactory(emptyList(), attempts = arrayOf(Cooperative))).run(request)
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
    fun `a read that answers late within the grace period keeps its real value and the queue moves on`() = runTest {
        val slow = AttributeRef(0, 0, 0)
        val factory = FakeFactory(scope = this, attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == slow) {
                    // Past the 3 s read timeout, inside the grace period.
                    link.after(4_000) { link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS)) }
                } else {
                    link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                }
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattReadValue.ok(valueFor(slow)), result.descriptor(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 1))), result.value(0, 1))
        assertEquals(SAMPLE_READ_ORDER, factory.links.single().reads)
        // The engine never queued a request behind an outstanding one.
        assertEquals(0, factory.links.single().refusedWhileOutstanding)
    }

    @Test
    fun `a read that never answers stalls the queue and ends the discovery as partial`() = runTest {
        val silent = AttributeRef(0, 0, 0)
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref != silent) link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(GattError(GattStage.READ, GattStatus.READ_QUEUE_STALLED, "READ_QUEUE_STALLED"), result.error)
        assertEquals(DisconnectInitiator.TIMEOUT, result.disconnect?.initiator)
        // The silent attribute is recorded as a timeout; nothing after it is read, and nothing
        // is recorded as a stack refusal (error:257).
        assertEquals(GattReadValue(null, "timeout"), result.descriptor(0, 0))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0))), result.value(0, 0))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(1, 0))
        val statuses = result.services.flatMap { s -> s.characteristics.flatMap { c -> listOf(c.value) + c.descriptors.map { it.value } } }
        assertTrue(statuses.none { it.status == "error:257" })
        assertEquals(listOf(AttributeRef(0, 0), silent), factory.links.single().reads)
        assertEquals(0, factory.links.single().refusedWhileOutstanding)
        assertEquals(1, factory.links.single().closes)
        // 3 s read timeout plus the 5 s grace period: the stall is declared at that point.
        assertEquals(3_000L + 5_000L, result.timings.readMs)
    }

    @Test
    fun `the read stage stops at its total budget and keeps what it has`() = runTest {
        // Every read takes 2.9 s, so ten of them nearly use the 30 s budget.
        val manyServices = listOf(
            GattServiceSnapshot(
                uuid16(0x1800), true, 1,
                (0 until 20).map { index ->
                    GattCharacteristicSnapshot(uuid16(0x2A00 + index), 10 + index, 0x02, emptyList())
                },
            ),
        )
        val factory = FakeFactory(manyServices, this, attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                link.after(2_900) { link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS)) }
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(GattError(GattStage.READ, GattStatus.TIMED_OUT, "TIMEOUT"), result.error)
        assertEquals(DisconnectInitiator.TIMEOUT, result.disconnect?.initiator)
        val values = result.services[0].characteristics.map { it.value.status }
        // Ten reads fit. The eleventh runs into the end of the budget: it is recorded as a
        // timeout, no grace period is added past the budget, and nothing is read after it.
        assertEquals(10, values.count { it == "ok" })
        assertEquals(1, values.count { it == "timeout" })
        assertEquals(9, values.count { it == "not_read" })
        assertEquals(30_000L, result.timings.readMs)
        assertEquals(0, factory.links.single().refusedWhileOutstanding)
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
    fun `a standing refusal ends the discovery as partial and is not recorded as an attribute result`() = runTest {
        val refused = AttributeRef(0, 1)
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == refused) return false
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStage.READ, result.stageReached)
        assertEquals(GattError(GattStage.READ, GattStatus.FAILURE, "READ_REFUSED"), result.error)
        // The attribute stays "not_read": the refusal says nothing about the attribute.
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 0, 0))), result.descriptor(0, 0))
        assertEquals(3, factory.links.single().reads.count { it == refused })
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `a transient refusal is retried and the discovery completes`() = runTest {
        var refusals = 2
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == AttributeRef(0, 1) && refusals-- > 0) return false
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)
        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(GattReadValue.ok(valueFor(AttributeRef(0, 1))), result.value(0, 1))
    }

    @Test
    fun `a refused discoverServices fails at discover and disconnects`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink): Boolean = false
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattStage.DISCOVER, result.stageReached)
        assertEquals(GattError(GattStage.DISCOVER, GattStatus.FAILURE, "GATT_FAILURE"), result.error)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
        assertTrue(result.services.isEmpty())
    }

    @Test
    fun `a connectGatt that returns no client fails at connect without a link to leak`() = runTest {
        val result = engine(GattLinkFactory { throw IllegalStateException("connectGatt returned null") }).run(request)

        assertEquals(GattOutcome.FAILED, result.outcome)
        assertEquals(GattStage.CONNECT, result.stageReached)
        assertEquals(GattError(GattStage.CONNECT, GattStatus.FAILURE, "CALL_REJECTED"), result.error)
        assertNull(result.disconnect)
    }

    @Test
    fun `cancelling the coroutine mid discovery drops the connection and releases the link once`() = runTest {
        val hang = AttributeRef(0, 1)
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref != hang) link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val running = async { engine(factory).run(request) }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(factory.links.single().reads.contains(hang))
        running.cancel()
        runCurrent()

        assertTrue(running.isCancelled)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
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

    // --- more cancellation, refusals and clocks --------------------------------------------

    @Test
    fun `cancel while waiting for the mtu answer disconnects and closes`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean = true // never answered
        }))
        val engine = engine(factory)
        val running = async { engine.run(request) }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, factory.links.single().mtuRequests)
        engine.cancel()
        val result = running.await()

        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(GattStage.MTU, result.stageReached)
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
        assertTrue(testScheduler.currentTime < 3_000) // did not wait out the mtu timeout
    }

    @Test
    fun `cancel while waiting for service discovery disconnects and closes`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onDiscover(link: FakeLink): Boolean = true // never answered
        }))
        val engine = engine(factory)
        val running = async { engine.run(request) }
        advanceTimeBy(5_000)
        runCurrent()
        engine.cancel()
        val result = running.await()

        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(GattStage.DISCOVER, result.stageReached)
        assertTrue(result.services.isEmpty())
        assertEquals(1, factory.links.single().disconnects)
        assertEquals(1, factory.links.single().closes)
        assertTrue(testScheduler.currentTime < 15_000)
    }

    @Test
    fun `cancel is noticed while the stack keeps refusing reads`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == AttributeRef(0, 1)) {
                    // Cancel arrives during the pause between refused attempts.
                    return false
                }
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val engine = GattEngine(
            linkFactory = factory,
            timeouts = GattTimeouts(readRefusedTries = 50, readRefusedBackoffMs = 1_000),
            nowMs = { testScheduler.currentTime },
        )
        val running = async { engine.run(request) }
        advanceTimeBy(1_500)
        runCurrent()
        engine.cancel()
        val result = running.await()

        assertEquals(GattOutcome.CANCELLED, result.outcome)
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertTrue("cancel took ${testScheduler.currentTime} ms", testScheduler.currentTime < 3_000)
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `after a stalled read no later read is issued so no refusal cascade is recorded`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean = true // every read is silent
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattStatus.READ_QUEUE_STALLED, result.error?.status)
        // Exactly one request reached the stack; the remaining four attributes were never asked.
        assertEquals(1, factory.links.single().reads.size)
        assertEquals(0, factory.links.single().refusedWhileOutstanding)
        val all = result.services.flatMap { s -> s.characteristics.flatMap { c -> listOf(c.value) + c.descriptors.map { it.value } } }
        assertEquals(1, all.count { it.status == "timeout" })
        assertTrue(all.none { it.status.startsWith("error:") })
    }

    @Test
    fun `a revoked permission in any link call is a refusal and not a crash`() = runTest {
        val factory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
            override fun onMtu(link: FakeLink, mtu: Int): Boolean = throw SecurityException("no BLUETOOTH_CONNECT")
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean = throw SecurityException("no BLUETOOTH_CONNECT")
        }))
        val result = engine(factory).run(request)

        // MTU is optional; the first read is then refused for good.
        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattError(GattStage.READ, GattStatus.FAILURE, "READ_REFUSED"), result.error)
        assertEquals(1, factory.links.single().closes)
    }

    @Test
    fun `durations come from the monotonic clock even when the wall clock jumps`() = runTest {
        var wall = 1_742_860_800_000L
        val engine = GattEngine(
            linkFactory = FakeFactory(attempts = arrayOf(object : FakeLink.Behavior {
                override fun onConnect(link: FakeLink) {
                    link.after(800) { link.emit(GattEvent.Connected) }
                }
            }), scope = this),
            nowMs = { wall.also { wall -= 5_000 } }, // runs backwards, 5 s per look
            elapsedMs = { testScheduler.currentTime },
        )
        val result = engine.run(request)

        assertEquals(GattOutcome.COMPLETE, result.outcome)
        assertEquals(800L, result.timings.connectMs)
        assertTrue(result.endedAtMs >= result.startedAtMs)
    }

    @Test
    fun `the stall grace period is bounded by what is left of the read budget`() = runTest {
        val silent = object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean = true
        }
        // 4 s budget: a 3 s read timeout leaves 1 s, so the 5 s grace period is cut to 1 s and
        // the end is the budget's TIMEOUT, not a stalled queue.
        val bounded = GattEngine(
            linkFactory = FakeFactory(attempts = arrayOf(silent)),
            timeouts = GattTimeouts(readTotalMs = 4_000),
            nowMs = { testScheduler.currentTime },
        ).run(request)
        assertEquals(GattOutcome.PARTIAL, bounded.outcome)
        assertEquals(GattError(GattStage.READ, GattStatus.TIMED_OUT, "TIMEOUT"), bounded.error)
        assertEquals(4_000L, bounded.timings.readMs)

        // With a roomy budget the full grace applies and the queue counts as stalled.
        val roomy = GattEngine(
            linkFactory = FakeFactory(attempts = arrayOf(silent)),
            timeouts = GattTimeouts(readTotalMs = 30_000),
            nowMs = { testScheduler.currentTime },
        ).run(request)
        assertEquals(GattStatus.READ_QUEUE_STALLED, roomy.error?.status)
        assertEquals(8_000L, roomy.timings.readMs)
    }

    @Test
    fun `a remote disconnect during the pause between refused reads is not lost`() = runTest {
        val refused = AttributeRef(0, 1)
        val factory = FakeFactory(scope = this, attempts = arrayOf(object : FakeLink.Behavior {
            override fun onRead(link: FakeLink, ref: AttributeRef): Boolean {
                if (ref == refused) {
                    // Arrives inside the second 200 ms pause between refused attempts.
                    link.after(300) { link.emit(GattEvent.Disconnected(GattStatus.CONN_TERMINATE_PEER_USER)) }
                    return false
                }
                link.emit(GattEvent.AttributeRead(ref, valueFor(ref), GattStatus.SUCCESS))
                return true
            }
        }))
        val result = engine(factory).run(request)

        assertEquals(GattOutcome.PARTIAL, result.outcome)
        assertEquals(GattError(GattStage.READ, 19, "GATT_CONN_TERMINATE_PEER_USER"), result.error)
        assertEquals(GattDisconnect(DisconnectInitiator.REMOTE, 19), result.disconnect)
        assertEquals(GattReadValue.NOT_READ_VALUE, result.value(0, 1))
        assertEquals(1, factory.links.single().closes)
    }
}
