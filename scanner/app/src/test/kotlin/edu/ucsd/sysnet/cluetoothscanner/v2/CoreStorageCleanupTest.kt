package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import edu.ucsd.sysnet.cluetoothscanner.api.core.ScanEnvironment
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** R-10's adapter on the core (#71): the event and the three calls, with each Err kept typed. */
class CoreStorageCleanupTest {
    private val env = ScanEnvironment("Pixel 8", "15", 35, "0.2.0", true, true, 1650, "all_supported", false)

    @Test fun theCoresStorageFullIsThePortsEvent() = runTest {
        val fake = FakeCore(backgroundScope, simulate = false)
        val port = CoreStorageCleanup { CoreHandle.of(fake) }
        fake.startRecording(env)
        val first = async(start = CoroutineStart.UNDISPATCHED) { port.storageFull.first() }
        fake.simulateStorageFull(150_000_000)
        assertEquals(Bytes(150_000_000), first.await())
    }

    @Test fun theCallsMapTheirValues() = runTest {
        val fake = FakeCore(backgroundScope, simulate = false)
        val port = CoreStorageCleanup { CoreHandle.of(fake) }
        fake.freeBytes = 150_000_000
        fake.uploadedRawBytes = 40_000_000
        val before = (port.reclaimable() as CleanupResult.Ok).value
        assertEquals(Bytes(150_000_000), before.free)
        assertEquals(Bytes(40_000_000), before.uploadedRaw)
        assertEquals(CleanupResult.Ok(Bytes(40_000_000)), port.deleteUploadedRaw())
        assertEquals(Bytes(190_000_000), (port.reclaimable() as CleanupResult.Ok).value.free)
        assertEquals(CleanupResult.Ok::class, port.clearLocalDatabase()::class)
    }

    @Test fun anErrKeepsTheCoresReason() {
        val err: CoreResult<Long> = CoreResult.Err(CoreException.Db("disk I/O error"))
        assertEquals(CleanupResult.Err(CleanupError.Failed("local database failed: disk I/O error")), err.toCleanup(::Bytes))
        assertEquals(CleanupResult.Ok(Bytes(5)), CoreResult.Ok(5L).toCleanup(::Bytes))
    }
}
