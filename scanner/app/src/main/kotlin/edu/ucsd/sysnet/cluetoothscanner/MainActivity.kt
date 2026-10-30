package edu.ucsd.sysnet.cluetoothscanner

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import edu.ucsd.sysnet.cluetoothscanner.api.platform.PlatformResult
import edu.ucsd.sysnet.cluetoothscanner.designsystem.CluetoothTheme
import edu.ucsd.sysnet.cluetoothscanner.v2.AppGraph
import edu.ucsd.sysnet.cluetoothscanner.v2.CoreLink
import edu.ucsd.sysnet.cluetoothscanner.v2.CoreState
import edu.ucsd.sysnet.cluetoothscanner.v2.MainScreen
import edu.ucsd.sysnet.cluetoothscanner.v2.MainViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/**
 * v2 (§1.2.2): the system splash stays until the core is open; then the one adaptive main screen (G-2). It also
 * launches what needs an Activity (O-2 permission requests, the Bluetooth enable dialog, P-5 location settings), and
 * keeps the screen on while it is in the foreground (G-15).
 */
class MainActivity : ComponentActivity() {
    /** The manifest names [CluetoothApplication], so the graph is always there; without it there is nothing to show. */
    private val graph: AppGraph? get() = (application as? CluetoothApplication)?.graph

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // G-15: in the foreground the screen never turns off by itself (as the v3 app did); the window drops it in the background
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = graph ?: return finish()
        val vm = ViewModelProvider(this, MainViewModel.factory(application, graph))[MainViewModel::class.java]
        // §1.2.2: the splash renders from the core state: it holds while Opening (and until the screen's state is built)
        splash.setKeepOnScreenCondition { vm.coreState.value is CoreState.Opening || (vm.coreState.value is CoreState.Ready && vm.ui.value.core is CoreLink.Waiting) }
        val permissions = graph.permissions
        val refresh = { runCatching { permissions.refresh(this) }.onFailure(vm::launchFailed); Unit }
        val permissionLauncher = registerForActivityResult(permissions.requestLauncherContract()) { refresh() }
        // a grant changed in system settings, or Bluetooth was turned on
        lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() })
        enableEdgeToEdge()
        lifecycleScope.launch(CoroutineExceptionHandler { _, e -> vm.launchFailed(e) }) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.requests.collect { r ->
                    // a launch that throws (no such activity on the device) is the ViewModel's typed failure
                    runCatching {
                        val launched = when (r) {
                            is MainViewModel.ActivityRequest.Permissions -> PlatformResult.Ok(permissionLauncher.launch(r.names))
                            MainViewModel.ActivityRequest.EnableBluetooth -> permissions.requestEnableBluetooth(this@MainActivity)
                            MainViewModel.ActivityRequest.TurnOnLocation -> graph.location.turnOn(this@MainActivity)
                        }
                        // a launch the platform refused (no such activity) is a typed failure, like a thrown one
                        if (launched is PlatformResult.Err) vm.launchFailed(launched.error)
                    }.onFailure(vm::launchFailed)
                }
            }
        }
        setContent { CluetoothTheme { MainScreen(vm) } }
    }
}
