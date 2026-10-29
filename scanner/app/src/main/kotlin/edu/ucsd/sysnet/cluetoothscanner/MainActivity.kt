package edu.ucsd.sysnet.cluetoothscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.v2.MainScreen
import edu.ucsd.sysnet.cluetoothscanner.v2.MainViewModel

/** v2 (§1.2.2): the system splash stays until the core is open; then the one adaptive main screen (G-2). */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { vm.ready.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CluetoothTheme { MainScreen(vm) } }
    }
}
