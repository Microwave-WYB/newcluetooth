package edu.ucsd.sysnet.cluetoothscanner.v2

import android.content.Context
import android.util.Base64
import edu.ucsd.sysnet.cluetoothscanner.R
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreAssets
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreConfig
import edu.ucsd.sysnet.cluetoothscanner.corebridge.CoreBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * The opened core (§1.1, §5.1): ONE instance seen through its three :core-api faces. The UI uses [queries] and
 * [storage], :platform uses [ingest]. `CoreBridge` (lane A, #59) and `FakeCore` both implement all three.
 */
class CoreHandle private constructor(val queries: CoreQueries, val storage: CoreStorage, val ingest: CoreIngest) {
    companion object {
        fun <T> of(core: T): CoreHandle where T : CoreQueries, T : CoreStorage, T : CoreIngest = CoreHandle(core, core, core)
    }
}

/** What `CoreBridge.open(CoreConfig, CoreAssets)` takes, read the way v3 did (same directory, same key). */
internal class CoreInputs(context: Context) {
    /** filesDir/cluetooth-core, the v3 directory, so the in-place upgrade keeps the upload queue (§1.5). */
    val dataDirectory: String = File(context.filesDir, "cluetooth-core").path
    /** The 32-byte upload recipient key; v3's resource is the same bytes as the Rust DEFAULT_RECIPIENT_PUBLIC_KEY. */
    val recipientPublicKey: ByteArray = Base64.decode(context.getString(R.string.encryption_public_key), Base64.DEFAULT)
    val wikiBundleJson: String by lazy { context.assets.open("wiki-bundle.json").bufferedReader().use { it.readText() } }
    val sigNumbersTsv: String by lazy { context.assets.open("sig-numbers.tsv").bufferedReader().use { it.readText() } }
}

/**
 * THE core seam: the only file of :app that names a core implementation (the real one, :core-bridge, #59). It runs
 * on Dispatchers.IO and is called once per process (§1.2.2); the handle is never closed in normal life.
 */
internal object CoreBinding {
    @Suppress("UNUSED_PARAMETER")
    fun open(context: Context, scope: CoroutineScope): CoreHandle = CoreInputs(context).let { i ->
        CoreHandle.of(CoreBridge.open(CoreConfig(i.dataDirectory, i.recipientPublicKey), CoreAssets(i.wikiBundleJson, i.sigNumbersTsv)))
    }
}
