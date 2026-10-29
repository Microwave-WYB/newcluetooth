package edu.ucsd.sysnet.cluetoothscanner.platform.gatt

import java.io.File
import java.io.FileOutputStream

/**
 * One file per discovery under [directory] (`<uuidv7>.json`). Writes go to a temporary file
 * that is synced and renamed into place, so an entry is either absent, the old version or the
 * complete new one, whatever the moment of process death.
 */
class FilePendingDiscoveryStore(private val directory: File) : PendingDiscoveryStore {
    init {
        directory.mkdirs()
    }

    override fun put(id: String, json: String) {
        require(ID.matches(id)) { "not a discovery id: $id" }
        val temporary = File(directory, "$id.json.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!temporary.renameTo(File(directory, "$id.json"))) {
            temporary.delete()
            error("could not store discovery $id")
        }
    }

    // UUIDv7 names sort chronologically.
    override fun entries(): List<PendingDiscovery> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".json") && ID.matches(it.name.removeSuffix(".json")) }
        .sortedBy { it.name }
        .map { PendingDiscovery(it.name.removeSuffix(".json"), it.readText(Charsets.UTF_8)) }

    override fun remove(id: String) {
        File(directory, "$id.json").delete()
    }

    private companion object {
        val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
