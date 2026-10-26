package com.biketrailplanner.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.biketrailplanner.AppContainer
import com.biketrailplanner.BikeTrailPlannerApp
import com.biketrailplanner.BuildConfig
import com.biketrailplanner.MainActivity
import com.biketrailplanner.R
import com.biketrailplanner.data.AndroidLocationSource
import com.biketrailplanner.data.LocationSource
import com.biketrailplanner.data.RideRecordingStore
import com.biketrailplanner.data.RideFixJournal
import com.biketrailplanner.data.RideRawArchive
import com.biketrailplanner.data.RideSimulation
import com.biketrailplanner.data.ScriptedLocationSource
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RecordedRide
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideRecorder
import com.biketrailplanner.domain.RideTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * D51 (docs/RIDE-RECORDER.md): the in-app ride recorder.
 *
 * A FOREGROUND service, so it keeps collecting GPS while the rider is in Google Maps or Velotric -
 * which is exactly why no `ACCESS_BACKGROUND_LOCATION` is needed, and why the D35/D36 "assist, never
 * background surveillance" stance is preserved. The recording notification is visible, ongoing, and
 * carries the Stop control; the rider can always see that a ride is being recorded.
 *
 * The recorder itself is the pure [RideRecorder]; this class only owns the Android lifecycle and the
 * notification. On stop the track is traced and graded through the same Valhalla + `SafetyGrader`
 * path a planned route uses, then stored as a [RideTrack].
 */
class RideRecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One thread for everything that touches [recorder]. Three things reach it - a Stop tap on the
     * main thread, the auto-stop computed from a fix, and the time-cap timer - and they must not
     * read and write the accumulator at the same time.
     */
    private val recordingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val recorder = RideRecorder()
    private var store: RideRecordingStore? = null
    /** D62: the fixes on disk, so a killed process cannot take the ride with it. */
    private var journal: RideFixJournal? = null
    /** D66: the same fixes, kept AFTER the ride is saved, so nothing the app filtered is lost. */
    private var rawArchive: RideRawArchive? = null
    private var container: AppContainer? = null
    private var collection: Job? = null
    private var stopping = false
    private var startedForeground = false
    private var lastNotifiedAtMs = 0L
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RideRecordingStore(this)
        journal = RideFixJournal(this)
        rawArchive = RideRawArchive(File(filesDir, RideRawArchive.DIR_NAME))
        container = (application as? BikeTrailPlannerApp)?.container
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAndSave()
            // D62: a sticky restart carries no intent. Resume ONLY if there is a journal to resume;
            // otherwise a restart after the ride already ended would start a phantom recording.
            null -> if (journal?.readAll().orEmpty().isNotEmpty()) startRecording() else stopSelf()
            else -> startRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        // Consume the debug-simulator arm FIRST, even if a recording is already running: an armed tap
        // that is ignored must not silently turn the NEXT real recording into a synthetic one.
        val simulate = BuildConfig.DEBUG && RideSimulation.consumeEnabled(this)
        if (recorder.isRecording) return
        val now = System.currentTimeMillis()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(null), type)
        }.onFailure { Log.w(TAG, "cannot start a location foreground service: ${it.message}") }.isSuccess
        if (!started) {
            store?.clear()
            stopSelf()
            return
        }
        startedForeground = true
        // D62: a journal left behind means a previous process died mid-recording. Replay it, so the ride
        // continues from where it was instead of starting again at zero miles.
        val resuming = store?.load()?.active == true
        val resumed = if (resuming) journal?.readAll().orEmpty() else emptyList()
        // D66: a journal left behind by a ride that was never saved is NOT this ride's raw material -
        // appending to it would splice two rides into one, and a later resume would replay the older
        // one as if it had just happened. Measured on a phone 2026-10-25: a save that failed leaves the
        // journal in place, so the next ride appended its first fix to yesterday's file.
        if (!resuming && journal?.hasFixes() == true) {
            // D66: archive it rather than throw it away. Those fixes are places somebody rode, even if
            // the ride that produced them was never stored; the cost of keeping them is one text file.
            val stale = journal?.readAll().orEmpty()
            val kept = rawArchive?.archive(stale, stale.firstOrNull()?.atEpochMs ?: now)
            Log.w(TAG, "stale journal from an unsaved ride: ${stale.size} fixes archived to ${kept?.absolutePath}")
            journal?.clear()
        }
        recorder.start(resumed.firstOrNull()?.atEpochMs ?: now)
        if (resumed.isNotEmpty()) {
            resumed.forEach(recorder::onFix)
            Log.i(TAG, "resumed from ${resumed.size} journalled fixes")
        }
        // D64: the map draws the recording from here on. A resumed ride appears on the map at once,
        // rather than growing from nothing as if the first hour had not happened.
        container?.liveRide?.publish(recorder.snapshot(now).points)
        // D57: the UI asks whether a ride is being recorded, and the answer has to be about THIS
        // process. The persisted flag alone lies after a process death - see the companion.
        isRecordingNow = true
        store?.save(RideRecordingStore.State(active = true, startedAtMs = now, paused = false))
        collection = scope.launch(recordingDispatcher) {
            source(simulate).fixes()
                .catch { e -> Log.w(TAG, "location stream failed: ${e.message}") }
                .collect { fix ->
                    // D62: write it down BEFORE anything that could throw. A fix that has reached the
                    // disk cannot be lost with the process; a fix only in memory can.
                    journal?.append(fix)
                    recorder.onFix(fix)
                    // D61: one line per fix. A recording that starves while the rider is in Google Maps
                    // must leave evidence in logcat, instead of arriving as a silently zero-mile ride.
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "fix ${fix.point.lat},${fix.point.lon} acc=${fix.accuracyM} t=${fix.atEpochMs}")
                    }
                    val at = System.currentTimeMillis()
                    val snapshot = recorder.snapshot(at)
                    // D64: the map draws it as it happens. Published per fix, but only when the
                    // recorder actually accepted a new point - a jitter fix is not new geometry.
                    container?.liveRide?.publish(snapshot.points)
                    if (at - lastNotifiedAtMs >= NOTIFY_EVERY_MS) {
                        lastNotifiedAtMs = at
                        withContext(Dispatchers.Main) { updateNotification(snapshot) }
                    }
                }
        }
    }

    /**
     * Where this recording's fixes come from. In a DEBUG build, a sim-armed recording replays a
     * synthetic track through this same service instead of the GPS provider; a release build can never
     * do this, because `BuildConfig.DEBUG` is false there. See docs/RIDE-RECORDER.md section 4.
     */
    private fun source(simulate: Boolean): LocationSource {
        if (simulate) {
            val origin = lastKnownPoint() ?: RideSimulation.DEFAULT_ORIGIN
            Log.i(TAG, "SIMULATED recording from $origin")
            return ScriptedLocationSource(
                RideSimulation.track(origin, System.currentTimeMillis()),
                realtime = true,
                intervalMs = RideSimulation.EMIT_EVERY_MS,
            )
        }
        return AndroidLocationSource(this)
    }

    /** The last fix the phone has, so a simulated ride runs over real roads the tracer can match. */
    @SuppressLint("MissingPermission")
    private fun lastKnownPoint(): LatLon? {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val location = providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .firstOrNull()
        return location?.let { LatLon(it.latitude, it.longitude) }
    }

    /**
     * Finish the ride, save it, then stop. The save is a network trace plus a DB write, so it runs in
     * the service's own scope and the service only stops once it has finished; the rider sees the
     * result notification either way, so a failed trace is never silent.
     *
     * Every call is pumped onto [recordingDispatcher], the same single thread the fixes arrive on, so a
     * Stop tap and an auto-stop can never touch the recorder at the same time.
     */
    private fun stopAndSave() {
        scope.launch(recordingDispatcher) { finishAndSave() }
    }

    private fun finishAndSave() {
        if (stopping) return
        stopping = true
        val journalled = journal?.readAll().orEmpty()
        if (!startedForeground && !recorder.isRecording && journalled.isEmpty()) {
            // Nothing is running and nothing was ever written down: there is no ride to save.
            store?.clear()
            stopSelf()
            return
        }
        collection?.cancel()
        val nowMs = System.currentTimeMillis()
        // D64: the live line ends where the recording ended. The finished ride is drawn from the store
        // once it is saved, so keeping the live copy would draw the same line twice.
        container?.liveRide?.clear()
        // D62: the in-memory recorder is the fast path; the journal is the truth after a process death.
        // This is the branch that used to lose a 16-mile ride - the process had been killed, so the
        // recorder was empty and the ride was discarded as "nothing was recorded".
        val ride = recorder.finish(nowMs) ?: rideFromJournal(journalled, nowMs)
        // D66: the rider's one hard requirement before a real trip - every place they went is written
        // down. The journal holds every fix exactly as it arrived, including the ones the recorder
        // rejected and the ones no tracer will ever match; copy it somewhere permanent BEFORE the save
        // attempt, so the archive does not depend on the trace, the network or the database.
        val archived = rawArchive?.archive(journalled, journalled.firstOrNull()?.atEpochMs ?: nowMs)
        if (archived != null) {
            Log.i(TAG, "raw fixes archived: ${archived.absolutePath} (${journalled.size} fixes)")
        } else if (journalled.isEmpty()) {
            Log.w(TAG, "no journalled fixes to archive")
        }
        if (ride == null && journalled.isNotEmpty()) Log.w(TAG, "journal held ${journalled.size} fixes but no ride could be built")
        isRecordingNow = false
        store?.clear()
        scope.launch {
            val saved: RideTrack? = ride?.let { recorded ->
                // D55: anything with a fix is kept, however short. The rider pressed record and rode.
                runCatching { container?.saveRecordedRide(recorded) }
                    .onFailure { Log.w(TAG, "could not save the recorded ride: ${it.message}") }
                    .getOrNull()
            }
            // Only once the ride is safely in the database: until then the journal is the only copy.
            if (saved != null) journal?.clear()
            withContext(Dispatchers.Main) {
                when {
                    saved != null -> notifyResult("Ride saved", savedNotificationText(saved))
                    ride != null -> notifyResult("Ride not saved", "The ride was recorded but could not be saved.")
                    else -> notifyResult("Ride not saved", "No GPS fix was recorded, so there was nothing to save.")
                }
                if (startedForeground) ServiceCompat.stopForeground(this@RideRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Rebuild the ride from the journal alone - the path taken when the recording outlived its process. */
    private fun rideFromJournal(fixes: List<RideFix>, endedAtMs: Long): RecordedRide? {
        if (fixes.isEmpty()) return null
        val fresh = RideRecorder()
        fresh.start(fixes.first().atEpochMs)
        fixes.forEach(fresh::onFix)
        return fresh.finish(endedAtMs)
    }

    override fun onDestroy() {
        collection?.cancel()
        scope.cancel()
        isRecordingNow = false
        container?.liveRide?.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifications

    private fun buildNotification(snapshot: RecordedRide?): Notification {
        val text = if (snapshot == null || !snapshot.hasTrack) {
            "Recording your ride\u2026"
        } else {
            "Recording your ride \u00b7 " + miles(snapshot.distanceM) + " so far"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording your ride")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Stop", stopIntent())
            .build()
    }

    private fun updateNotification(snapshot: RecordedRide) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
    }

    private fun notifyResult(title: String, text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                RESULT_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build(),
            )
        }
    }

    private fun savedNotificationText(track: RideTrack): String {
        // A recording too short to draw is still saved; say what happened rather than "0.0 mi".
        if (!track.hasTrack) return "Saved to Past rides \u00b7 too short to map"
        val distance = miles(track.distanceM)
        return if (track.ungradedShare > 0.0) {
            "$distance recorded \u00b7 ${(track.ungradedShare * 100).toInt()}% not measured"
        } else {
            "$distance recorded"
        }
    }

    private fun miles(metres: Double): String =
        String.format(Locale.US, "%.1f mi", Geo.metresToMiles(metres))

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, RideRecordingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Ride recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows that a ride is being recorded, with a Stop control."
            },
        )
    }

    companion object {
        const val ACTION_START = "com.biketrailplanner.action.RECORDING_START"
        const val ACTION_STOP = "com.biketrailplanner.action.RECORDING_STOP"

        /**
         * D57: whether a recording is running *in this process*, which is the only thing that can
         * answer the question honestly.
         *
         * `RideRecordingStore` is persisted so a restarted service can tell it was mid-recording, but
         * it is the wrong source of truth for the UI: the service is `START_NOT_STICKY`, so if the
         * process is killed while recording the flag stays `active` and the app goes on showing
         * *"Recording your ride"* with **nothing recording at all** - the rider believes their ride is
         * being logged, and no ride is ever saved. Measured on a phone 2026-10-11: `active=true`, no
         * `RideRecordingService` in `dumpsys activity services`, and the recording banner up.
         *
         * A process-static is exact: it is set the moment the recorder starts, cleared when it stops,
         * and reset by the process simply dying - which is the very case the flag cannot see.
         */
        @Volatile
        var isRecordingNow: Boolean = false
            private set

        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 4712
        private const val RESULT_ID = 4713
        private const val REQUEST_OPEN = 11
        private const val REQUEST_STOP = 12
        private const val NOTIFY_EVERY_MS = 15_000L
        private const val TAG = "RideRecording"

        /** Start recording. Safe to call when a recording is already running (it is ignored). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RideRecordingService::class.java).setAction(ACTION_START),
                )
            }.onFailure { Log.w(TAG, "could not start the recorder: ${it.message}") }
        }

        /** Stop recording and save. Ignored when nothing is being recorded. */
        fun stop(context: Context) {
            if (!RideRecordingStore(context).load().active) return
            runCatching {
                context.startService(
                    Intent(context, RideRecordingService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { Log.w(TAG, "could not stop the recorder: ${it.message}") }
        }
    }
}
