package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.chrome.Cleanup
import kotlinx.coroutines.flow.Flow

/** A size on the data volume (R-10). */
@JvmInline
value class Bytes(val n: Long) : Comparable<Bytes> {
    operator fun plus(o: Bytes) = Bytes(n + o.n)
    override fun compareTo(other: Bytes) = n.compareTo(other.n)
}

/** R-10: the core stops writing below 200 MB free (decimal, as Format.bytes shows it). */
val STORAGE_FLOOR = Bytes(200_000_000)

/** Why a storage call failed, as :app tells the cases apart (lane A's `CoreException`s map onto these). */
sealed interface CleanupError {
    /** The core does not offer the call (a build or test without it). */
    data object Unavailable : CleanupError
    /** The core failed or refused; [message] is its own. */
    data class Failed(val message: String) : CleanupError
}

/** A typed result as lane A's storage calls return it: the error case must be handled to compile. */
sealed interface CleanupResult<out T> {
    data class Ok<T>(val value: T) : CleanupResult<T>
    data class Err(val error: CleanupError) : CleanupResult<Nothing>
}

/** R-10: what one run of cleanups did: each that succeeded with what it freed, each that failed with why. */
data class CleanupOutcome(val freed: Map<Cleanup, Bytes>, val failed: Map<Cleanup, CleanupError>) {
    /** The total freed, or null when nothing succeeded (then no "Freed" line shows). */
    val freedTotal: Bytes? get() = if (freed.isEmpty()) null else freed.values.fold(Bytes(0), Bytes::plus)
}

/** R-10: the free space now and what each cleanup would free. */
data class Reclaim(val free: Bytes, val uploadedRaw: Bytes, val localDatabase: Bytes)

/**
 * R-10, the core's storage API as the screen uses it: `CoreEvent.StorageFull { freeBytes }`, `reclaimable()`,
 * `deleteUploadedRaw()` and `clearLocalDatabase()` as typed results. [CoreStorageCleanup] is the core's; tests pass
 * their own.
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

/**
 * Runs [cleanups] in a fixed order (raw files first: they never include un-uploaded ones, then the history). Each
 * Err is kept with its cleanup, never counted as freed, and does not stop the next one.
 */
internal suspend fun StorageCleanup.run(cleanups: Set<Cleanup>): CleanupOutcome {
    val freed = mutableMapOf<Cleanup, Bytes>()
    val failed = mutableMapOf<Cleanup, CleanupError>()
    for (c in Cleanup.entries.filter { it in cleanups }) {
        val result = when (c) {
            Cleanup.UploadedRaw -> deleteUploadedRaw()
            Cleanup.LocalDatabase -> clearLocalDatabase()
        }
        when (result) {
            is CleanupResult.Ok -> freed[c] = result.value
            is CleanupResult.Err -> failed[c] = result.error
        }
    }
    return CleanupOutcome(freed.toMap(), failed.toMap())
}
