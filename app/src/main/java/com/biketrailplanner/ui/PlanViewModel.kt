package com.biketrailplanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.biketrailplanner.AppContainer
import com.biketrailplanner.data.AddressLookup
import com.biketrailplanner.data.UserHomeStore
import com.biketrailplanner.data.DiscoveryMerge
import com.biketrailplanner.data.DiscoveryResult
import com.biketrailplanner.data.OverpassException
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.GradeBreakdown
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.MapsUrlBuilder
import com.biketrailplanner.domain.NamedSegment
import com.biketrailplanner.domain.PlanOutcome
import com.biketrailplanner.domain.PlanPolicy
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.PolicyVerdict
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.ReusableRoute
import com.biketrailplanner.domain.RideHit
import com.biketrailplanner.domain.RideHitTest
import com.biketrailplanner.domain.RideNudge
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.RideWatch
import com.biketrailplanner.domain.RouteStore
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.TrailNear
import com.biketrailplanner.domain.TrailsNear
import com.biketrailplanner.domain.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * D57: how long to give the foreground service to actually start before trusting (or dropping) the
 * optimistic "recording" the toggle shows. Only ever corrects towards the truth.
 */
private const val START_SETTLE_MS = 2_000L

/**
 * D57: how long to wait between checks that a stopped recording has really gone. The service saves
 * before it stops and a trace over a slow link can take a while, so these are cumulative waits rather
 * than a single one.
 */
private val STOP_SETTLE_STEPS_MS = listOf(1_000L, 2_000L, 5_000L, 12_000L, 30_000L)

