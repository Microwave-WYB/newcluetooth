package edu.ucsd.sysnet.cluetoothscanner.map

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.Bbox
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryCount
import edu.ucsd.sysnet.cluetoothscanner.api.core.CategoryGroup
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreEvent
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreQueries
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceFilter
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKey
import edu.ucsd.sysnet.cluetoothscanner.api.core.DeviceKeys
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.core.LiveChart
import edu.ucsd.sysnet.cluetoothscanner.api.core.ProductDetail
import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import edu.ucsd.sysnet.cluetoothscanner.api.core.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** The CoreQueries calls :map never makes; a test fake overrides only what the map uses. */
abstract class UnusedCoreQueries : CoreQueries {
    private fun unused(): Nothing = throw UnsupportedOperationException(":map does not call this")
    override val events: Flow<CoreEvent> = emptyFlow()
    override suspend fun sessions(): List<SessionSummary> = unused()
    override suspend fun currentSession(): SessionSummary? = unused()
    override suspend fun allSessionsSummary(): AllSessionsSummary = unused()
    override suspend fun deviceOrder(scope: Scope): DeviceKeys = unused()
    override suspend fun deviceKeys(scope: Scope, filter: DeviceFilter): DeviceKeys = unused()
    override suspend fun categoryCounts(scope: Scope, filter: DeviceFilter): List<CategoryCount> = unused()
    override suspend fun products(scope: Scope, filter: DeviceFilter): List<CategoryGroup> = unused()
    override suspend fun product(scope: Scope, pageId: String?, filter: DeviceFilter): ProductDetail? = unused()
    override suspend fun device(scope: Scope, key: DeviceKey): DeviceDetail? = unused()
    override suspend fun deviceChart(key: DeviceKey): LiveChart? = unused()
    override suspend fun deviceGatt(key: DeviceKey): GattView? = unused()
    override suspend fun search(scope: Scope, query: String, area: Bbox?): DeviceKeys? = unused()
    override fun matchDevice(query: String, name: String?, addr: String): Boolean = unused()
    override suspend fun setLiveFocus(key: DeviceKey?) = unused()
}
