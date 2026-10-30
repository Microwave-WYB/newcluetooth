package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.api.core.Reclaimable
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

    /**
     * Each port call runs its own core call and passes its own value: the spy answers each with a distinct number,
     * so a cleanup wired to the other one's call, or swapped Reclaimable fields, fail here.
     */
    @Test fun eachCallRunsItsOwnCoreCall() = runTest {
        val spy = Spy(FakeCore(backgroundScope, simulate = false))
        val port = CoreStorageCleanup { CoreHandle.of(spy) }
        assertEquals(CleanupResult.Ok(Bytes(22)), port.clearLocalDatabase())
        assertEquals(listOf("clearLocalDatabase"), spy.calls)
        assertEquals(CleanupResult.Ok(Bytes(11)), port.deleteUploadedRaw())
        assertEquals(listOf("clearLocalDatabase", "deleteUploadedRaw"), spy.calls)
        assertEquals(CleanupResult.Ok(Reclaim(free = Bytes(1), uploadedRaw = Bytes(2), localDatabase = Bytes(3))), port.reclaimable())
        assertEquals(listOf("clearLocalDatabase", "deleteUploadedRaw", "reclaimable"), spy.calls)
    }

    /** The fake core's own numbers: what the uploaded raw files hold is what deleting them frees. */
    @Test fun deletingUploadedRawFreesWhatTheyHold() = runTest {
        val fake = FakeCore(backgroundScope, simulate = false)
        val port = CoreStorageCleanup { CoreHandle.of(fake) }
        fake.freeBytes = 150_000_000
        fake.uploadedRawBytes = 40_000_000
        assertEquals(Bytes(40_000_000), (port.reclaimable() as CleanupResult.Ok).value.uploadedRaw)
        assertEquals(CleanupResult.Ok(Bytes(40_000_000)), port.deleteUploadedRaw())
        assertEquals(Bytes(190_000_000), (port.reclaimable() as CleanupResult.Ok).value.free)
        assertEquals(CleanupResult.Ok(Bytes(0)), port.deleteUploadedRaw())
    }

    /** The fake core for everything else; the three R-10 calls answer distinct values and are recorded. */
    private class Spy(fake: FakeCore) : CoreQueries by fake, CoreStorage by fake, CoreIngest by fake {
        val calls = mutableListOf<String>()
        override suspend fun deleteUploadedRaw(): CoreResult<Long> = CoreResult.Ok(11L).also { calls += "deleteUploadedRaw" }
        override suspend fun clearLocalDatabase(): CoreResult<Long> = CoreResult.Ok(22L).also { calls += "clearLocalDatabase" }
        override suspend fun reclaimable(): CoreResult<Reclaimable> = CoreResult.Ok(Reclaimable(1, 2, 3)).also { calls += "reclaimable" }
    }

    @Test fun anErrKeepsTheCoresReason() {
        val err: CoreResult<Long> = CoreResult.Err(CoreException.Db("disk I/O error"))
        assertEquals(CleanupResult.Err(CleanupError.Failed("local database failed: disk I/O error")), err.toCleanup(::Bytes))
        assertEquals(CleanupResult.Ok(Bytes(5)), CoreResult.Ok(5L).toCleanup(::Bytes))
    }
}