class PlanViewModel(
    private val container: AppContainer,
    private val locationProvider: LocationProvider,
    private val userHome: UserHomeStore,
) : ViewModel() {

    data class UiState(
        val targetMiles: String = com.biketrailplanner.AppContainer.DEFAULT_TARGET_MILES.toString(),
        /** The SAVED home: the bundled one, or the spot the rider saved themselves. */
        val home: LatLon,
        val homeLabel: String,
        /** True when the rider saved their own home (so it can be forgotten again). */
        val savedHomeIsUserSet: Boolean = false,
        /**
         * docs/LOCATION-CONTROL.md B2: the place this ride is planned AROUND. It is the live fix
         * whenever one is good (D22), and null when no fix is good enough to plan from. It is NOT
         * the saved home; the saved home only says where the bundled data is valid.
         */
        val planningOrigin: LatLon? = null,
        val planningOriginLabel: String = RiderText.PlanningOrigin.NO_FIX_LABEL,
        /** True when the live fix is close enough to the saved home to treat the rider as there. */
        val planningOriginAtSavedHome: Boolean = false,
        /** How far the planning origin is from the saved home, for honest coverage wording. */
        val planningOriginFromSavedHomeM: Double? = null,
        /** The explicit confirmation the rider gets after tapping "Use my location". */
        val positionNote: String? = null,
        /** What happened after "Save this spot as home". */
        val saveHomeMessage: String? = null,
        val planning: Boolean = false,
        /**
         * When the running plan started, so the sheet can say how long it has been working instead of
         * showing an indeterminate spinner forever. A plan is ~25 candidate evaluations and 2 HTTP
         * calls each, so on the fallback router it is minutes, not seconds - the rider deserves to see
         * that clock and to be able to stop it.
         */
        val planStartedAtMs: Long? = null,
        /** The trail the running plan is restricted to, when the rider picked one. */
        val planningTrail: String? = null,
        /**
         * D43: how many "Try a safer detour" taps the next plan is answering. 0 is the ordinary plan.
         * It is a one-shot: the run consumes it, so a later plan is the shipped search again.
         */
        val searchHarderAttempt: Int = 0,
        val result: PlanResult? = null,
        /**
         * D37: WHICH of the plan's options the rider is looking at. The plan computes up to five
         * distinct rides (D6/D29) and picks one by rotation; this is the one the map, the summary,
         * the hand-off and the ride history use. Null until a plan completes.
         */
        val selectedRoute: PlannedRoute? = null,
        val error: String? = null,
        val allowFallback: Boolean = false,
        val serverReachable: Boolean? = null,
        val mapsUrl: MapsUrlBuilder.MapsUrl? = null,
        val navigateUrl: MapsUrlBuilder.MapsUrl? = null,
        val handOffMessage: String? = null,
        /**
         * D35 (docs/VELOTRIC-LINK.md option A, piece 3a): the Velotric assist. [handOffAtMs] and
         * [handOffStartPoint] stamp the ride the rider was handed to Google Maps for, so a good fix
         * back near that point can prompt them to stop their Velotric ride. [rideNudge] is the prompt
         * the screen should show; null means show nothing. [nudgeDismissed] silences it until the
         * next hand-off.
         */
        val handOffAtMs: Long? = null,
        val handOffStartPoint: LatLon? = null,
        val rideNudge: RideNudge.Nudge? = null,
        val nudgeDismissed: Boolean = false,
        /**
         * D36: the end-of-navigation watcher. [notificationAccessGranted] and [notificationsEnabled]
         * are the two grants the prompt needs; [rideWatchArmed] says a ride is currently being
         * watched. While the watcher is live it owns the end-of-ride prompt, and [rideNudge] above is
         * the fallback for when it is not.
         */
        val notificationAccessGranted: Boolean = false,
        val notificationsEnabled: Boolean = false,
        val rideWatchArmed: Boolean = false,
        /** D51: a ride is being recorded right now (read from the container's persisted flag). */
        val recordingActive: Boolean = false,
        /**
         * D53: how much of the frame the bottom panel covers, 0.30..1.0. The rider resizes it to see
         * more map; it always sits on a preset ([com.biketrailplanner.domain.PanelSize]).
         */
        val panelFraction: Float = com.biketrailplanner.domain.PanelSize.FULL,
        /**
         * D36 safety net: a Velotric ride was started through the app but no navigation was ever
         * seen for it, so the rider is shown the stop-the-ride bar on the next open (used when the
         * app cannot post a notification at all; otherwise the reminder is the notification itself).
         */
        val stopRideReminder: Boolean = false,
        val historyCount: Int = 0,
        /** D51: the rides the rider actually recorded, newest first. Empty until [loadRides] runs. */
        val rides: List<RideTrack> = emptyList(),
        /** True while the recorded-ride list is being read, so the screen can show something. */
        val ridesLoading: Boolean = false,
        /** D59: the ride the rider tapped in the list; it is what the shared map draws. */
        val selectedRide: RideTrack? = null,
        /** D60: the node or street the rider just tapped on the map. */
        val rideHit: RideHit? = null,
        /** D60: the postal address of the tapped node, when one could be had. */
        val rideAddress: String? = null,
        /** True while the address is being looked up. */
        val addressLoading: Boolean = false,
        /**
         * D60: set when an address cannot be shown because the rider has not allowed public servers.
         * Kept separate from "no address", because "we are not allowed to ask" is not "there is none".
         */
        val addressNeedsConsent: Boolean = false,
        val currentLocation: LatLon? = null,
        val locationKnown: Boolean = false,
        /** D22: the live origin, its accuracy, and whether it is good enough to plan from. */
        val origin: LatLon? = null,
        val originAccuracyM: Float? = null,
        val originAgeS: Long? = null,
        val originGood: Boolean = false,
        /** D22 rule 1: the screen always states that the ride starts from the live position. */
        val fixNote: String? = "Checking your location\u2026",
        val refreshing: Boolean = false,
        val trailRefresh: String? = null,
        val detourAccepted: Boolean = false,
        /** Flags the data producer attached to the limits table (D14/D16): never suppressed. */
        val dataFlags: List<String> = emptyList(),
        /** Plain-language status line for the sheet (never internal vocabulary). */
        val riderMessage: String? = null,
        val serverUrl: String = "",
        /** Other trails the rider can try (D13 option ii). */
        val alternativeTrails: List<String> = emptyList(),
        /**
         * D38: every trail the app can reach from where the rider is, CLOSEST FIRST, with its ridable
         * length and how far away it starts. Computed from the live fix, so the list is on screen
         * before anything is planned - the rider picks a trail, they do not wait for a plan to see one.
         */
        val trailsNear: List<TrailNear> = emptyList(),
        val chosenTrails: List<String> = emptyList(),
        /** Increments when a NEW plan completes, so the UI opens the result exactly once. */
        val planGeneration: Int = 0,
        /** D20: total rideable trail in the bundled network, for honest large-target warnings. */
        val networkTrailMiles: Double = 0.0,
        /** D20: said plainly when the requested distance is mostly the way there and back. */
        val honestyLine: String? = null,
        /**
         * D26 (docs/ROUTE-STORE.md): the rides already saved for this request. They are published
         * BEFORE any planning work happens, and using one costs no Valhalla call and no model call.
         */
        val suggestions: List<ReusableRoute> = emptyList(),
        val suggestionNote: String? = null,
        /** The saved ride the rider adopted, when the screen is showing one. */
        val adoptedSignature: String? = null,
        /** The line that says the route on screen came out of the saved-trip store. */
        val savedTripNote: String? = null,
        /** D27: true when the model's own suggestions were acted on. */
        val aiUsed: Boolean = false,
        /** D27: what the model suggested, what was thrown out and why. Rider language only. */
        val strategyNotes: List<String> = emptyList(),
        /** D23: the rider asked "find trails near here"; the answer in rider language. */
        val discoveryNote: String? = null,
        val discoveryBusy: Boolean = false,
        /** Trails the last discovery added to what the app already had. */
        val discoveredTrails: Int = 0,
    )

    /** The home the rider saved themselves, or null when they never have (docs/LOCATION-CONTROL 6.3.3). */
    private var userSavedHome: UserHomeStore.SavedHome? = userHome.load()

    /** The saved home: the rider's own when they have one, otherwise the bundled one. */
    private var savedHome: UserHomeStore.SavedHome = userSavedHome
        ?: UserHomeStore.SavedHome(
            container.loaded.config.home,
            RiderText.PlanningOrigin.savedHomeValue(container.loaded.config.homeLabel),
        )

    private val _state = MutableStateFlow(
        UiState(
            home = savedHome.point,
            homeLabel = savedHome.label,
            savedHomeIsUserSet = userSavedHome != null,
            allowFallback = container.allowPublicFallback,
            dataFlags = container.loaded.dataFlags,
            serverUrl = com.biketrailplanner.BuildConfig.VALHALLA_BASE_URL,
            targetMiles = container.lastTargetMiles.toString(),
            networkTrailMiles = container.networkTrailMiles,
            // D53: the panel is where the rider left it, not always full height.
            panelFraction = container.panelSize.load(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(historyCount = container.history.count())
        }
        refreshSuggestions()
    }

    /**
     * D26: look for the trips already saved for this request, and publish them, BEFORE any planning
     * work happens. Reading them is one indexed lookup plus a few small payloads: no Valhalla call, no
     * model call, nothing the rider has to pay for. The plan screen then offers them above its own
     * "Plan ride" action, so a suggestion never traps the rider and never hides the way to a new ride.
     */
    fun refreshSuggestions() {
        val target = _state.value.targetMiles.toDoubleOrNull() ?: return
        val origin = _state.value.planningOrigin ?: savedHome.point
        viewModelScope.launch { publishSuggestions(origin, target) }
    }

    private suspend fun publishSuggestions(origin: LatLon, targetMiles: Double) {
        val step = withContext(Dispatchers.Default) {
        SavedTripsFirst.look(
            store = container.routeStore,
            origin = origin,
            targetMiles = targetMiles,
            fingerprint = runCatching { container.dataFingerprint }.getOrNull(),
            riderAskedForNew = false,
        )
        }
        _state.value = _state.value.copy(
            suggestions = step.suggestions,
            suggestionNote = step.note,
        )
    }

    /** Called when the activity is created: the app opens on the plan screen. */
    fun resetPlanGeneration() {
        _state.value = _state.value.copy(planGeneration = 0)
    }

    fun onTargetChange(text: String) {
        if (text.length <= 6) _state.value = _state.value.copy(targetMiles = text)
    }

    /** D20: presets and the custom stepper both land here, and the choice is remembered. */
    fun onTargetMiles(miles: Int) {
        val clamped = miles.coerceIn(1, 200)
        container.lastTargetMiles = clamped
        _state.value = _state.value.copy(targetMiles = clamped.toString())
        refreshHonesty()
        // D26: a different distance is a different request, so the saved trips on offer change too.
        refreshSuggestions()
    }

    private fun refreshHonesty() {
        val target = _state.value.targetMiles.toDoubleOrNull() ?: return
        val route = _state.value.selectedRoute
        val trailMiles = route?.let { com.biketrailplanner.domain.Geo.metresToMiles(it.trailRoundTripM) }
        val line = when {
            trailMiles != null && trailMiles < target * 0.5 && trailMiles > 0.0 ->
                "Only " + fmt(trailMiles, 1) + " mi of this ride is trail; the rest is getting there and back."
            trailMiles == null && target > _state.value.networkTrailMiles ->
                "The trails in this app add up to about " + fmt(_state.value.networkTrailMiles, 0) +
                    " mi, so most of this ride will be on roads."
            else -> null
        }
        _state.value = _state.value.copy(honestyLine = line)
    }

    fun onAllowFallbackChange(value: Boolean) {
        container.allowPublicFallback = value
        _state.value = _state.value.copy(allowFallback = value)
    }

    /**
     * "Use my location" (docs/LOCATION-CONTROL.md, layer 3). Adopt the fresh fix as the place this
     * ride is planned around, say so on screen, and, when a ride is already on screen, plan it again
     * from there. The tap must never be invisible, which is what it was until today.
     */
    fun refreshLocation() {
        locationTapped = true
        // Answer the tap at once: waiting for the GPS chip can take seconds, and a button that says
        // nothing for ten seconds is the complaint this whole change is about.
        _state.value = _state.value.copy(positionNote = RiderText.PlanningOrigin.CHECKING)
        refreshFix()
    }

    /**
     * The automatic checks (app start, permission result): adopt the fix quietly. There was no tap,
     * so there is nothing to confirm and nothing to re-plan.
     */
    fun refreshFixQuietly() {
        locationTapped = false
        refreshFix()
        refreshRecording()
    }

    /**
     * D53: the rider resized the panel. The value is snapped to a preset and remembered, so the panel
     * is where they left it on the next launch.
     */
    fun onPanelFraction(fraction: Float) {
        val snapped = com.biketrailplanner.domain.PanelSize.nearest(fraction)
        if (_state.value.panelFraction != snapped) {
            _state.value = _state.value.copy(panelFraction = snapped)
        }
        container.savePanelFraction(snapped)
    }

    /**
     * D51: read whether a ride is being recorded. The flag is persisted in the container, so this
     * survives a process death; the screen reads it on resume and after a toggle.
     */
    fun refreshRecording() {
        val active = container.isRideRecording()
        if (_state.value.recordingActive != active) {
            _state.value = _state.value.copy(recordingActive = active)
        }
    }

    /**
     * D51: start or stop the in-app ride recorder. This is the manual half of the rider's "both
     * triggers" answer - a ride with no plan can still be recorded, and the recording is graded and
     * saved the same way the automatic one is.
     */
    fun toggleRideRecording() {
        if (container.isRideRecording()) {
            container.stopRideRecording()
            // D57: the service finishes the save (a Valhalla trace plus a DB write) BEFORE it stops, so
            // re-reading the flag right now still says "recording" and the banner would bounce straight
            // back. Hide it - the rider asked for it to stop - and poll until the service is really
            // gone, then trust whatever the truth turns out to be.
            _state.value = _state.value.copy(recordingActive = false)
            viewModelScope.launch {
                for (waitMs in STOP_SETTLE_STEPS_MS) {
                    delay(waitMs)
                    if (!container.isRideRecording()) break
                }
                refreshRecording()
            }
        } else {
            container.startRideRecording()
            // Starting a foreground service is asynchronous too, so the flag is not set when this
            // returns. Show the banner immediately - that is what the rider just asked for - and let
            // the reconcile below take it back if the service refused to start (no location grant, or
            // Android refused the foreground service). It only ever corrects an over-claim.
            _state.value = _state.value.copy(recordingActive = true)
            viewModelScope.launch {
                delay(START_SETTLE_MS)
                refreshRecording()
            }
        }
    }

    /**
     * D51 (debug only, docs/RIDE-RECORDER.md section 4): record a synthetic ride so the whole recorder
     * - service, notification, trace, grade, save - runs on the phone without the rider moving. It arms
     * the simulator first, then starts a recording the normal way; the simulator disarms itself as the
     * service reads it, so only this one recording is synthetic.
     */
    fun simulateRide() {
        container.enableRideSimulation()
        container.startRideRecording()
        refreshRecording()
    }

    /**
     * D51: read the recorded rides for the Rides screen, newest first. Reads the same store the
     * recorder writes to, so a ride that just finished appears here without a restart.
     */
    fun loadRides() {
        if (_state.value.ridesLoading) return
        _state.value = _state.value.copy(ridesLoading = true)
        viewModelScope.launch {
            val rides = runCatching { container.rideTracks.all() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(rides = rides, ridesLoading = false)
        }
    }

    /**
     * D59: the rider tapped a ride in the list. That ride becomes what the shared basemap draws, so the
     * list can finally show the route it is describing.
     */
    fun selectRide(signature: String) {
        val ride = _state.value.rides.firstOrNull { it.signature == signature } ?: return
        _state.value = _state.value.copy(
            selectedRide = ride,
            rideHit = null,
            rideAddress = null,
            addressNeedsConsent = false,
        )
    }

    /** Leaving the ride screen puts the plan back on the map. */
    fun clearSelectedRide() {
        val state = _state.value
        if (state.selectedRide == null && state.rideHit == null) return
        _state.value = state.copy(
            selectedRide = null,
            rideHit = null,
            rideAddress = null,
            addressLoading = false,
            addressNeedsConsent = false,
        )
    }

    /**
     * D60: the rider tapped the map. The node or street is answered from the ride's OWN data - the
     * recorded points and the streets the trace matched - so a tap works with no network at all. The
     * postal address is the one part that needs somebody else's server, so it is looked up separately.
     */
    fun onMapTap(point: LatLon) {
        val ride = _state.value.selectedRide ?: return
        val hit = RideHitTest.at(ride, point)
        _state.value = _state.value.copy(
            rideHit = hit,
            rideAddress = null,
            addressLoading = hit is RideHit.Node,
            addressNeedsConsent = false,
        )
        if (hit !is RideHit.Node) return
        viewModelScope.launch {
            val result = container.addressLookup.reverse(point)
            _state.value = _state.value.copy(
                rideAddress = result.getOrNull(),
                addressLoading = false,
                addressNeedsConsent = result.exceptionOrNull()?.message ==
                    AddressLookup.Refusal.NOT_ALLOWED.name,
            )
        }
    }

    /** D51: forget one recorded ride. This is the rider's own data, so deleting it is theirs to do. */
    fun deleteRide(signature: String) {
        val remaining = _state.value.rides.filterNot { it.signature == signature }
        _state.value = _state.value.copy(rides = remaining)
        viewModelScope.launch {
            runCatching { container.rideTracks.delete(signature) }
            // D58: the history row goes with the ride. Leaving it behind pins the rotation's
            // "do not repeat your last ride" to a ride the rider has just thrown away.
            runCatching { container.history.forget(signature) }
        }
    }

    /** "Try a safer detour": ask for a shorter round trip so a compliant route can keep the trail. */
    /**
     * "Try a safer detour" (D13 rule 3, D43).
     *
     * Two real actions, never a silent no-op:
     *  - when the plan carries a trail-floor offer, plan the longer total the offer describes (the
     *    D13 rule 3 tradeoff: more mileage buys the missing trail);
     *  - otherwise search again with a wider detour budget ([DetourPolicy.SEARCH_HARDER_FACTOR]
     *    instead of the shipped 1.80x) and REPORT the answer either way. The button is drawn on the
     *    card that appears when nothing was compliant, and in that state there is by definition no
     *    trail-floor offer - which is why it used to do nothing at all when tapped.
     */
    fun trySaferDetour() {
        val offer = _state.value.result?.trailFloorOffer
        val next = offer?.suggestedTotalMiles?.let { fmt(it, 1) }
        if (next != null) {
            _state.value = _state.value.copy(targetMiles = next)
            plan(adoptedFix = null, allowAi = false)
            return
        }
        val s = _state.value
        if (s.planning) return
        _state.value = s.copy(
            searchHarderAttempt = s.searchHarderAttempt + 1,
            riderMessage = com.biketrailplanner.ui.RiderText.SEARCH_HARDER_RUNNING,
        )
        plan(adoptedFix = null, allowAi = false)
    }

    fun onDetourAccepted(accepted: Boolean) {
        _state.value = _state.value.copy(detourAccepted = accepted)
    }

    /**
     * D37: the rider picks which of the plan's options to ride. Every option was already computed,
     * routed and graded by the plan, so this only switches what the map, the summary and the
     * hand-off use. It never re-plans and never spends a routing call or a model call.
     *
     * The detour acceptance is cleared because "longer than you asked for" is a property of ONE ride,
     * not of the plan: accepting the long way to trail A must not silently accept the long way to B.
     */
    fun selectRoute(signature: String) {
        val s = _state.value
        val result = s.result ?: return
        val options = result.options.ifEmpty { listOfNotNull(result.chosen ?: result.bestAttempt) }
        val route = options.firstOrNull { it.signature == signature } ?: return
        // Re-selecting the ride already on screen must not churn the state (the map redraws on it).
        if (route.signature == s.selectedRoute?.signature) return
        val origin = s.planningOrigin ?: savedHome.point
        val maxWaypoints = container.loaded.config.planner.maxWaypoints
        val maxUrlLength = container.loaded.config.planner.maxUrlLength
        _state.value = s.copy(
            selectedRoute = route,
            mapsUrl = MapsUrlBuilder.build(route, origin, maxWaypoints, maxUrlLength, dirAction = false),
            navigateUrl = MapsUrlBuilder.build(route, origin, maxWaypoints, maxUrlLength, dirAction = true),
            handOffMessage = null,
            detourAccepted = false,
            chosenTrails = route.trailNames,
        )
    }

    /** D13 rule 3 option (i): plan again with a longer total so the trail floor is kept. */
    fun planLongerTotal(suggestedMiles: Double) {
        val text = fmt(suggestedMiles, 1)
        _state.value = _state.value.copy(targetMiles = text)
        plan(adoptedFix = null, allowAi = false)
    }

    /** D13 option (ii): plan a different trail. */
    fun planWithTrail(name: String) {
        preferredTrail = name
        plan(adoptedFix = null, allowAi = false)
    }

    /**
     * D38: the rider picked a trail off the "trails near you" list. Same path as the D13 "try a
     * different trail" action - what changes is only how the rider got there - so there is exactly one
     * implementation of "plan this trail", and it never spends a model call (D27: the AI is for the
     * "Plan ride" button).
     */
    fun onTrailPicked(name: String) = planWithTrail(name)

    /**
     * The start area the trails-near list was computed for. The regional bundle is sampled per start
     * area (~1 km), so recomputing on every GPS update would be pure waste; a real move recomputes.
     */
    private var trailsNearBucket: String? = null

    /**
     * D38: refresh the closest-first trail list for the live position.
     *
     * The merged set is the same one the planner uses - bundled, regional, and whatever "find trails
     * near here" added - so the list and the plan can never disagree about what is out there. The work
     * is geometry over thousands of points and a regional sample, so it runs off the main thread.
     */
    private fun refreshTrailsNear() {
        val origin = _state.value.planningOrigin ?: return
        val bucket = RouteKey.bucket(origin)
        if (bucket == trailsNearBucket) return
        trailsNearBucket = bucket
        viewModelScope.launch {
            val list = withContext(Dispatchers.Default) {
                val merged = container.loaded.network.trails +
                    runCatching { container.regional(origin).trails }.getOrDefault(emptyList()) +
                    discovered?.trails.orEmpty()
                TrailsNear.closest(merged, origin)
            }
            _state.value = _state.value.copy(trailsNear = list)
        }
    }

    private var preferredTrail: String? = null

    private fun fixNoteFor(fix: LocationProvider.Fix): String = when {
        fix.ageSeconds > 60 -> "Waiting for a good GPS fix: the last one is " + fix.ageSeconds + " s old."
        fix.accuracyM > 50f -> "Waiting for a good GPS fix: accuracy is " + fmt(fix.accuracyM.toDouble(), 0) + " m."
        else -> "Starting from your location (\u00b1" + fmt(fix.accuracyM.toDouble(), 0) + " m)."
    }

    private fun publishFix(fix: LocationProvider.Fix?) {
        // Only a fix good enough to plan from becomes the place this ride is planned around (D22).
        val goodFix = fix?.takeIf { it.isGood(GOOD_FIX_MAX_AGE_S, GOOD_FIX_MAX_ACCURACY_M) }
        val atSavedHome = goodFix != null &&
            RiderText.PlanningOrigin.atSavedHome(goodFix.point, savedHome.point)
        _state.value = _state.value.copy(
            currentLocation = fix?.point,
            locationKnown = fix != null,
            origin = fix?.point,
            originAccuracyM = fix?.accuracyM,
            originAgeS = fix?.ageSeconds,
            originGood = goodFix != null,
            fixNote = fix?.let { fixNoteFor(it) } ?: "No location yet: turn location on and try again.",
            planningOrigin = goodFix?.point,
            planningOriginAtSavedHome = atSavedHome,
            planningOriginLabel = goodFix
                ?.let { RiderText.PlanningOrigin.label(it.accuracyM, atSavedHome, savedHome.label) }
                ?: RiderText.PlanningOrigin.NO_FIX_LABEL,
            planningOriginFromSavedHomeM = goodFix?.let { Geo.distanceM(it.point, savedHome.point) },
        )
        // D35: a good fix is the only thing that may say "you are back where you started". This runs
        // on every good fix, including the one read when the app is brought back to the foreground.
        evaluateArrival(goodFix?.point)
        // D36: keep the Settings row honest about the watch and its two grants.
        refreshWatchStatus()
        // D38: the trails near this position, closest first, so the sheet shows what is reachable
        // before the rider commits to planning anything.
        refreshTrailsNear()
        // D26: the requests saved for THIS start area are the ones worth offering, so a new position
        // re-runs the lookup. It is a local index probe with no network call.
        refreshSuggestions()
    }

    /** True when the running refresh came from a tap, so it owes the rider a confirmation. */
    private var locationTapped = false

    /**
     * D23: the last "find trails near here" answer. The next plan uses it as the merged set, so the
     * button changes what the app plans with and not only what it says. Null means "the bundled
     * trails only", which is exactly the behaviour before the button existed.
     */
    private var discovered: DiscoveryResult? = null

    /**
     * D22 rule 1: read the position fresh, but paint whatever we already have first, so the screen
     * never sits on "checking" while the GPS chip wakes up.
     */
    private fun refreshFix() {
        locationProvider.lastFix()?.let { publishFix(it) }
        viewModelScope.launch {
            val fresh = locationProvider.freshFix()
            when {
                fresh != null -> publishFix(fresh)
                locationProvider.lastFix() == null -> publishFix(null)
            }
            if (locationTapped) {
                locationTapped = false
                onLocationAdopted(fresh)
            }
        }
    }

    /**
     * The visible half of the tap: always a sentence, and a re-plan only when a ride is on screen,
     * the fix is good, and no plan is already running.
     */
    private fun onLocationAdopted(fresh: LocationProvider.Fix?) {
        val s = _state.value
        val action = RiderText.PlanningOrigin.tapAction(
            originGood = s.originGood,
            hasPlan = s.result != null,
            planning = s.planning,
        )
        val note = when (action) {
            RiderText.PlanningOrigin.TapAction.NOT_USABLE ->
                RiderText.PlanningOrigin.notUsable(s.originAgeS, s.originAccuracyM)
            else -> RiderText.PlanningOrigin.confirmation(s.planningOriginLabel, action)
        }
        _state.value = _state.value.copy(positionNote = note)
        if (action == RiderText.PlanningOrigin.TapAction.REPLAN) {
            // Reuse the fix we just read: the announcement and the plan then come from one position.
            plan(adoptedFix = fresh)
        }
    }

    /**
     * "Save this spot as home" (docs/LOCATION-CONTROL.md, layer 3 item 3). SharedPreferences, never
     * the bundled assets: `assets/config.json` is read-only at runtime and is also the only record of
     * where the bundled trail and traffic data is valid.
     */
    fun saveCurrentSpotAsHome() {
        val point = _state.value.planningOrigin
        if (point == null) {
            _state.value = _state.value.copy(
                saveHomeMessage = "No current position to save yet. Wait for a good position, then try again.",
            )
            return
        }
        val label = RiderText.PlanningOrigin.savedHomeLabel(
            nearBundledArea = Geo.distanceM(point, container.loaded.config.home) <= KEEP_BUNDLED_LABEL_M,
            bundledLabel = RiderText.PlanningOrigin.savedHomeValue(container.loaded.config.homeLabel),
            today = LocalDate.now().format(SAVED_HOME_DATE),
        )
        userHome.save(point, label)
        userSavedHome = UserHomeStore.SavedHome(point, label)
        savedHome = userSavedHome!!
        reloadInBackground()
        _state.value = _state.value.copy(
            home = point,
            homeLabel = label,
            savedHomeIsUserSet = true,
            saveHomeMessage = RiderText.PlanningOrigin.savedHomeMessage(label),
        )
    }

    /** Undo: back to the home the app shipped with. */
    fun forgetSavedHome() {
        userHome.clear()
        userSavedHome = null
        savedHome = UserHomeStore.SavedHome(
            container.loaded.config.home,
            RiderText.PlanningOrigin.savedHomeValue(container.loaded.config.homeLabel),
        )
        _state.value = _state.value.copy(
            home = savedHome.point,
            homeLabel = savedHome.label,
            savedHomeIsUserSet = false,
            saveHomeMessage = RiderText.PlanningOrigin.FORGOTTEN_MESSAGE,
        )
    }

    /** The existing refresh path, off the main thread (it re-reads the bundled data). */
    private fun reloadInBackground() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { runCatching { container.reload() } }
        }
    }

    /**
     * The "Plan ride" button, and NOTHING else (D27 as the rider narrowed it on 2026-09-16: "don't
     * directly use ai, only use it when I click on Plan ride button. Don't use it up front or for
     * existing rides"). This is the one path that may spend the rider's DeepSeek tokens.
     */
    fun plan() = plan(adoptedFix = null, allowAi = true)

    /**
     * Every other way a plan can start: a location tap re-planning an on-screen ride, "try a safer
     * detour", "plan a longer total", "plan a different trail", an adopted saved ride. These are all
     * the app doing work the rider did not ask the AI for, so they never reach the model.
     *
     * [adoptedFix] is the fix a location tap has just read, so the announcement and the plan come
     * from the same position and the GPS is not read twice.
     */
    /** The running plan, so the rider can stop it. Null when nothing is running. */
    private var planJob: kotlinx.coroutines.Job? = null

    /**
     * Stop a plan in flight. The ladder is dozens of sequential routing calls when the safety rules
     * are struggling with the picked trail, and a rider who changes their mind must not have to wait
     * it out or kill the app.
     */
    fun cancelPlan() {
        val job = planJob ?: return
        planJob = null
        job.cancel()
        _state.value = _state.value.copy(
            planning = false,
            planStartedAtMs = null,
            planningTrail = null,
            riderMessage = "Planning stopped. Nothing was changed - pick a trail or plan again.",
        )
    }

    private fun plan(adoptedFix: LocationProvider.Fix?, allowAi: Boolean = false) {
        // Never two plans at once: the "Use my location" tap must not start a second run.
        if (_state.value.planning) return
        val target = _state.value.targetMiles.toDoubleOrNull()
        if (target == null || target <= 0.0) {
            _state.value = _state.value.copy(error = "Enter the round-trip mileage as a number, for example 12")
            return
        }
        _state.value = _state.value.copy(
            planning = true, error = null, result = null, selectedRoute = null,
            planStartedAtMs = System.currentTimeMillis(),
            // Name the trail on screen when the rider picked one: after tapping a row in the trail
            // list, "planning on Sumner Link Trail" is the difference between waiting and wondering.
            planningTrail = preferredTrail,
            mapsUrl = null, handOffMessage = null, detourAccepted = false,
            // D35: a new plan is a new ride, so any Velotric prompt left over from the last one is
            // dropped rather than compared against the wrong start point. D36: the navigation watch
            // is disarmed for the same reason - it belongs to the ride that was handed off.
            handOffAtMs = null, handOffStartPoint = null, rideNudge = null, nudgeDismissed = false,
            rideWatchArmed = false,
        )
        container.rideWatch.clear()
        planJob = viewModelScope.launch {
            val reachable = runCatching { container.client.primaryReachable() }.getOrDefault(false)
            // D22 rule 2: never plan from a stale or vague fix; say what is wrong and stop.
            val fix = adoptedFix ?: locationProvider.freshFix()
            if (fix == null || !fix.isGood(GOOD_FIX_MAX_AGE_S, GOOD_FIX_MAX_ACCURACY_M)) {
                _state.value = _state.value.copy(
                    planning = false,
                    planStartedAtMs = null,
                    planningTrail = null,
                    serverReachable = reachable,
                    origin = fix?.point,
                    originAccuracyM = fix?.accuracyM,
                    originAgeS = fix?.ageSeconds,
                    originGood = false,
                    fixNote = fix?.let { fixNoteFor(it) } ?: "No GPS fix yet: turn location on and try again.",
                )
                return@launch
            }
            val loc = fix.point
            // The plan's own position becomes the planning origin, through the one path that sets it.
            publishFix(fix)
            // D23: the merged set is the bundled six plus the regional bundle, plus whatever the
            // rider's own "find trails near here" answer added.
            // never run the planner on the main thread: it does seconds of geometry and JSON work.
            // Building it counts: the regional trail file is decoded and sampled here too, once per
            // ~1 km start area (docs/REGIONAL-TRAILS.md).
            // D43: a "try a safer detour" tap widens the D13 budget for THIS run only. The shipped
            // 1.80x band is the automatic limit; searching further out is exactly what the rider
            // asked for by tapping, and the outcome is reported either way (never silence).
            val harder = _state.value.searchHarderAttempt
            val shippedPlanner = container.loaded.config.planner
            val widened = if (harder > 0) {
                shippedPlanner.copy(
                    detourMaxFactor = com.biketrailplanner.domain.DetourPolicy
                        .harderSearchFactor(shippedPlanner.detourMaxFactor),
                    maxViaCandidates = shippedPlanner.maxViaCandidates * 2,
                )
            } else {
                null
            }
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val planner = container.planner(loc, discovered = discovered, configOverride = widened)
                planner.plan(
                    com.biketrailplanner.domain.PlanRequest(
                        targetMiles = target,
                        origin = loc,
                        originAccuracyM = fix.accuracyM,
                        originFixAgeS = fix.ageSeconds,
                        maxCandidates = container.loaded.config.planner.maxCandidates,
                        accessPointsPerTrail = container.loaded.config.planner.accessPointsPerTrail,
                        allowFallback = container.allowPublicFallback,
                        preferTrails = listOfNotNull(preferredTrail),
                        // The rider's gate: true only on the explicit "Plan ride" tap.
                        allowAi = allowAi,
                    ),
                )
                }
            }.getOrElse { e ->
                _state.value = _state.value.copy(
                    planning = false, planStartedAtMs = null, planningTrail = null,
                    serverReachable = reachable, currentLocation = loc, locationKnown = loc != null,
                    error = "Planning failed: ${e.message}",
                )
                return@launch
            }
            // docs/ROUTE-STORE.md: remember what the app just worked out, so the same request is
            // never planned (and never paid for) twice. Only trips that passed the safety gate are
            // worth keeping; the waypoints are saved with the geometry so the hand-off URL needs no
            // re-planning either.
            runCatching {
                val now = System.currentTimeMillis()
                val maxWaypoints = container.loaded.config.planner.maxWaypoints
                val maxUrlLength = container.loaded.config.planner.maxUrlLength
                result.attempts
                    .mapNotNull { it.route }
                    .filter { it.verdict.hasNoKnownViolation }
                    .distinctBy { it.signature }
                    .forEach { route ->
                        // The waypoints are saved in the ORDER the hand-off URL visits them, so a
                        // saved trip's URL needs no re-planning and is the same URL the fresh plan
                        // produced (docs/ROUTE-STORE.md section 1).
                        val handOff = MapsUrlBuilder.build(
                            route = route,
                            home = loc,
                            maxWaypoints = maxWaypoints,
                            maxUrlLength = maxUrlLength,
                            dirAction = true,
                        )
                        container.routeStore.save(
                            route = route,
                            origin = loc,
                            targetMiles = target,
                            waypoints = handOff.included.map { it.point },
                            fingerprint = container.dataFingerprint,
                            nowMs = now,
                        )
                    }
            }

            val shown = result.chosen ?: result.bestAttempt
            // D22 rule 5: the hand-off destination is the LIVE origin (the loop-closing point).
            val confirmUrl = shown?.let {
                MapsUrlBuilder.build(
                    route = it,
                    home = loc,
                    maxWaypoints = container.loaded.config.planner.maxWaypoints,
                    maxUrlLength = container.loaded.config.planner.maxUrlLength,
                    // D18: no dir_action, so Google Maps shows the route with its stops and a Start button
                    dirAction = false,
                )
            }
            val navigateUrl = shown?.let {
                MapsUrlBuilder.build(
                    route = it,
                    home = loc,
                    maxWaypoints = container.loaded.config.planner.maxWaypoints,
                    maxUrlLength = container.loaded.config.planner.maxUrlLength,
                    dirAction = true,
                )
            }
            val shownRoute = result.chosen ?: result.bestAttempt
            val alternatives = result.attempts
                .mapNotNull { it.route?.trailNames?.firstOrNull() }
                .distinct()
                .filterNot { it in (shownRoute?.trailNames ?: emptyList()) }
                .take(4)
            val rider = buildList {
                // D43: answer the tap FIRST. "Try a safer detour" widens the search, so the rider is
                // owed what that wider search found: a safer ride, or a plain statement that there
                // is none. Silence here is what made the button look broken.
                if (harder > 0) {
                    add(
                        if (result.chosen?.verdict?.hasNoKnownViolation == true) {
                            com.biketrailplanner.ui.RiderText.SEARCH_HARDER_FOUND
                        } else {
                            com.biketrailplanner.ui.RiderText.SEARCH_HARDER_EMPTY
                        },
                    )
                }
                com.biketrailplanner.ui.RiderText.refusalSentence(result)?.let { add(it) }
                if (result.requiresDetourAcceptance) add("The safe way is longer than the direct route.")
                if (result.usedFallback) add("Planned by the public router because your server was unreachable.")
            }.take(2).joinToString(" ").takeIf { it.isNotBlank() }
            _state.value = _state.value.copy(
                planning = false,
                planStartedAtMs = null,
                planningTrail = null,
                result = result,
                // D37: the ride on screen starts as the one the rotation chose; the rider can switch
                // to any of the other options without planning anything again.
                selectedRoute = shownRoute,
                planGeneration = _state.value.planGeneration + 1,
                // The wider search was for this run only: the next plan is the shipped one again.
                searchHarderAttempt = 0,
                riderMessage = rider,
                aiUsed = result.aiUsed,
                strategyNotes = result.strategyNotes,
                // The ride on screen is a new one, so it is no longer "a saved trip".
                adoptedSignature = null,
                savedTripNote = null,
                suggestions = emptyList(),
                suggestionNote = null,
                alternativeTrails = alternatives,
                chosenTrails = shownRoute?.trailNames ?: emptyList(),
                serverReachable = reachable,
                currentLocation = loc,
                locationKnown = loc != null,
                mapsUrl = confirmUrl,
                navigateUrl = navigateUrl,
                // The tap's job is done once the ride has been planned again from the new position.
                positionNote = null,
            )
        }
    }

    /** Records the ride in the rotation history (D6) and returns the URL for the launcher. */
    fun recordHandOff(navigate: Boolean): String? {
        val url = if (navigate) _state.value.navigateUrl else _state.value.mapsUrl
        url ?: return null
        val route = _state.value.selectedRoute ?: return null
        viewModelScope.launch {
            container.history.record(
                signature = route.signature,
                trailNames = route.trailNames,
                totalMiles = route.totalM / com.biketrailplanner.domain.Geo.METRES_PER_MILE,
                atEpochMs = System.currentTimeMillis(),
            )
            _state.value = _state.value.copy(historyCount = container.history.count())
        }
        // D35: stamp the ride so the app can tell the rider to stop their Velotric ride on return.
        // The ride is a round trip, so the point to come back to is the planning origin itself.
        val start = _state.value.planningOrigin ?: savedHome.point
        _state.value = _state.value.copy(
            handOffAtMs = System.currentTimeMillis(),
            handOffStartPoint = start,
            rideNudge = null,
            nudgeDismissed = false,
        )
        // D36: and arm the end-of-navigation watcher. It is persisted, because our process will be
        // killed while the rider is in Google Maps. A Velotric ride the rider already started is kept.
        container.rideWatch.save(RideWatch.arm(container.rideWatch.load(), System.currentTimeMillis()))
        // D51: a hand-off is also the start of a recording. The rider explores streets the plan never
        // chose, so the only truthful record of the ride is the one taken from GPS as it happens.
        container.startRideRecording()
        refreshWatchStatus()
        return url.url
    }

    /**
     * D26: use a saved ride. The trip was already worked out and paid for, so this draws its geometry,
     * builds the hand-off URL out of the waypoints it was saved with, and stamps it as used. No
     * Valhalla call, no model call - which is the whole point of the store.
     */
    fun useSuggestion(signature: String) {
        val saved = _state.value.suggestions.firstOrNull { it.signature == signature } ?: return
        val origin = _state.value.planningOrigin ?: savedHome.point
        val maxWaypoints = container.loaded.config.planner.maxWaypoints
        val maxUrlLength = container.loaded.config.planner.maxUrlLength
        val now = System.currentTimeMillis()
        val plan = saved.asPlanResult(
            origin,
            container.loaded.config.planner.unverifiedUngradedShare,
        )
        viewModelScope.launch {
            runCatching { container.routeStore.markUsed(saved.signature, now) }
            _state.value = _state.value.copy(
                // The screen renders one route from one shape, so the saved trip is published as the
                // route in the state; everything it shows comes from what was saved with it.
                result = plan,
                selectedRoute = plan.chosen,
                mapsUrl = MapsUrlBuilder.MapsUrl(
                    url = savedTripUrl(saved, origin, dirAction = false, maxWaypoints, maxUrlLength),
                    included = emptyList(),
                    dropped = emptyList(),
                    length = 0,
                    droppedSafetyCritical = false,
                ),
                navigateUrl = MapsUrlBuilder.MapsUrl(
                    url = savedTripUrl(saved, origin, dirAction = true, maxWaypoints, maxUrlLength),
                    included = emptyList(),
                    dropped = emptyList(),
                    length = 0,
                    droppedSafetyCritical = false,
                ),
                adoptedSignature = saved.signature,
                savedTripNote = RiderText.SavedTrips.adoptedLine(saved),
                suggestions = emptyList(),
                suggestionNote = null,
                aiUsed = false,
                strategyNotes = emptyList(),
                riderMessage = RiderText.SavedTrips.adoptedLine(saved),
                planGeneration = _state.value.planGeneration + 1,
                chosenTrails = saved.trailNames,
                positionNote = null,
            )
        }
    }

    /**
     * D23: "Find trails near here". Ask the live trail service what bike trails are near the rider,
     * merge the answer with the bundled set, and use the merged set for the next plan. The answer is
     * reported in rider language, including how strong a "nothing found" is (one mirror that found
     * nothing is not a verdict).
     */
    fun findTrailsNearHere() {
        if (_state.value.discoveryBusy) return
        _state.value = _state.value.copy(
            discoveryBusy = true,
            discoveryNote = RiderText.Discovery.CHECKING,
        )
        viewModelScope.launch {
            val fix = runCatching { locationProvider.freshFix() }.getOrNull() ?: locationProvider.lastFix()
            val good = fix?.takeIf { it.isGood(GOOD_FIX_MAX_AGE_S, GOOD_FIX_MAX_ACCURACY_M) }
            publishFix(fix)
            val origin = good?.point ?: _state.value.planningOrigin ?: savedHome.point
            val radiusKm = container.loaded.config.planner.trailRadiusKm
            // The bundled six AND the regional set are handed in, so a relation the app already ships
            // is reported as already covered instead of arriving twice under the same name. Sampling
            // the regional set for a new start area is real work, so it stays off the main thread.
            val bundled = container.loaded.network.trails +
                withContext(Dispatchers.Default) { container.regional(origin).trails }
            val answer = runCatching {
                container.discovery.discover(origin, radiusKm, forceRefresh = true, bundled = bundled)
            }
            answer.fold(
                onSuccess = { result ->
                    discovered = result
                    // D38: discovery added trails, so the closest-first list changes with it.
                    trailsNearBucket = null
                    refreshTrailsNear()
                    _state.value = _state.value.copy(
                        discoveryBusy = false,
                        discoveredTrails = result.trails.size,
                        discoveryNote = DiscoveryMerge.summary(result, System.currentTimeMillis()) +
                            ". " + RiderText.Discovery.whatHappensNext(result.trails.size),
                    )
                },
                onFailure = { error ->
                    // A failure changes nothing: the next plan is the bundled one, exactly as before.
                    _state.value = _state.value.copy(
                        discoveryBusy = false,
                        discoveryNote = RiderText.Discovery.failed(error),
                    )
                },
            )
        }
    }

    /**
     * SPEC section 7b item 4: re-read the tags of the closed ways from the OSM API and unlock a
     * segment as soon as the bicycle=no / access=no tags are gone.
     */
    fun refreshTrailData() {
        val ids = container.closedWayIds
        if (ids.isEmpty()) {
            _state.value = _state.value.copy(trailRefresh = "No closed trail segments are recorded, so there is nothing to re-check.")
            return
        }
        _state.value = _state.value.copy(refreshing = true, trailRefresh = "Checking ${ids.size} way(s) in the OpenStreetMap API...")
        viewModelScope.launch {
            val states = runCatching { container.closureChecker.check(ids) }.getOrElse { e ->
                _state.value = _state.value.copy(
                    refreshing = false,
                    trailRefresh = "Could not reach the OSM API: ${e.message}. The closure stays in place.",
                )
                return@launch
            }
            // D15: the closure is permanent, so this is a data-hygiene check only - nothing is
            // unlocked automatically and no reopening is promised.
            // Rider language only: the raw tags and version numbers do not belong on a screen.
            val closedNames = container.loaded.network.trails.filter { it.hasClosure }.map { it.name }
            val stillClosed = states.count { !it.usable }
            val message = when {
                states.all { !it.reachable } -> "Could not reach OpenStreetMap just now, so nothing changed."
                stillClosed > 0 -> closedNames.distinct().joinToString(", ") +
                    if (stillClosed == 1) " is still closed in OpenStreetMap." else " are still closed in OpenStreetMap."
                else -> closedNames.distinct().joinToString(", ") +
                    " no longer looks closed in OpenStreetMap. The app keeps routing around it until you change that record."
            }
            _state.value = _state.value.copy(refreshing = false, trailRefresh = message)
        }
    }

    fun onHandOffResult(message: String) {
        _state.value = _state.value.copy(handOffMessage = message)
    }

    /**
     * D36: with Notification access granted, the notification watcher owns the end-of-ride prompt, so
     * the location nudge must stay quiet (two prompts for one ride would be worse than either). The
     * location nudge is the fallback for when that grant - or the ability to post - is missing.
     */
    private fun primaryRideWatchAvailable(): Boolean =
        container.notificationAccessGranted() && container.notificationsEnabled()

    /** D36: what the Settings row shows about the watcher and its two grants. */
    private fun refreshWatchStatus() {
        val now = System.currentTimeMillis()
        val watch = container.rideWatch.load()
        _state.value = _state.value.copy(
            notificationAccessGranted = container.notificationAccessGranted(),
            notificationsEnabled = container.notificationsEnabled(),
            rideWatchArmed = RideWatch.isArmed(watch, now),
        )
        maybeSafetyNetReminder(watch, now)
    }

    /**
     * D36: the rider started a Velotric ride through the app, but no navigation was ever seen for it,
     * so the navigation check has nothing to watch. Remind them now (on opening the app) rather than
     * leaving the ride running in silence. The marker is cleared so this fires at most once per ride.
     */
    private fun maybeSafetyNetReminder(watch: RideWatch.State, now: Long) {
        if (!RideWatch.safetyNetReminder(watch, now)) return
        container.rideWatch.save(RideWatch.onRideReminded(watch))
        if (container.notificationsEnabled()) {
            container.postRideReminder()
        } else {
            // No notifications means no message is possible; the in-app bar is the reminder instead.
            _state.value = _state.value.copy(stopRideReminder = true)
        }
    }

    /** D36: the rider tapped "Start Velotric ride", so this ride can be reminded about on return. */
    fun onVelotricRideStarted() {
        val now = System.currentTimeMillis()
        container.rideWatch.save(RideWatch.onVelotricRideStarted(container.rideWatch.load(), now))
        refreshWatchStatus()
    }

    /**
     * D35: decide whether the rider should now be told to stop their Velotric ride. The rule itself
     * lives in [RideNudge] so it is testable on the JVM; this only feeds it the stamp and the fix.
     */
    private fun evaluateArrival(fix: LatLon?) {
        // D36: the watcher above takes precedence whenever it is available.
        if (primaryRideWatchAvailable()) {
            if (_state.value.rideNudge != null) _state.value = _state.value.copy(rideNudge = null)
            return
        }
        val s = _state.value
        val handOff = s.handOffAtMs?.let { at ->
            s.handOffStartPoint?.let { start -> RideNudge.HandOff(start, at) }
        }
        val nudge = RideNudge.decide(
            handOff = handOff,
            nowEpochMs = System.currentTimeMillis(),
            currentFix = fix,
            dismissed = s.nudgeDismissed,
        )
        // Only react when the prompt appears or disappears: while it is up, a new fix must not churn
        // the state on every update.
        if ((nudge != null) != (s.rideNudge != null)) {
            _state.value = s.copy(rideNudge = nudge)
        }
    }

    /** D35: the rider has seen "stop your Velotric ride" and does not want it again this ride. */
    fun dismissRideNudge() {
        _state.value = _state.value.copy(nudgeDismissed = true, rideNudge = null, stopRideReminder = false)
    }

    companion object {
        /** D22 rule 2: a fix older than this, or vaguer than this, is never planned from. */
        private const val GOOD_FIX_MAX_AGE_S = 60L
        private const val GOOD_FIX_MAX_ACCURACY_M = 50.0

        /** Saving a spot this close to the bundled home keeps the bundled label, which is better. */
        private const val KEEP_BUNDLED_LABEL_M = 250.0

        private val SAVED_HOME_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
    }
}

