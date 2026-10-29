package edu.ucsd.sysnet.cluetoothscanner.platform.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import edu.ucsd.sysnet.cluetoothscanner.api.core.PreparedExport
import edu.ucsd.sysnet.cluetoothscanner.api.platform.ExportShare
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.platform.failureHandler
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The `:platform` FileProvider for export ZIPs; its own class, so it never collides with a host app's provider. */
class ExportFileProvider : FileProvider()

/**
 * Where [AndroidExportShare.saveToDownloads] puts a ZIP; returns what it was saved as (a name or a URI). An I/O edge:
 * it may throw, and the runner turns that into [PlatformError.Io].
 */
fun interface DownloadsWriter {
    fun write(displayName: String, mimeType: String, source: File): String
}

/**
 * SPEC O-1 item 10 and docs/v2-api.md §4.5: hands a ZIP the core prepared (`CoreStorage.prepareExport` for the
 * scope and format the user chose in Settings: Parquet, CSV or SQLite) to the system share sheet or to Downloads.
 *
 * When each export is acknowledged is [ExportLedger]'s decision; this class is its runner. One actor in [scope] owns
 * the ledger: [share], [saveToDownloads] and [acknowledgeStale] send it events, and it runs the acknowledgements and
 * the prefs writes. Every Android, core and I/O call is guarded: a failure is a [PlatformResult.Err] or a ledger
 * event, never a throw.
 */
class AndroidExportShare(
    private val context: Context,
    /** `CoreStorage.acknowledgeExport`. */
    private val acknowledge: suspend (String) -> Unit,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val downloads: DownloadsWriter = defaultDownloads(context),
) : ExportShare {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val inbox = Channel<LedgerEvent>(Channel.UNLIMITED)

    init {
        inbox.trySend(LedgerEvent.Loaded(guarded("reading the pending exports") { prefs.getStringSet(KEY_PENDING, null)?.toSet() } ?: emptySet()))
        scope.launch(failureHandler(TAG)) { actor() }
    }

    /** The share sheet; the export stays pending until the next export or app start (the receiver may still read it). */
    override fun share(export: PreparedExport): PlatformResult<Unit> {
        inbox.trySend(LedgerEvent.Handed(export.exportId))
        return try {
            val uri = FileProvider.getUriForFile(context, authority(context), File(export.localPath))
            val send = Intent(Intent.ACTION_SEND)
                .setType(ZIP)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, export.suggestedFileName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(export.suggestedFileName, uri)
            context.startActivity(
                Intent.createChooser(send, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            PlatformResult.Ok(Unit)
        } catch (failure: RuntimeException) {
            // No share sheet on this device, or the ZIP is outside the provider's roots.
            PlatformResult.Err(PlatformError.Unavailable(failure.describe()))
        }
    }

    /**
     * Copies the ZIP into Downloads under its suggested name, then it is acknowledged. On API 24–28 this needs
     * `WRITE_EXTERNAL_STORAGE`; without it (or on any I/O failure) the result is [PlatformError.Io] and the export
     * stays pending, so the user can try again and it is still acknowledged at the next export or app start.
     */
    override suspend fun saveToDownloads(export: PreparedExport): PlatformResult<Unit> {
        inbox.trySend(LedgerEvent.Handed(export.exportId))
        val copied = try {
            withContext(io) { downloads.write(export.suggestedFileName, ZIP, File(export.localPath)) }
            PlatformResult.Ok(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            PlatformResult.Err(PlatformError.Io(failure.describe()))
        }
        if (copied is PlatformResult.Ok) inbox.trySend(LedgerEvent.Saved(export.exportId))
        return copied
    }

    /** App start, once the core is open: acknowledges what an earlier process shared or failed to acknowledge. */
    fun acknowledgeStale() {
        inbox.trySend(LedgerEvent.AppStarted)
    }

    private suspend fun actor() {
        var ledger = Ledger()
        val pending = ArrayDeque<LedgerEvent>()
        for (event in inbox) {
            pending += event
            while (pending.isNotEmpty()) {
                val step = ExportLedger.reduce(ledger, pending.removeFirst())
                ledger = step.state
                for (effect in step.effects) {
                    when (effect) {
                        is LedgerEffect.Remember ->
                            guarded("remembering the pending exports") { prefs.edit().putStringSet(KEY_PENDING, HashSet(effect.pending)).apply() }
                        is LedgerEffect.Acknowledge -> pending += acknowledged(effect.exportId)
                    }
                }
            }
        }
    }

    private suspend fun acknowledged(exportId: String): LedgerEvent = try {
        acknowledge(exportId)
        LedgerEvent.Acknowledged(exportId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        log("acknowledging $exportId failed; it is tried again later", failure)
        LedgerEvent.AckFailed(exportId)
    }

    /** A prefs call: a failure is logged and gives null (nothing pending read, or not remembered this time). */
    private fun <T> guarded(what: String, call: () -> T): T? = try {
        call()
    } catch (failure: Exception) {
        log("$what failed", failure)
        null
    }

    private fun log(message: String, failure: Throwable) {
        runCatching { Log.w(TAG, message, failure) }
    }

    private fun Throwable.describe() = message ?: this::class.java.simpleName

    companion object {
        const val ZIP = "application/zip"
        private const val TAG = "AndroidExportShare"
        private const val PREFS = "cluetooth_exports"
        private const val KEY_PENDING = "pending_exports"

        fun authority(context: Context) = "${context.packageName}.cluetooth.exports"

        fun defaultDownloads(context: Context): DownloadsWriter =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStoreDownloads(context) else LegacyDownloads(context)
    }
}

/** API 29+: MediaStore Downloads, no permission; pending until the copy is complete. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreDownloads(private val context: Context) : DownloadsWriter {
    override fun write(displayName: String, mimeType: String, source: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Downloads refused $displayName")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("no stream for $uri")).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return uri.toString()
    }
}

/** API 24–28: the public Downloads directory (needs `WRITE_EXTERNAL_STORAGE`); never overwrites an existing file. */
class LegacyDownloads(
    private val context: Context,
    private val directory: () -> File = { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) },
) : DownloadsWriter {
    override fun write(displayName: String, mimeType: String, source: File): String {
        val dir = directory().apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        source.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType), null)
        return target.name
    }

    companion object {
        /** `name.zip`, else `name (1).zip`, `name (2).zip`, … */
        fun uniqueFile(dir: File, name: String): File {
            val first = File(dir, name)
            if (!first.exists()) return first
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            return generateSequence(1) { it + 1 }.map { File(dir, "$base ($it)$ext") }.first { !it.exists() }
        }
    }
}
