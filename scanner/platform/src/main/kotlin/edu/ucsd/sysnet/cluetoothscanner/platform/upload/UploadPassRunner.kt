package edu.ucsd.sysnet.cluetoothscanner.platform.upload

import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Runs one [UploadMachine] pass: the caller's coroutine owns it, executes each [UploadEffect] in order and feeds the
 * answer back as an [UploadEvent]. Every core, store, prefs and file call is a typed result; a file operation that
 * throws is a failed send, never a crash.
 */
internal class UploadPassRunner(
    private val core: UploadCore,
    private val store: ObjectStore,
    private val place: (UploadKind) -> UploadPlace,
    private val io: CoroutineDispatcher,
) {
    suspend fun run(kind: UploadKind): UploadTally {
        val where = place(kind)
        var step = UploadMachine.start(kind)
        val effects = ArrayDeque(step.effects)
        while (true) {
            val effect = effects.removeFirstOrNull() ?: return step.pass.tally
            if (effect is UploadEffect.Finish) return effect.tally
            execute(effect, where)?.let { event ->
                step = UploadMachine.reduce(step.pass, event)
                effects.addAll(step.effects)
            }
        }
    }

    private suspend fun execute(effect: UploadEffect, where: UploadPlace): UploadEvent? = when (effect) {
        UploadEffect.ListPending -> when (val pending = core.pending()) {
            is PlatformResult.Ok -> UploadEvent.Listed(pending.value)
            is PlatformResult.Err -> UploadEvent.ListFailed(pending.error.message)
        }
        is UploadEffect.DiscardOrphans -> {
            files { discardOrphans(where, effect.pending) }
            null
        }
        is UploadEffect.Send -> send(effect.file, effect.mode, where)
        is UploadEffect.CheckStored -> UploadEvent.Checked(effect.file.id, store.exists(effect.file.objectPath) == true)
        is UploadEffect.MarkSucceeded -> when (val marked = core.markSucceeded(effect.id)) {
            is PlatformResult.Ok -> UploadEvent.MarkedSucceeded(effect.id)
            is PlatformResult.Err -> UploadEvent.MarkSucceededFailed(effect.id, marked.error.message)
        }
        is UploadEffect.MarkFailed -> when (val marked = core.markFailed(effect.id, effect.message)) {
            is PlatformResult.Ok -> UploadEvent.MarkedFailed(effect.id)
            is PlatformResult.Err ->
                UploadEvent.MarkedFailed(effect.id, "${effect.id} failed (${effect.message}) and the core could not record it: ${marked.error.message}")
        }
        is UploadEffect.Discard -> {
            files { discard(where, effect.id) }
            null
        }
        is UploadEffect.Finish -> null
    }

    private suspend fun send(file: PendingFile, mode: SendMode, where: UploadPlace): UploadEvent {
        val saved = when (mode) {
            SendMode.ResumeIfSaved -> where.sessions.get(file.id)?.takeIf { files { File(it.cachePath).isFile } == true }
            SendMode.Fresh -> null
        }
        if (saved != null) return put(file, File(saved.cachePath), saved.uri, where)
        val prepared = when (val result = core.prepare(file.id)) {
            is PlatformResult.Ok -> result.value
            is PlatformResult.Err -> return UploadEvent.SendFailed(file.id, SendFailure.Transient(result.error.message))
        }
        if (prepared.id != file.id || prepared.objectPath != file.objectPath) {
            return UploadEvent.SendFailed(file.id, SendFailure.Mismatch("the core prepared ${prepared.id} at ${prepared.objectPath}"))
        }
        // Every prepare re-randomises the ciphertext: the bytes a session started with are kept until it is done.
        val cached = files {
            where.cacheDirectory.mkdirs()
            File(prepared.ciphertextPath).copyTo(File(where.cacheDirectory, "${file.id}.encrypted"), overwrite = true)
        } ?: return UploadEvent.SendFailed(file.id, SendFailure.Transient("the ciphertext could not be cached"))
        where.sessions.remove(file.id)
        return put(file, cached, null, where)
    }

    private suspend fun put(file: PendingFile, bytes: File, sessionUri: String?, where: UploadPlace): UploadEvent =
        when (val result = store.put(file.objectPath, bytes, sessionUri) { uri -> where.sessions.put(file.id, UploadSession(uri, bytes.path)) }) {
            StoreResult.Stored -> UploadEvent.Sent(file.id)
            StoreResult.Expired -> UploadEvent.SendFailed(file.id, SendFailure.Expired)
            is StoreResult.Denied -> UploadEvent.SendFailed(file.id, SendFailure.Denied(result.message))
            is StoreResult.Failed -> UploadEvent.SendFailed(file.id, SendFailure.Transient(result.message))
        }

    private fun discard(where: UploadPlace, id: String) {
        where.sessions.get(id)?.let { File(it.cachePath).delete() }
        File(where.cacheDirectory, "$id.encrypted").delete()
        where.sessions.remove(id)
    }

    private fun discardOrphans(where: UploadPlace, pending: Set<String>) {
        (where.sessions.ids() - pending).forEach { discard(where, it) }
        where.cacheDirectory.listFiles()?.forEach { file -> if (file.name.removeSuffix(".encrypted") !in pending) file.delete() }
    }

    /** A file operation on [io]; a failure is null (the caller decides what it means), never a throw. */
    private suspend fun <T> files(block: () -> T): T? = try {
        withContext(io) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
