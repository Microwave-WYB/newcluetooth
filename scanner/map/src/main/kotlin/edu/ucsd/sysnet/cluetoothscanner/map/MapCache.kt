package edu.ucsd.sysnet.cluetoothscanner.map

import android.content.Context
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.MapCacheInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.storage.FileSource
import java.io.File

/** O-1 Storage "map cache": the size of MapLibre's ambient cache database (mbgl-offline.db and its journal files). */
class MapLibreCacheInfo(context: Context) : MapCacheInfo {
    private val app = context.applicationContext

    /**
     * The cache's size; 0 when it cannot be read (the frozen `MapCacheInfo` returns a plain Long, so an unreadable
     * cache reads as empty rather than throwing).
     */
    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        try {
            val dir = File(FileSource.getResourcesCachePath(app))
            dir.listFiles { f -> f.isFile && f.name.startsWith(CACHE_DB) }?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        const val CACHE_DB = "mbgl-offline.db"
    }
}
