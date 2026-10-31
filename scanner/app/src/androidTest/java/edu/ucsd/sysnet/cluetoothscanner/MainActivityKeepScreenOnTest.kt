package edu.ucsd.sysnet.cluetoothscanner

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** G-15: while the v2 activity is in the foreground the screen never turns off by itself. */
@RunWith(AndroidJUnit4::class)
class MainActivityKeepScreenOnTest {
    @Test
    fun theWindowKeepsTheScreenOn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue("FLAG_KEEP_SCREEN_ON is set", flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            }
        }
    }
}
