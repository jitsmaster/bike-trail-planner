package com.biketrailplanner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.biketrailplanner.data.AiKeyStore
import com.biketrailplanner.data.AndroidAssetReader
import com.biketrailplanner.data.AppDatabase
import com.biketrailplanner.data.AssetReader
import com.biketrailplanner.data.CachedRouteService
import com.biketrailplanner.data.DeepSeekClient
import com.biketrailplanner.data.DeepSeekHttpCall
import com.biketrailplanner.data.DiscoveryMerge
import com.biketrailplanner.data.DiscoveryResult
import com.biketrailplanner.data.EncryptedAiKeyStore
import com.biketrailplanner.data.OverpassClient
import com.biketrailplanner.data.AddressLookup
import com.biketrailplanner.data.PrefsStrategyCache
import com.biketrailplanner.data.PanelSizeStore
import com.biketrailplanner.data.RegionalMerge
import com.biketrailplanner.data.RegionalTrailLoader
import com.biketrailplanner.data.RideRecordingStore
import com.biketrailplanner.data.RideFixJournal
import com.biketrailplanner.data.RideSimulation
import com.biketrailplanner.data.RideWatchStore
import com.biketrailplanner.data.RoomDiscoveryCache
import com.biketrailplanner.data.RoomRideHistory
import com.biketrailplanner.data.RoomRideTrackStore
import com.biketrailplanner.data.RoomRouteCacheStore
import com.biketrailplanner.data.RoomRouteStore
import com.biketrailplanner.data.RouteService
import com.biketrailplanner.data.StrategyCache
import com.biketrailplanner.data.StrategyCacheKey
import com.biketrailplanner.data.TrailDiscovery
import com.biketrailplanner.data.TrailRepository
import com.biketrailplanner.data.ValhallaClient
import com.biketrailplanner.domain.CachedStrategist
import com.biketrailplanner.domain.DataFingerprint
import com.biketrailplanner.domain.DeepSeekStrategist
import com.biketrailplanner.domain.FallbackStrategist
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Planner
import com.biketrailplanner.domain.PolylineCodec
import com.biketrailplanner.domain.RecordedRide
import com.biketrailplanner.domain.RideHistoryStore
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.RideTrackGrader
import com.biketrailplanner.domain.RideTrackStore
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.domain.RouteStore
import com.biketrailplanner.domain.RouteStrategist
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.ValidatingStrategist
import com.biketrailplanner.nav.RideRecordingService
import com.biketrailplanner.nav.RideReminder
import kotlinx.serialization.json.Json

