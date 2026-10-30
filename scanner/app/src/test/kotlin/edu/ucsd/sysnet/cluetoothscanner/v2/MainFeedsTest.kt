package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.AllSessionsSummary
import edu.ucsd.sysnet.cluetoothscanner.api.core.CoreException
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import edu.ucsd.sysnet.cluetoothscanner.chrome.DiscoveryBins
import edu.ucsd.sysnet.cluetoothscanner.chrome.SessionsListState
import edu.ucsd.sysnet.cluetoothscanner.ui.contract.OrderedKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The reads enter the state only as result events through the reducer, each tagged with the request it answers: the
 * session view (its [ViewKey]), the search results (their [SearchKey]), the sessions list (its request number) and
 * the discovery rate (its [DiscoveryKey]). Every arrival order of a stale and a fresh result ends on the fresh one.
 */
class MainFeedsTest {
    /** Every order of [events]. */
    private fun <T> orders(events: List<T>): List<List<T>> =
        if (events.size <= 1) listOf(events)
        else events.indices.flatMap { i -> orders(events.filterIndexed { j, _ -> j != i }).map { listOf(events[i]) + it } }

    private fun list(n: Int) = SessionsListState(null, AllSessionsSummary(n, n, null), emptyList(), selected = null)
    private fun bins(n: Int) = DiscoveryBins(DiscoveryBins.BIN_MS, listOf(DiscoveryBins.Bin(0, n)), emptyList())

    @Test fun theViewOfAnEarlierSelectionNeverShows() {
        val first = MainUiState().on(MainEvent.SelectScope(sessionA))
        val now = first.on(MainEvent.SelectScope(sessionB))
        val stale = MainEvent.ViewRead(ViewData(first.viewKey, view(sessionA)))
        val fresh = MainEvent.ViewRead(ViewData(now.viewKey, view(sessionB)))
        for (order in orders(listOf(stale, fresh))) {
            val s = now.on(*order.toTypedArray())
            assertEquals("$order", view(sessionB), shownView(s))
            assertEquals("$order", fresh.data, s.viewData)
        }
        // alone, the stale read changes nothing
        assertEquals(Step(now), reduce(now, stale))
        // V-3: re-selecting the same scope makes a new key, so the earlier read of that scope is stale too
        val reselected = first.on(MainEvent.SelectScope(sessionA))
        assertNull(shownView(reselected.on(MainEvent.ViewRead(ViewData(first.viewKey, view(sessionA))))))
    }

    @Test fun anOldQuerysResultsNeverReplaceTheNewOnes() {
        val tile = MainUiState(scope = sessionA).on(MainEvent.OpenSearch, MainEvent.SetQuery("tile"))
        val tiles = tile.on(MainEvent.SetQuery("tiles"))
        fun read(s: MainUiState, vararg keys: Long) =
            MainEvent.SearchRead(SearchResults(s.searchKey ?: error("the page is open"), OrderedKeys(keys), emptyList()))
        val stale = read(tile, 1)
        val fresh = read(tiles, 2)
        for (order in orders(listOf(stale, fresh))) {
            val s = tiles.on(*order.toTypedArray())
            assertEquals("$order", fresh.results, s.searchResults)
            assertSame("$order", fresh.results.order, shownSearch(s)?.first?.results)
        }
        // a fresh result, then the user types on: it stays until the next query's results come, and a late one for
        // the query before it is dropped
        val shownBefore = read(tile, 1)
        val typedOn = tile.on(shownBefore, MainEvent.SetQuery("tiles"))
        assertSame(shownBefore.results.order, shownSearch(typedOn)?.first?.results)
        assertEquals(Step(typedOn), reduce(typedOn, read(tile, 3)))
    }

    @Test fun anOlderSessionsReadNeverReplacesANewerOne() {
        // two requests: a scope selection, then the core's SessionsChanged
        val asked = MainUiState().on(MainEvent.SelectScope(sessionA), MainEvent.CoreSessionsChanged)
        assertEquals(2, asked.sessionsAsked)
        val older = MainEvent.SessionsRead(1, list(1))
        val newer = MainEvent.SessionsRead(2, list(2))
        for (order in orders(listOf(older, newer))) {
            assertEquals("$order", SessionsData(2, list(2)), asked.on(*order.toTypedArray()).sessionsData)
        }
        // the core's events ask with the next numbers; each read answers with its own
        assertEquals(listOf(MainEffect.LoadSessions(3)), reduce(asked, MainEvent.CoreSessionsChanged).effects)
        assertEquals(listOf(MainEffect.LoadSessions(3)), reduce(asked, MainEvent.CoreDataInvalidated).effects)
    }

