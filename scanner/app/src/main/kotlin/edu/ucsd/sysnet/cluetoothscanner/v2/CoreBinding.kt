package edu.ucsd.sysnet.cluetoothscanner.v2

import android.content.Context
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreIngest
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreStorage
import edu.ucsd.sysnet.cluetoothscanner.fake.core.FakeCore
import kotlinx.coroutines.CoroutineScope

/** The opened core: the three :core-api faces of one `Core` handle (§1.1, §5.1). */
class CoreHandle(val queries: CoreQueries, val storage: CoreStorage, val ingest: CoreIngest)

/**
 * THE core seam (temporary, integration step 1). This is the only file of :app that names a core implementation;
 * swapping :core-fake for :core-bridge changes the one line in [open]. It runs on Dispatchers.IO (§1.2.2).
 */
internal object CoreBinding {
    fun open(context: Context, scope: CoroutineScope): CoreHandle = fake(scope)

    /** :core-fake on the wall clock (its default demo clock would skew the recorder's locally ticked time). */
    private fun fake(scope: CoroutineScope): CoreHandle =
        FakeCore(scope, clock = System::currentTimeMillis).let { CoreHandle(it, it, it) }
}
