package edu.ucsd.sysnet.cluetoothscanner.v2

import edu.ucsd.sysnet.cluetoothscanner.api.core.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R-7 / V-3: the sheets' live flag survives the same scope's view re-read after Stop, and starts unknown per scope. */
class ViewLiveTest {
    private val session = Scope.session("s1")

    /** The sequence MainScreen sees: each step's result is the next step's [last]. */
    private fun run(steps: List<Pair<Scope?, Boolean?>>): List<Boolean?> {
        var last: ViewLive? = null
        return steps.map { (scope, live) -> viewLive(scope, live, last).also { last = it }?.live }
    }

    @Test
    fun stopReSelectsTheSameScope_theLastLiveFlagIsKeptUntilTheReadArrives() {
        assertEquals(
            // launch (not read) → live → Stop's re-select (view re-read: none yet) → the read: frozen
            listOf(null, true, true, false),
            run(listOf(session to null, session to true, session to null, session to false)),
        )
    }

    @Test
    fun anotherScopeStartsUnknown_andNoScopeHasNone() {
        assertEquals(listOf(true, null, false, null), run(listOf(session to true, Scope.All to null, Scope.All to false, null to null)))
        assertNull(viewLive(null, true, ViewLive(session, true)))
    }
}