/** Manual dependency wiring: one small container, no framework. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val assets: AssetReader = AndroidAssetReader(appContext.assets)
    private val prefs = appContext.getSharedPreferences("bike-trail-planner", Context.MODE_PRIVATE)

    /** Way ids an OSM re-check proved rideable again (SPEC section 7b item 4). */
    fun reopenedWayIds(): Set<Long> =
        prefs.getStringSet(KEY_REOPENED, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun markReopened(wayId: Long) {
        prefs.edit().putStringSet(KEY_REOPENED, (reopenedWayIds() + wayId).map { it.toString() }.toSet()).apply()
        reload()
    }

    var loaded: TrailRepository.Loaded = TrailRepository(assets, json).load(reopenedWayIds())
        private set

    fun reload() {
        loaded = TrailRepository(assets, json).load(reopenedWayIds())
        grader = SafetyGrader(loaded.limits, loaded.traffic)
    }

    var grader = SafetyGrader(loaded.limits, loaded.traffic)
        private set

    val closureChecker = com.biketrailplanner.data.OsmClosureChecker(json = json)

    /** The regional trail file, decoded at most once per process (docs/REGIONAL-TRAILS.md). */
    private val regionalLoader = RegionalTrailLoader(assets, json)

    /**
     * Access points freshly sampled per ~1 km origin bucket; the parse itself is cached inside.
     *
     * Bounded on purpose: one entry is the whole regional set rebuilt for one origin (186 trails,
     * ~14k points), which is about a megabyte, so the cache keeps only the few areas a rider actually
     * plans from rather than holding every place they have ever opened the app.
     */
    private val regionalCache = object : LinkedHashMap<String, RegionalTrailLoader.Loaded>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RegionalTrailLoader.Loaded>?): Boolean =
            size > REGIONAL_CACHE_BUCKETS
    }

    val closedWayIds: List<Long> get() = loaded.network.trails.flatMap { it.closures }.map { it.osmWayId }
    val database: AppDatabase = AppDatabase.build(appContext)

    /** docs/ROUTE-STORE.md: the trips the app has already worked out, keyed by start area and mileage. */
    val routeStore: RouteStore = RoomRouteStore(database.savedRouteDao())

    /** D51: the rides the rider actually recorded, graded the same way a planned route is. */
    val rideTracks: RideTrackStore by lazy { RoomRideTrackStore(database.rideTrackDao(), dataFingerprint) }

    /**
     * D36: the ride watch. Armed when a ride is handed to Google Maps and read by the notification
     * listener in `nav/RideWatchService.kt`, which runs in this same process but far from the UI.
     */
    val rideWatch = RideWatchStore(appContext)

    /** D51: whether a ride is being recorded right now (persisted, so it survives a process death). */
    val rideRecording = RideRecordingStore(appContext)

    /** D53: how much of the frame the bottom panel covers, remembered between launches. */
    val panelSize = PanelSizeStore(appContext)

    /**
     * D60: postal addresses for tapped points, via OpenStreetMap's Nominatim. It refuses to ask at all
     * unless the rider has already allowed public servers ([allowPublicFallback]) - the same disclosure
     * they agreed to for routing, never a new one.
     */
    val addressLookup = AddressLookup(allowPublicLookup = allowPublicFallback)

    /**
     * D36: whether the rider has granted Notification access. Android has no narrower grant, so this
     * is the switch that decides whether the end-of-navigation prompt is available at all - without
     * it the app falls back to the return-to-start nudge.
     */
    fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    /** D36: whether the app may post notifications at all (runtime permission on API 33+). */
    fun notificationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** D36: post the "Ride finished?" reminder (the safety net, from the app itself). */
    fun postRideReminder() = RideReminder.post(appContext)

    /**
     * Hash of the safety data a grade was computed from (docs/ROUTE-STORE.md section 3.4). A saved
     * trip carrying a different fingerprint is still reusable - its geometry is unchanged - but its
     * verdict must be recomputed locally before it is offered as ready to ride.
     */
    val dataFingerprint: String by lazy {
        DataFingerprint.of(
            DataFingerprint.ASSETS.mapNotNull { name -> runCatching { assets.read(name) }.getOrNull() },
        )
    }
    val history: RideHistoryStore = RoomRideHistory(database.rideHistoryDao())
    val client = ValhallaClient(
        primaryBase = BuildConfig.VALHALLA_BASE_URL,
        publicBase = BuildConfig.VALHALLA_PUBLIC_URL,
        json = json,
    )
    val service: RouteService = CachedRouteService(client, RoomRouteCacheStore(database.routeCacheDao()), json)

    /**
     * D12: AUTOMATIC since 2026-09-15 - the public Valhalla server is used whenever the Mac is
     * unreachable, with a visible badge. It stays behind this one flag so the user can make it
     * opt-in again later, and the Plan screen switch still turns it off.
     */
    /** D20: remember the distance the rider used last, defaulting to 10 mi on first run. */
    var lastTargetMiles: Int
        get() = prefs.getInt(KEY_TARGET, DEFAULT_TARGET_MILES)
        set(value) {
            prefs.edit().putInt(KEY_TARGET, value.coerceIn(1, 200)).apply()
        }

    /** Total rideable trail in the bundled network, used to be honest about large targets. */
    val networkTrailMiles: Double
        get() = loaded.network.trails.sumOf { it.rideableM } / 1609.344

    var allowPublicFallback: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FALLBACK, value).apply()
        }

    /**
     * D23 answer (a): the bundled regional trail set, sampled around [origin].
     *
     * The 0.39 MB file is decoded once per process ([RegionalTrailLoader.rows]); the per-origin work
     * is the distance measurement and the access-point sampling, and it is cached per ~1 km bucket
     * ([RouteKey.bucket]) so a second plan from the same place does none of it. A file that cannot be
     * read is not a reason to refuse to plan: the app then simply plans with the bundled six, which
     * is what it did before this set existed.
     */
    fun regional(origin: LatLon): RegionalTrailLoader.Loaded =
        regionalCache.getOrPut(RouteKey.bucket(origin)) {
            runCatching {
                regionalLoader.load(origin, loaded.network.trails)
            }.getOrElse { RegionalTrailLoader.Loaded.NONE }
        }

    /** Live trail discovery (D23): the "find trails near here" half. */
    val discovery: TrailDiscovery = TrailDiscovery(
        client = OverpassClient(json = json),
        cache = RoomDiscoveryCache(database.discoveredTrailDao()),
    )

    /** The rider's DeepSeek key and model choice: encrypted preferences, never source or a log. */
    val aiKeys: AiKeyStore = EncryptedAiKeyStore(appContext)

    private val strategyCache: StrategyCache = PrefsStrategyCache(appContext)

    /**
     * D27: the AI seam, in its full production shape (docs/AI-PLANNING.md section 7): the model's
     * reply is validated, cached by request, and backed by the app's own deterministic order so a
     * missing key, a failed call or a hallucinated trail name can never make the plan worse.
     *
     * The cache key is the same request key the saved trips use (origin bucket + mileage), so
     * replanning the same request never pays for a second model call.
     */
    fun strategist(origin: LatLon): RouteStrategist {
        // Read once here. The key is never logged, never stored anywhere but the encrypted prefs.
        val model = aiKeys.model
        val ai: RouteStrategist = DeepSeekStrategist(
            client = DeepSeekClient(DeepSeekHttpCall(aiKeys.deepSeekKey, model), json = json),
            keyStore = aiKeys,
            model = model,
        )
        return FallbackStrategist(
            CachedStrategist(ValidatingStrategist(ai), strategyCache) { context ->
                StrategyCacheKey.of(origin, context.targetMiles)
            },
        )
    }

    /**
     * The planner for one request. The trail set it is given is the MERGED one:
     *
     *   the bundled six (verified)  +  the bundled regional set (unverified)  [ +  live discovery ]
     *
     * The last part is only added when the rider has asked for it ("Find trails near here"); the rest
     * of the time nothing about a plan depends on a volunteer server. The near-home behaviour is
     * unchanged in the one way that matters: which trails matter is decided by the existing radius
     * filter and the existing approach measurement, and no second rule was added here.
     */
    /**
     * [configOverride] is for a deliberate, one-off search: "Try a safer detour" re-plans the same
     * request with a wider D13 budget (docs/SPEC.md section 7c). Everything else - the network, the
     * access points, the grader - is the shipped one.
     */
    fun planner(
        origin: LatLon,
        discovered: DiscoveryResult? = null,
        configOverride: com.biketrailplanner.data.PlannerDto? = null,
    ): Planner {
        val regional = regional(origin)
        val withRegional = RegionalMerge.network(loaded.network, regional)
        val network = if (discovered != null) DiscoveryMerge.network(withRegional, discovered) else withRegional
        val accessPoints = if (discovered != null) {
            DiscoveryMerge.accessPoints(RegionalMerge.accessPoints(loaded.accessPoints, regional), discovered)
        } else {
            RegionalMerge.accessPoints(loaded.accessPoints, regional)
        }
        return Planner(
            network = network,
            accessPoints = accessPoints,
            grader = grader,
            client = service,
            config = configOverride ?: loaded.config.planner,
            home = loaded.config.home,
            history = history,
            strategist = strategist(origin),
            routeStore = routeStore,
        )
    }

    fun isRideRecording(): Boolean {
        val state = rideRecording.load()
        if (RideRecordingStore.isReallyRecording(state, RideRecordingService.isRecordingNow)) return true
        // D57: a persisted flag with no live recorder means the recording died with its process. Clear
        // it rather than telling the rider their ride is being logged - it is not, and they would lose
        // the ride believing otherwise.
        if (state.active) {
            // D62: a stale flag means a recording outlived its process. If it left fixes on disk, hand
            // them back to the service to save - Stop is the very path a manual stop takes, so the ride
            // is recovered rather than discarded. Only a recording with nothing on disk is dropped.
            if (RideFixJournal(appContext).hasFixes()) {
                RideRecordingService.stop(appContext)
            } else {
                rideRecording.clear()
            }
        }
        return false
    }

    /** D53: remember the rider's chosen panel size. */
    fun savePanelFraction(fraction: Float) = panelSize.save(fraction)

    /** D51: start the foreground recorder. Ignored when a ride is already being recorded. */
    fun startRideRecording() = RideRecordingService.start(appContext)

    /** D51: stop and save the recording (the notification's Stop action does the same thing). */
    fun stopRideRecording() = RideRecordingService.stop(appContext)

    /**
     * D51 debug tool (docs/RIDE-RECORDER.md section 4): arm the in-app simulator for the next
     * recording, so the whole recorder can be exercised on the phone without riding. Release builds
     * ignore this, because [BuildConfig.DEBUG] is false there.
     */
    fun enableRideSimulation() {
        if (BuildConfig.DEBUG) RideSimulation.enable(appContext)
    }

    /**
     * D51: trace the recorded shape with the same Valhalla call the planner uses, grade it with the
     * shipped ladder, store it as a saved ride, and register it for the ride rotation (D6). A trace
     * that fails stores the ride UNGRADED rather than dropping it: the rider still rode it, and the
     * ride is never called safe either way.
     */
    suspend fun saveRecordedRide(ride: RecordedRide): RideTrack {
        // D12: a recorded ride has no route behind it, so it is traced with the same public-fallback
        // policy planning uses. Without this a recorder away from the tailnet could never be graded at
        // all, even when planning on the very same server worked (docs/RIDE-RECORDER.md section 7).
        val shape = PolylineCodec.encode(ride.points)
        val trace = runCatching {
            service.trace(shape, BuildConfig.VALHALLA_BASE_URL, allowFallback = allowPublicFallback)
        }.getOrNull()
        val track = RideTrackGrader.grade(
            ride = ride,
            trace = trace,
            grader = grader,
            fingerprint = dataFingerprint,
            currentFingerprint = dataFingerprint,
            nowMs = System.currentTimeMillis(),
        )
        rideTracks.save(track)
        val names = track.named.map { it.name }.filter { it.isNotBlank() }.distinct().take(3)
        history.record(
            signature = track.signature,
            trailNames = names,
            totalMiles = track.distanceMiles,
            atEpochMs = track.endedAtMs,
        )
        return track
    }

    companion object {
        /** How many ~1 km start areas keep their sampled regional access points in memory. */
        private const val REGIONAL_CACHE_BUCKETS = 4

        private const val KEY_FALLBACK = "allow_public_fallback"
        private const val KEY_REOPENED = "reopened_way_ids"
        private const val KEY_TARGET = "last_target_miles"
        const val DEFAULT_TARGET_MILES = 10
    }
}
