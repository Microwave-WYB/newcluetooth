package edu.ucsd.sysnet.cluetoothscanner.map

import android.content.Context
import edu.ucsd.sysnet.cluetoothscanner.map.stub.MapCacheInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.storage.FileSource
import java.io.File

/** O-1 Storage "map cache": the size of MapLibre's ambient cache database (mbgl-offline.db and its journal files). */
class MapLibreCacheInfo(context: Context) : MapCacheInfo {
    private val app = context.applicationContext

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        val dir = File(FileSource.getResourcesCachePath(app))
        dir.listFiles { f -> f.isFile && f.name.startsWith(CACHE_DB) }?.sumOf { it.length() } ?: 0L
    }

    private companion object {
        const val CACHE_DB = "mbgl-offline.db"
    }
}
