package com.biketrailplanner

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.biketrailplanner.nav.GoogleMapsLauncher
import com.biketrailplanner.nav.VelotricLauncher
import com.biketrailplanner.ui.ResultScreen
import com.biketrailplanner.ui.MapController
import com.biketrailplanner.ui.RideRecordingBanner
import com.biketrailplanner.ui.RideScreen
import com.biketrailplanner.ui.RouteMap
import com.biketrailplanner.ui.SettingsScreen
import com.biketrailplanner.ui.LocationProvider
import com.biketrailplanner.ui.PlanScreen
import com.biketrailplanner.ui.PlanViewModel
import com.biketrailplanner.ui.VelotricNudgeBar
import com.biketrailplanner.data.UserHomeStore
import com.biketrailplanner.domain.MapShapeChoice
import com.biketrailplanner.ui.theme.BikeTrailPlannerTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer by lazy { (application as BikeTrailPlannerApp).container }

    private val viewModel: PlanViewModel by viewModels {
        viewModelFactory {
            initializer {
                PlanViewModel(
                    container,
                    LocationProvider(applicationContext),
                    UserHomeStore(applicationContext),
                )
            }
        }
    }

    /** Set from the composition so a launcher launch can send the app back to its home screen. */
    private var navController: androidx.navigation.NavHostController? = null

    /**
     * docs/UI.md: the plan screen is the home of the app. A launch from the launcher (monkey, the
     * home-screen icon, `am start`) always returns there; returning from Google Maps does not,
     * because that resumes the task without a new intent.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.resetPlanGeneration()
        navController?.popBackStack("plan", inclusive = false)
    }

    /**
     * D35 (docs/VELOTRIC-LINK.md option A): bringing the app back to the front is the moment the
     * rider could be back from a ride, so read a fresh fix and let the ViewModel decide whether to
     * remind them to stop their Velotric ride. This is the "on-return arrival check" (piece 3a): no
     * background service and no new permission - only a check while the app is in front.
     */
    override fun onResume() {
        super.onResume()
        viewModel.refreshFixQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.resetPlanGeneration()
        setContent {
            BikeTrailPlannerTheme {
                val navController = rememberNavController().also { this@MainActivity.navController = it }
                val state by viewModel.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                // D59: one map, one controller. The map itself is owned by RouteMap and kept alive
                // across screens (D21); screens reach it through this for the zoom buttons.
                val mapController = remember { MapController() }
                // D35 (option A): the Velotric assist only opens the Velotric app, and the button is
                // hidden when the app is not installed. Availability is asked once per composition.
                val velotric = remember { VelotricLauncher(context) }
                val velotricAvailable = remember(velotric) { velotric.available() }
                val openVelotric: () -> Unit = {
                    // D36: remember that a Velotric ride was started, so the safety net can remind
                    // the rider even if Google Maps navigation is never seen.
                    viewModel.onVelotricRideStarted()
                    when (val r = velotric.open()) {
                        is VelotricLauncher.Result.Failed -> viewModel.onHandOffResult(r.reason)
                        else -> viewModel.onHandOffResult("Opened the Velotric app. Tap START RIDING there.")
                    }
                }
                val onStartVelotricRide: (() -> Unit)? = if (velotricAvailable) openVelotric else null
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { viewModel.refreshFixQuietly() }

                // The app always opens on the plan screen (the home of the app), even when Android
                // restores a saved navigation state.
                LaunchedEffect(Unit) {
                    navController.popBackStack("plan", inclusive = false)
                }

                // docs/UI.md sections 3 and 4: a NEW plan opens the result screen by itself, so the
                // rider never has to hunt for it.
                LaunchedEffect(state.planGeneration) {
                    if (state.planGeneration > 0 && !state.planning &&
                        navController.currentDestination?.route != "result"
                    ) {
                        navController.navigate("result")
                    }
                }

                LaunchedEffect(Unit) {
                    // D36: the end-of-ride prompt is a notification, so ask for that too on API 33+.
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                    viewModel.refreshFixQuietly()
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    // D21: one basemap for the whole app, behind every screen. It stays in the
                    // composition while the screens change, which is both cheaper and the only way
                    // to keep MapLibre's surface thread out of the way.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
                    RouteMap(
                        // D37: the map draws the option the rider has actually chosen, not always the
                        // one the rotation picked.
                        // D59: or the recorded ride the rider tapped, when there is one - a selected
                        // ride outranks the plan, so tapping a row really changes what is behind it.
                        shape = MapShapeChoice.of(state.selectedRide, state.selectedRoute, state.liveRoute),
                        origin = state.planningOrigin ?: state.home,
                        modifier = Modifier.fillMaxSize(),
                        onTap = if (state.selectedRide != null) viewModel::onMapTap else null,
                        highlight = state.rideHit,
                        controller = mapController,
                        showZoomControls = state.selectedRide != null,
                        // D53/D64: the camera fits the ride into the map the rider can see, above
                        // whatever height the sheet is at.
                        coveredBottomFraction = state.panelFraction,
                    )
                    Column(Modifier.fillMaxSize()) {
                        // D35 (option A, piece 3a): shown on any screen when the rider is back at the
                        // start of the ride they were handed to navigation.
                        // D36: also shown for the safety net when the app cannot post a notification.
                        if (state.rideNudge != null || state.stopRideReminder) {
                            VelotricNudgeBar(onOpen = openVelotric, onDismiss = viewModel::dismissRideNudge)
                        }
                        // D51: a recording is never silent. The banner is on every screen, so the rider
                        // can always see it, stop it, and get to the rides it has saved.
                        if (state.recordingActive) {
                            RideRecordingBanner(
                                onStop = viewModel::toggleRideRecording,
                                onOpenRides = {
                                    viewModel.loadRides()
                                    navController.navigate("rides")
                                },
                            )
                        }
                    NavHost(
                        navController = navController,
                        startDestination = "plan",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        composable("plan") {
                            PlanScreen(
                                state = state,
                                onTargetMiles = viewModel::onTargetMiles,
                                onPlan = viewModel::plan,
                                onRefreshLocation = viewModel::refreshLocation,
                                onRefreshTrailData = viewModel::refreshTrailData,
                                onOpenSettings = { navController.navigate("settings") },
                                // D59: the rider's own rides, on their own screen.
                                onOpenRide = {
                                    viewModel.loadRides()
                                    navController.navigate("ride")
                                },
                                onOpenResult = { navController.navigate("result") },
                                onFindTrailsNearHere = viewModel::findTrailsNearHere,
                                onUseSuggestion = viewModel::useSuggestion,
                                onTrailPicked = viewModel::onTrailPicked,
                                onCancelPlan = viewModel::cancelPlan,
                                // D53: the rider can shrink the panel to see more of the map.
                                onPanelFraction = viewModel::onPanelFraction,
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                state = state,
                                onAllowFallback = viewModel::onAllowFallbackChange,
                                onRefreshTrailData = viewModel::refreshTrailData,
                                onSaveCurrentSpotAsHome = viewModel::saveCurrentSpotAsHome,
                                onForgetSavedHome = viewModel::forgetSavedHome,
                                // D36: Notification access is granted in the system Settings, not by a
                                // runtime dialog, so the row sends the rider there.
                                onOpenNotificationSettings = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("ride") {
                            // D59/D60: the rider's rides, with the map still visible behind them.
                            LaunchedEffect(Unit) { viewModel.loadRides() }
                            RideScreen(
                                state = state,
                                controller = mapController,
                                onToggleRecording = viewModel::toggleRideRecording,
                                onSimulateRide = viewModel::simulateRide,
                                onSelectRide = viewModel::selectRide,
                                onDeleteRide = viewModel::deleteRide,
                                onPanelFraction = viewModel::onPanelFraction,
                                onBack = { navController.popBackStack() },
                            )
                            // Leaving the screen puts the plan back on the map, so the next screen is
                            // not still showing a ride the rider has walked away from.
                            DisposableEffect(Unit) { onDispose { viewModel.clearSelectedRide() } }
                        }
                        composable("result") {
                            ResultScreen(
                                state = state,
                                onBack = { navController.popBackStack() },
                                onDetourAccepted = viewModel::onDetourAccepted,
                                onTrySaferDetour = viewModel::trySaferDetour,
                                onPlanLongerTotal = viewModel::planLongerTotal,
                                onTryTrail = { name ->
                                    viewModel.planWithTrail(name)
                                    navController.popBackStack()
                                },
                                onFindTrailsNearHere = viewModel::findTrailsNearHere,
                                onStartVelotricRide = onStartVelotricRide,
                                onSelectRoute = viewModel::selectRoute,
                                onOpenInMaps = { navigate ->
                                    val url = viewModel.recordHandOff(navigate)
                                    if (url == null) {
                                        viewModel.onHandOffResult("No hand-off URL to open.")
                                    } else {
                                        val result = GoogleMapsLauncher(context).launch(url)
                                        viewModel.onHandOffResult(
                                            when (result) {
                                                is GoogleMapsLauncher.Result.Launched ->
                                                    "Handed off to ${result.packageName ?: "an app"}."
                                                is GoogleMapsLauncher.Result.Failed -> result.reason
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}