/**
 * D26 (docs/ROUTE-STORE.md): the saved-trip step, in one place so the screen and the tests run the
 * same code. It runs BEFORE any planning work, it makes no network call, and it never forces a saved
 * trip on the rider: the screen keeps its own "Plan ride" action either way.
 */
object SavedTripsFirst {

    data class Step(
        /** Every trip saved for this request, newest used first. */
        val matches: List<ReusableRoute>,
        /** The ones worth showing, at most [PlanPolicy.DEFAULT_MAX_SUGGESTIONS]. Empty when the app
         *  should simply plan a new ride. */

        val suggestions: List<ReusableRoute>,
        /** True when a plan may run. False means "publish the saved trips and do nothing else". */
        val mayPlan: Boolean,
        val note: String?,
    )

    /**
     * @param riderAskedForNew true when the rider has tapped the action that plans a new ride. A saved
     *   trip is then still offered, but it can never be the reason a plan does not happen.
     */
    suspend fun look(
        store: RouteStore?,
        origin: LatLon,
        targetMiles: Double,
        fingerprint: String?,
        riderAskedForNew: Boolean,
    ): Step {
        if (store == null) return Step(emptyList(), emptyList(), true, null)
        val matches = runCatching { store.matching(origin, targetMiles, fingerprint) }.getOrDefault(emptyList())
        val decision = PlanPolicy.decide(matches)
        val suggestions = (decision as? PlanPolicy.Decision.Suggest)?.routes.orEmpty()
        return Step(
            matches = matches,
            suggestions = suggestions,
            mayPlan = mayPlan(decision, riderAskedForNew),
            note = note(suggestions, matches),
        )
    }

