package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import edu.ucsd.sysnet.cluetoothscanner.api.core.UploadStatus
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * docs/v2-api.md §1.5 "The pre-0.0.5 JSONL uploader is ported unchanged": the Zstd JSONL files producers 0.0.1–0.0.4
 * left in `filesDir`, as an [UploadCore], so the same pass reducer and runner upload them (one at a time with the
 * scans, resumable, overwrite-safe). v3's rules, unchanged:
 *
 * - pending is every `<name>_0.0.[1-4][suffix].jsonl.zst`; the object is `scans/<file name>.encrypted`;
 * - any other `*.jsonl.zst` moves to `quarantine/legacy/` (kept, never uploaded, never deleted);
 * - the upload is the file's bytes in a sealed box to the recipient key ([seal]);
 * - an uploaded file is renamed `<name>.jsonl.uploaded` (kept, as v3 did); a failed one stays pending.
 *
 * Nothing here deletes data: only the sealed copy this class wrote is removed after its upload ended.
 */
internal class LegacyJsonlCore(
    private val directory: File,
    private val quarantine: File,
    /** Where the sealed copy of a file is written for the runner (it copies it into its own cache). */
    private val sealed: File,
    private val seal: (ByteArray) -> ByteArray,
    private val io: CoroutineDispatcher,
) : UploadCore {
    override suspend fun status(): PlatformResult<UploadStatus> = files {
        val waiting = candidates().filter { legacyObjectPath(it.name) != null }
        UploadStatus(waiting.size, waiting.sumOf { it.length() }, 0, null)
    }

    override suspend fun pending(): PlatformResult<List<PendingFile>> = files {
        candidates().mapNotNull { file ->
            val objectPath = legacyObjectPath(file.name)
            if (objectPath == null) {
                quarantine.mkdirs()
                file.renameTo(File(quarantine, file.name))
                null
            } else {
                PendingFile(file.name, objectPath)
            }
        }
    }

    override suspend fun prepare(id: String): PlatformResult<Prepared> = files {
        val objectPath = legacyObjectPath(id) ?: throw IOException("$id is not a legacy pending file")
        sealed.mkdirs()
        val out = File(sealed, "$id.encrypted")
        out.writeBytes(seal(File(directory, id).readBytes()))
        Prepared(id, out.path, objectPath)
    }

    /** v3's marker: `<name>.jsonl.zst` becomes `<name>.jsonl.uploaded`; the file itself is kept. */
    override suspend fun markSucceeded(id: String): PlatformResult<Unit> = files {
        val file = File(directory, id)
        if (!file.renameTo(File(directory, "${file.nameWithoutExtension}.uploaded"))) throw IOException("$id could not be marked uploaded")
        File(sealed, "$id.encrypted").delete()
        Unit
    }

    /** Stays pending for the next pass; only the sealed copy goes. */
    override suspend fun markFailed(id: String, message: String): PlatformResult<Unit> = files {
        File(sealed, "$id.encrypted").delete()
        Unit
    }

    private fun candidates(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }?.sortedBy { it.name }.orEmpty()

    private suspend fun <T> files(block: () -> T): PlatformResult<T> = try {
        PlatformResult.Ok(withContext(io) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PlatformResult.Err(PlatformError.Io(failure.message ?: failure::class.java.simpleName))
    }

    companion object {
        private const val SUFFIX = ".jsonl.zst"
        private val PATTERN = Regex("^[^/]+_0\\.0\\.[1-4](?:[-+][A-Za-z0-9_.-]+)?\\.jsonl\\.zst$")

        /** v3's `legacyRemoteObjectName`, under `scans/`: the producer's name, stable across retries; else null. */
        fun legacyObjectPath(fileName: String): String? =
            if (PATTERN.matches(fileName) && fileName.removeSuffix(SUFFIX).isNotBlank()) "scans/$fileName.encrypted" else null
    }
}

/** libsodium's sealed box to the 32-byte recipient key, as v3's `EncryptionUtils.encryptData`. Throws only on failure. */
internal class SodiumSeal(private val key: () -> ByteArray) : (ByteArray) -> ByteArray {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    override fun invoke(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + SEAL_OVERHEAD)
        if (!sodium.cryptoBoxSeal(out, data, data.size.toLong(), key())) throw IOException("the sealed box failed")
        return out
    }

    private companion object {
        /** crypto_box_SEALBYTES: the ephemeral public key and the MAC. */
        const val SEAL_OVERHEAD = 48
    }
}
