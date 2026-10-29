package edu.ucsd.sysnet.cluetoothscanner.v2

import kotlinx.coroutines.flow.Flow

/** A size on the data volume (R-10). */
@JvmInline
value class Bytes(val n: Long) : Comparable<Bytes> {
    operator fun plus(o: Bytes) = Bytes(n + o.n)
    override fun compareTo(other: Bytes) = n.compareTo(other.n)
}

/** R-10: the core stops writing below 200 MB free (decimal, as Format.bytes shows it). */
val STORAGE_FLOOR = Bytes(200_000_000)

/** A typed result as lane A's storage calls return it: the error case must be handled to compile. */
sealed interface CleanupResult<out T> {
    data class Ok<T>(val value: T) : CleanupResult<T>
    data class Err(val reason: String) : CleanupResult<Nothing>
}

/** R-10: the free space now and what each cleanup would free. */
data class Reclaim(val free: Bytes, val uploadedRaw: Bytes, val localDatabase: Bytes)

/**
 * R-10, lane A's storage API under the names it is adding to :core-api: `CoreEvent.StorageFull { freeBytes }`,
 * `reclaimable()`, `deleteUploadedRaw()` and `clearLocalDatabase()` as typed results. :app codes against this; until
 * they land, [PlatformPlaceholders] provides it, and then the core does (one file).
 */
interface StorageCleanup {
    /** `CoreEvent.StorageFull { freeBytes }`: the core stopped writing. */
    val storageFull: Flow<Bytes>
    suspend fun reclaimable(): CleanupResult<Reclaim>
    /** Raw files that have already uploaded; never the ones that have not. Ok carries what it freed. */
    suspend fun deleteUploadedRaw(): CleanupResult<Bytes>
    /** The history of every session except the current one; un-uploaded raw files stay. Ok carries what it freed. */
    suspend fun clearLocalDatabase(): CleanupResult<Bytes>
}