    /** "More than two saved trips for this request" suggests them first; anything else plans. */
    fun mayPlan(decision: PlanPolicy.Decision, riderAskedForNew: Boolean): Boolean =
        riderAskedForNew || decision is PlanPolicy.Decision.PlanNew

    private fun note(suggestions: List<ReusableRoute>, matches: List<ReusableRoute>): String? = when {
        suggestions.isEmpty() -> null
        else -> RiderText.SavedTrips.intro(suggestions.size) +
            (matches.size - suggestions.size).takeIf { it > 0 }?.let { " (" + it + " more saved from here)" }.orEmpty()
    }
}

/**
 * The hand-off URL for a SAVED trip, built from the waypoints it was saved with. It is the same URL
 * the fresh plan produced, because the saved waypoints are the ones the fresh plan put in its own
 * URL, in the same order (see the save path in [PlanViewModel]).
 */
internal fun savedTripUrl(
    saved: ReusableRoute,
    origin: LatLon,
    dirAction: Boolean,
    maxWaypoints: Int,
    maxUrlLength: Int,
): String {
    val waypoints = ArrayList<MapsUrlBuilder.Waypoint>()
    var url = MapsUrlBuilder.buildUrl(origin, waypoints, dirAction)
    for (point in saved.waypoints) {
        if (waypoints.size >= maxWaypoints) break
        val candidate = ArrayList(waypoints)
        candidate.add(MapsUrlBuilder.Waypoint(point, 1, "saved stop", "saved stop", false, 0.0))
        val candidateUrl = MapsUrlBuilder.buildUrl(origin, candidate, dirAction)
        if (candidateUrl.length > maxUrlLength) break
        waypoints.add(candidate.last())
        url = candidateUrl
    }
    return url
}

