package edu.ucsd.sysnet.cluetoothscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.v2.AppGraph
import edu.ucsd.sysnet.cluetoothscanner.v2.CoreState
import edu.ucsd.sysnet.cluetoothscanner.v2.MainScreen
import edu.ucsd.sysnet.cluetoothscanner.v2.MainViewModel
import kotlinx.coroutines.launch

/**
 * v2 (§1.2.2): the system splash stays until the core is open; then the one adaptive main screen (G-2). It also
 * launches what needs an Activity (O-2 permission requests, the Bluetooth enable dialog, P-5 location settings).
 */
class MainActivity : ComponentActivity() {
    /** The manifest names [CluetoothApplication], so the graph is always there; without it there is nothing to show. */
    private val graph: AppGraph? get() = (application as? CluetoothApplication)?.graph

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val graph = graph ?: return finish()
        val vm = ViewModelProvider(this, MainViewModel.factory(application, graph))[MainViewModel::class.java]
        // §1.2.2: the splash renders from the core state: it holds while Opening (and until the screen's state is built)
        splash.setKeepOnScreenCondition { vm.coreState.value is CoreState.Opening || (vm.coreState.value is CoreState.Ready && vm.ui.value.ready == null) }
        val permissions = graph.permissions
        val permissionLauncher = registerForActivityResult(permissions.requestLauncherContract()) { permissions.refresh(this) }
        enableEdgeToEdge()
        lifecycleScope.launch(graph.core.handler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.requests.collect { r ->
                    when (r) {
                        is MainViewModel.ActivityRequest.Permissions -> permissionLauncher.launch(r.names)
                        MainViewModel.ActivityRequest.EnableBluetooth -> permissions.requestEnableBluetooth(this@MainActivity)
                        MainViewModel.ActivityRequest.TurnOnLocation -> graph.location.turnOn(this@MainActivity)
                    }
                }
            }
        }
        setContent { CluetoothTheme { MainScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        graph?.permissions?.refresh(this) // a grant changed in system settings, or Bluetooth was turned on
    }
}
