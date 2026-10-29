package edu.ucsd.sysnet.cluetoothscanner

import android.app.Application
import edu.ucsd.sysnet.cluetoothscanner.v2.AppGraph

/** v2 (§1.2): only starts the lazily built [AppGraph]; the core opens on IO. */
class CluetoothApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.start()
    }
}
