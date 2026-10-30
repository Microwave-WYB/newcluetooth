package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * R-10's [StorageCleanup] on the core (#71): `CoreEvent.StorageFull` and `CoreStorage.reclaimable()`,
 * `deleteUploadedRaw()` and `clearLocalDatabase()`, with each `CoreResult.Err` kept as a typed [CleanupError].
 * [core] suspends until the core is open (§1.2.2), so nothing here runs before it.
 */
internal class CoreStorageCleanup(private val core: suspend () -> CoreHandle) : StorageCleanup {
    override val storageFull: Flow<Bytes> = flow {
        emitAll(core().queries.events.filterIsInstance<CoreEvent.StorageFull>().map { Bytes(it.freeBytes) })
    }

    override suspend fun reclaimable(): CleanupResult<Reclaim> =
        core().storage.reclaimable().toCleanup { Reclaim(Bytes(it.freeBytes), Bytes(it.uploadedRawBytes), Bytes(it.localDatabaseBytes)) }

    override suspend fun deleteUploadedRaw(): CleanupResult<Bytes> = core().storage.deleteUploadedRaw().toCleanup(::Bytes)

    override suspend fun clearLocalDatabase(): CleanupResult<Bytes> = core().storage.clearLocalDatabase().toCleanup(::Bytes)
}

/** A core result as the port's: Ok mapped, Err kept with the core's own reason. */
internal fun <T, R> CoreResult<T>.toCleanup(ok: (T) -> R): CleanupResult<R> = when (this) {
    is CoreResult.Ok -> CleanupResult.Ok(ok(value))
    is CoreResult.Err -> CleanupResult.Err(cleanupError(error))
}

/** The reason the dialog shows after "Couldn't …:"; the exception's message is the core's own. */
internal fun cleanupError(e: CoreException): CleanupError = CleanupError.Failed(e.message ?: e::class.simpleName.orEmpty())
