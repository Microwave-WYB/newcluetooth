package edu.ucsd.sysnet.cluetoothscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.v2.CoreState
import edu.ucsd.sysnet.cluetoothscanner.v2.MainScreen
import edu.ucsd.sysnet.cluetoothscanner.v2.MainViewModel
import kotlinx.coroutines.launch

/**
 * v2 (§1.2.2): the system splash stays until the core is open; then the one adaptive main screen (G-2). It also
 * launches what needs an Activity (O-2 permission requests, the Bluetooth enable dialog, P-5 location settings).
 */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        // §1.2.2: the splash holds while the core opens; a failure releases it to show the error and a retry
        installSplashScreen().setKeepOnScreenCondition { vm.ready.value == null && vm.coreState.value !is CoreState.Failed }
        super.onCreate(savedInstanceState)
        val permissions = vm.graph.permissions
        permissionLauncher = registerForActivityResult(permissions.requestLauncherContract()) { permissions.refresh(this) }
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.requests.collect { r ->
                    when (r) {
                        is MainViewModel.ActivityRequest.Permissions -> permissionLauncher.launch(r.names)
                        MainViewModel.ActivityRequest.EnableBluetooth -> permissions.requestEnableBluetooth(this@MainActivity)
                        MainViewModel.ActivityRequest.TurnOnLocation -> vm.graph.location.turnOn(this@MainActivity)
                    }
                }
            }
        }
        setContent { CluetoothTheme { MainScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.graph.permissions.refresh(this) // a grant changed in system settings, or Bluetooth was turned on
    }
}
