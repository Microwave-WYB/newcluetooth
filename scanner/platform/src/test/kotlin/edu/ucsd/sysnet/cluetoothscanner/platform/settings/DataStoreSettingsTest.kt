package edu.ucsd.sysnet.cluetoothscanner.platform.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun cancel() = scope.cancel()

    private fun v3Prefs() = context.getSharedPreferences(DataStoreSettings.V3_PREFS, Context.MODE_PRIVATE)

    @Test
    fun `section 4_5 the v3 prefs migrate once and onboarding still runs`() = runBlocking {
        v3Prefs().edit()
            .putString("user_id", "abc12")
            .putBoolean("wifi_only_upload", true)
            .putBoolean("scan_coded_phy", false)
            .putLong("last_upload_at_ms", 42)
            .commit()
        val store = DataStoreSettings(context, scope, "0.1.0")
        val settings = store.loaded()
        assertEquals("abc12", settings.userId)
        assertTrue(settings.wifiOnlyUpload)
        assertFalse(settings.scanCodedPhy)
        assertTrue("new setting at its default", settings.recordOnOpen)
        assertTrue("G-12 default", settings.hideUnnamedApple)
        assertFalse("not migrated: v3 upgraders see O-2 once", settings.onboardingDone)
        assertEquals("0.1.0", settings.appVersion)
        // Migrated keys leave the v3 file; others stay.
        assertFalse(v3Prefs().contains("user_id"))
        assertEquals(42L, v3Prefs().getLong("last_upload_at_ms", 0))
        assertEquals(settings, store.settings.first { it.userId != null })
    }

    @Test
    fun `a fresh install starts at the defaults`() = runBlocking {
        val settings = DataStoreSettings(context, scope, "0.1.0").loaded()
        assertNull(settings.userId)
        assertTrue(settings.recordOnOpen)
        assertFalse(settings.wifiOnlyUpload)
        assertTrue(settings.scanCodedPhy)
        assertTrue(settings.hideUnnamedApple)
        assertFalse(settings.onboardingDone)
    }

    @Test
    fun `updates persist, including O-2 Done`() = runBlocking {
        val store = DataStoreSettings(context, scope, "0.1.0")
        store.update { it.copy(userId = "u1", recordOnOpen = false, onboardingDone = true, hideUnnamedApple = false) }
        val settings = store.loaded()
        assertEquals("u1", settings.userId)
        assertFalse(settings.recordOnOpen)
        assertTrue(settings.onboardingDone)
        assertFalse(settings.hideUnnamedApple)
        assertEquals(settings, store.settings.first { it.onboardingDone })
    }

    @Test
    fun `an invalid user id is refused and nothing is written`() = runBlocking {
        val store = DataStoreSettings(context, scope, "0.1.0")
        store.update { it.copy(userId = "ok1") }
        val refused = runCatching { store.update { it.copy(userId = "Not Valid", recordOnOpen = false) } }
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        val settings = store.loaded()
        assertEquals("ok1", settings.userId)
        assertTrue(settings.recordOnOpen)
    }

    @Test
    fun `no DataStore type is part of the public API, so callers need no DataStore dependency`() {
        val type = DataStoreSettings::class.java
        // Synthetic accessors (access$...) are compiler plumbing that no caller can name.
        val public = type.constructors.filterNot { it.isSynthetic }.flatMap { it.parameterTypes.toList() } +
            type.methods.filterNot { it.isSynthetic }.flatMap { it.parameterTypes.toList() + it.returnType }
        assertTrue(public.none { it.name.startsWith("androidx.datastore") })
    }
}