/**
 * A saved trip drawn as the route the screens and the map already know how to render.
 *
 * Only what was saved is claimed: the summary (distances, grades, named stretches) is the one stored
 * with the trip, and the geometry is the stored round trip. Per-street colours are NOT claimed,
 * because colouring the shape needs the per-edge trace the fresh plan had and a saved row does not
 * keep - so the shape is drawn as one uncoloured line and the bar above it carries the real numbers.
 */
internal fun ReusableRoute.asPlannedRoute(
    /**
     * The coverage gate to judge this saved trip by. It MUST come from the caller's config
     * (`planner.unverified_ungraded_share`) rather than from a constant here: the saved trip is the
     * one path that rebuilds a verdict without a [com.biketrailplanner.data.PlannerConfig] in hand,
     * and it silently used the default while the planner used the knob. Defaulted only so that tests
     * and callers with no config still compile.
     */
    minGradedShare: Double = com.biketrailplanner.domain.DEFAULT_MIN_GRADED_SHARE,
): PlannedRoute {
    val commuteOneWayM = maxOf(0.0, totalM - trailRoundM) / 2
    val commuteOneWayS = maxOf(0.0, (timeS - trailRoundM / 5.0) / 2)
    val shape = points.ifEmpty { listOf(turnaround, turnaround) }
    val rejected = byGrade[Grade.REJECTED] ?: 0.0
    val breakdown = GradeBreakdown(
        totalM = breakdownTotalM,
        byGrade = byGrade,
        gradedM = gradedM,
        ungradedM = ungradedM,
        rejectedM = rejected,
    )
    val namedRows: List<NamedSegment> = this.named
    // One rule, one implementation. The verdict is DERIVED from the rebuilt breakdown with the same
    // function the planner uses; it is never read back from a stored boolean, so a saved trip cannot
    // carry an older policy's answer. `d10Verdict` also replaces the hand-rolled `PolicyVerdict(...)`
    // that used to live here with the drifted `ungradedM > 0.0` test.
    val verdict = com.biketrailplanner.domain.d10Verdict(breakdown, minGradedShare = minGradedShare)
    return PlannedRoute(
        signature = signature,
        trailNames = trailNames,
        accessPoint = com.biketrailplanner.domain.AccessPoint(
            trailName = trailNames.firstOrNull().orEmpty(),
            osmRelationId = 0L,
            osmNodeId = accessNode,
            point = turnaround,
            onRoad = null,
            highway = null,
            sharesTrailNode = false,
            straightLineMFromHome = 0.0,
            probeRouteKmFromHome = null,
        ),
        turnaround = turnaround,
        commute = com.biketrailplanner.data.RouteResult(
            // The map colours a route by grade from `commute.points` plus `roadSegments`, and a saved
            // row does not keep the per-edge trace those colours need. So the whole stored shape is
            // handed over as one run: the rider sees the ride's real outline, and the numbers above
            // the map stay the stored ones. It is never drawn as trail and never drawn as safe.
            shape = PolylineCodec.encode(shape),
            points = shape,
            lengthM = commuteOneWayM,
            timeS = commuteOneWayS,
            maneuvers = emptyList(),
            baseUrl = "",
            usedFallback = false,
        ),
        trailLegs = emptyList(),
        trailOneWayM = trailRoundM / 2,
        trailRoundTripM = trailRoundM,
        targetMiles = targetMiles,
        capped = false,
        capReason = null,
        roundTrip = shape,
        segments = emptyList(),
        roadSegments = emptyList(),
        breakdown = breakdown,
        verdict = verdict,
        named = namedRows,
        cautions = namedRows.filter { it.grade == Grade.D },
        ungraded = namedRows.filter { it.grade == Grade.UNGRADED },
        rejections = namedRows.filter { it.grade == Grade.E || it.grade == Grade.REJECTED },
        detours = emptyList(),
        detourOneWayM = 0.0,
        safetyNotes = verdict.violations,
        notes = emptyList(),
    )
}

/** A saved trip as the plan the result screen renders: PLAN_OK, no warning card, no refusal. */
internal fun ReusableRoute.asPlanResult(
    origin: LatLon,
    minGradedShare: Double = com.biketrailplanner.domain.DEFAULT_MIN_GRADED_SHARE,
): PlanResult {
    val drawn = asPlannedRoute(minGradedShare)
    return PlanResult(
        targetMiles = targetMiles,
        home = origin,
        chosen = drawn,
        options = listOf(drawn),
        bestAttempt = null,
        attempts = emptyList(),
        warnings = emptyList(),
        dataGaps = emptyList(),
        baseUrlUsed = null,
        usedFallback = false,
        safeRouteFound = true,
        shortestTotalM = totalM,
        detourReferenceM = totalM,
        outcome = PlanOutcome.PLAN_OK,
        aiUsed = false,
        strategyNotes = emptyList(),
    )
}