    @Test fun aPreviousReadOfTheRateNeverReplacesTheCurrentOne() {
        val recording = MainUiState().on(MainEvent.RecorderChanged(Phase.Recording, sessionId = "a"))
        val first = recording.discoveryKey ?: error("recording")
        // DataInvalidated (the display filter changed which devices count): the same session is read again
        val reread = recording.on(MainEvent.DiscoveryRead(first, bins(5)), MainEvent.CoreDataInvalidated)
        val second = reread.discoveryKey ?: error("recording")
        assertEquals(DiscoveryKey("a", first.request + 1), second)
        assertEquals("the shown rate stays while it is read again", bins(5), reread.discovery)
        val stale = MainEvent.DiscoveryRead(first, bins(7))
        val fresh = MainEvent.DiscoveryRead(second, bins(4))
        for (order in orders(listOf(stale, fresh))) {
            assertEquals("$order", bins(4), reread.on(*order.toTypedArray()).discovery)
        }
        // each read's deltas apply in order, and a later one of the stale read is dropped too
        assertEquals(bins(6), reread.on(fresh, MainEvent.DiscoveryRead(second, bins(6)), stale).discovery)
    }

    @Test fun aNewSessionStartsEmptyAndThePreviousOnesRateIsDropped() {
        val a = MainUiState().on(MainEvent.RecorderChanged(Phase.Recording, sessionId = "a"))
        val keyA = a.discoveryKey ?: error("recording")
        val shown = a.on(MainEvent.DiscoveryRead(keyA, bins(3)))
        // the same session again (a pause, a resume) asks for nothing new
        assertEquals(shown, shown.on(MainEvent.RecorderChanged(Phase.Paused, sessionId = "a"), MainEvent.RecorderChanged(Phase.Recording, sessionId = "a")))
        // Stop: no read, no rate; a late result of session a is dropped
        val stopped = shown.on(MainEvent.RecorderChanged(Phase.Idle))
        assertNull(stopped.discoveryKey)
        assertEquals(DiscoveryBins.Empty, stopped.discovery)
        assertEquals(DiscoveryBins.Empty, stopped.on(MainEvent.DiscoveryRead(keyA, bins(9))).discovery)
        // nothing is read again while not recording
        assertEquals(stopped.discoveryKey, stopped.on(MainEvent.CoreDataInvalidated, MainEvent.RereadDiscovery).discoveryKey)
        // session b: a new key, empty until its own read
        val b = stopped.on(MainEvent.RecorderChanged(Phase.Recording, sessionId = "b"))
        val keyB = b.discoveryKey ?: error("recording")
        assertEquals("b", keyB.sessionId)
        for (order in orders(listOf(MainEvent.DiscoveryRead(keyA, bins(9)), MainEvent.DiscoveryRead(keyB, bins(2))))) {
            assertEquals("$order", bins(2), b.on(*order.toTypedArray()).discovery)
        }
    }

    /** A failed read says why only while it is the read wanted; a superseded one's failure is dropped like its data. */
    @Test fun onlyTheCurrentReadsFailureShows() {
        val recording = MainUiState().on(MainEvent.RecorderChanged(Phase.Recording, sessionId = "a"))
        val first = recording.discoveryKey ?: error("recording")
        val reread = recording.on(MainEvent.RereadDiscovery)
        val second = reread.discoveryKey ?: error("recording")
        val err = CoreException.Db("locked")
        assertNull(reread.on(MainEvent.Failed(MainTask.DiscoveryRate(first), err)).notice)
        assertEquals(
            Notice("Could not read the discovery rate: ${err.message}", retry = MainEffect.ReloadDiscoveryRate),
            reread.on(MainEvent.Failed(MainTask.DiscoveryRate(second), err)).notice,
        )
    }
}
