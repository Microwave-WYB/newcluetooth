package edu.ucsd.sysnet.cluetoothscanner.platform.fake

import edu.ucsd.sysnet.cluetoothscanner.api.core.GattFailure
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepKind
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattStepState
import edu.ucsd.sysnet.cluetoothscanner.api.core.GattView
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformError
import edu.ucsd.sysnet.cluetoothscanner.api.platform.GattRunner
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.Action
import edu.ucsd.sysnet.cluetoothscanner.api.platform.LocationStatus.State
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Perm
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PermissionState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.Permissions
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState
import edu.ucsd.sysnet.cluetoothscanner.api.platform.RecorderState.Phase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakesTest {
    private val devices = mapOf(
        1L to FakeGattDevice("JBL Flip 6", connectable = true, companyName = "Harman International"),
        2L to FakeGattDevice(null, connectable = false),
        3L to FakeGattDevice("ELK-BLEDOM", connectable = true),
    )

    private fun runner(available: Boolean = true, finished: MutableList<GattView> = mutableListOf()) =
        FakeGattRunner(devices::get, available = { available }, stepMs = 10, nowMs = { 1_000 }, onFinished = { finished += it })

    @Test
    fun `D-13 a readable device runs every step and ends with its services`() = runTest {
        val finished = mutableListOf<GattView>()
        val views = runner(finished = finished).discover(1).toList()
        val last = views.last()
        assertTrue(last.ok)
        assertEquals(GattStepKind.entries, last.steps.map { it.kind })
        assertTrue(last.steps.all { it.state == GattStepState.Done })
        assertEquals(800, last.steps[0].durationMs)
        assertEquals(3, last.steps[1].services)
        assertEquals(4, last.steps[2].read)
        assertEquals(4, last.steps[2].readable)
        assertEquals("JBL Flip 6", last.services[0].characteristics[0].valueText)
        assertEquals(listOf(last), finished)
        // Progress: each step shows running before it is done; reading shows m/n first.
        val running = views.dropLast(1).map { it.steps.last() }
        assertTrue(running.all { it.state == GattStepState.Running })
        assertEquals(3, running.first { it.kind == GattStepKind.ReadingCharacteristics }.read)
    }

    @Test
    fun `D-13 a non-connectable device times out while connecting`() = runTest {
        val last = runner().discover(2).toList().last()
        assertFalse(last.ok)
        assertEquals(1, last.steps.size)
        assertEquals(GattStepState.Failed, last.steps[0].state)
        assertEquals(GattFailure.TimedOut, last.steps[0].failure)
        assertTrue(last.services.isEmpty())
    }

    @Test
    fun `D-13 ELK-BLEDOM disconnects while reading`() = runTest {
        val last = runner().discover(3).toList().last()
        assertFalse(last.ok)
        assertEquals(
            listOf(GattStepKind.Connecting, GattStepKind.DiscoveringServices, GattStepKind.ReadingCharacteristics),
            last.steps.map { it.kind },
        )
        assertEquals(GattFailure.DisconnectedUnexpectedly, last.steps.last().failure)
    }

    @Test
    fun `D-13 preconditions fail the Connecting step with the named codes`() = runTest {
        val unavailable = runner(available = false).discover(1).toList()
        assertEquals(1, unavailable.size)
        assertEquals(GattFailure.Other(GattRunner.GATT_UNAVAILABLE), unavailable.single().steps.single().failure)
        val noTarget = runner().discover(99).toList()
        assertEquals(GattStepKind.Connecting, noTarget.single().steps.single().kind)
        assertEquals(GattFailure.Other(GattRunner.GATT_NO_TARGET), noTarget.single().steps.single().failure)
    }

    @Test
    fun `D-13_8 a new discovery cancels the running one`() = runTest {
        val gatt = runner()
        val first = mutableListOf<GattView>()
        val firstJob = launch { gatt.discover(1).collect { first += it } }
        runCurrent()
        val second = launch { gatt.discover(1).collect { } }
        runCurrent()
        assertTrue("the earlier run ends; its collector is not cancelled", firstJob.isCompleted)
        assertFalse(firstJob.isCancelled)
        assertFalse(first.last().ok)
        advanceTimeBy(1_000)
        assertTrue(second.isCompleted)
        assertFalse(second.isCancelled)
    }

    @Test
    fun `recorder excludes paused time and keeps the stopped session`() = runTest {
        var now = 0L
        var stops = 0
        val recorder = FakeRecorder(onStart = { "s1" }, onStop = { stops++; "s1" }, nowMs = { now })
        assertEquals(PlatformResult.Ok("s1"), recorder.start())
        assertEquals(Phase.Recording, recorder.state.value.phase)
        now = 10_000
        recorder.pause()
        assertEquals(Phase.Paused, recorder.state.value.phase)
        assertEquals(10_000, recorder.state.value.activeBeforeMs)
        assertNull(recorder.state.value.runningSinceMs)
        now = 70_000
        recorder.resume()
        assertEquals(70_000L, recorder.state.value.runningSinceMs)
        assertEquals(PlatformResult.Ok("s1"), recorder.stop())
        assertEquals(1, stops)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertEquals("s1", recorder.state.value.sessionId)
    }

    @Test
    fun `R-10 the recorder shows a storage-full pause until resumed`() = runTest {
        val recorder = FakeRecorder(onStart = { "s1" }, onStop = { "s1" }, nowMs = { 0 })
        recorder.start()
        recorder.storageFull(150_000_000)
        assertEquals(Phase.Paused, recorder.state.value.phase)
        assertEquals(RecorderState.PauseReason.StorageFull(150_000_000), recorder.state.value.pause)
        recorder.resume()
        assertEquals(Phase.Recording, recorder.state.value.phase)
        assertNull(recorder.state.value.pause)
        recorder.storageFailed("commit failed")
        assertEquals(RecorderState.PauseReason.StorageFailed("commit failed"), recorder.state.value.pause)
    }

    @Test
    fun `recorder with a missing grant stays idle with an error and starts no session`() = runTest {
        var starts = 0
        val recorder = FakeRecorder(onStart = { starts++; "s1" }, onStop = { "s1" }, grantsOk = { false })
        val refused = recorder.start()
        assertTrue(refused is PlatformResult.Err && refused.error is PlatformError.MissingGrants)
        assertEquals(0, starts)
        assertEquals(Phase.Idle, recorder.state.value.phase)
        assertNotNull(recorder.state.value.error)
    }

    @Test
    fun `P-5 location demo states match data_js`() {
        val status = FakeLocationStatus()
        assertEquals(State.Ok, status.status.value.state)
        assertNotNull(status.myLocation.value)
        status.set(LocationDemo.Denied)
        assertEquals(Action.Allow, status.status.value.action)
        assertTrue(status.status.value.warn)
        status.set(LocationDemo.Off)
        assertEquals(Action.TurnOn, status.status.value.action)
        status.set(LocationDemo.Waiting)
        assertTrue("45 s without a fix is past 30 s", status.status.value.warn)
        assertNull(status.myLocation.value)
        status.set(LocationDemo.Stale)
        assertEquals(5, status.status.value.ageMin)
        assertTrue(status.status.value.warn)
    }

    @Test
    fun `O-2 permission requests grant only the asked group`() {
        val permissions = FakePermissions(PermissionState(Perm.Denied, Perm.Denied, Perm.Denied, bluetoothOn = false))
        permissions.grant(Permissions.nearby(31))
        assertEquals(Perm.Granted, permissions.state.value.nearby)
        assertEquals(Perm.Denied, permissions.state.value.location)
        permissions.grant(emptyArray())
        assertEquals(Perm.Granted, permissions.state.value.location)
        assertEquals(Perm.Granted, permissions.state.value.notifications)
    }

    @Test
    fun `settings update and upload now`() = runTest {
        val settings = FakeSettings()
        assertEquals(PlatformResult.Ok(Unit), settings.update { it.copy(onboardingDone = true, userId = "abc") })
        assertTrue(settings.settings.value.onboardingDone)
        assertEquals(
            "an invalid user id is a typed refusal, nothing changes",
            PlatformResult.Err(PlatformError.InvalidUserId("Bad_Id")),
            settings.update { it.copy(userId = "Bad_Id") },
        )
        assertEquals("abc", settings.settings.value.userId)
        val uploads = FakeUploads()
        uploads.uploadNow()
        assertEquals(0, uploads.status.value.pendingCount)
        assertEquals(1, uploads.uploadNowCalls)
    }
}
