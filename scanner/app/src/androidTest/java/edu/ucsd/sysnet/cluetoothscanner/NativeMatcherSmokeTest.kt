package edu.ucsd.sysnet.cluetoothscanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.ucsd.sysnet.cluetoothscanner.local.NativeAdvertisementMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in device smoke (see mise task device-smoke): the packaged Rust matcher over the packaged bundle. */
@RunWith(AndroidJUnit4::class)
class NativeMatcherSmokeTest {
    @Test
    fun packagedMatcherIdentifiesAnAdvertisementFromTheBundleAsset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = context.assets.open("wiki-bundle.json").bufferedReader().use { it.readText() }
        val matcher = NativeAdvertisementMatcher.fromBundleJson(json)
        assertEquals(emptyList<String>(), matcher.ruleErrors)

        // flags, then the complete local name "Aranet4"
        val aranet = byteArrayOf(2, 1, 6, 8, 9) + "Aranet4".toByteArray()
        assertTrue(matcher.match(aranet).contains("model--aranet4"))
        assertTrue(matcher.match(byteArrayOf(2, 1, 6)).isEmpty())
    }
}
