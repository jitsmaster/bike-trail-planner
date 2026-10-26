package com.biketrailplanner.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.biketrailplanner.R
import com.biketrailplanner.domain.RideTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * D68: a recorded ride, saved as an icon on the phone's own home screen.
 *
 * The rider asked for exactly this, after the Google Maps hand-off worked: *"for recorded ride, add
 * another button save it as a google map navigation desktop icon, but allow me to give it a proper
 * name."* The hand-off already turns a ride into a Maps URL - this is that URL with a face: a shortcut
 * the rider names themselves, so the home screen says *"Milton trail loop"* rather than a date.
 *
 * It uses the platform's own pin-shortcut flow (`ShortcutManagerCompat.requestPinShortcut`), which is
 * the only supported way for an app to place an icon on the launcher. The launcher shows its own
 * confirmation dialog with the name the rider chose, so the rider can fix a typo there too, and no
 * launcher permission is involved.
 *
 * The ride's signature is the shortcut id, so re-saving the same ride **updates** the icon instead of
 * stacking duplicates, and the intent is a plain `ACTION_VIEW` on the URL: whatever the rider has Maps
 * installed as - the app, a browser, Android Auto - answers it.
 */
object RideShortcut {

    /** Launchers show a truncated label; the rider's own name is cut at a sane length here instead. */
    const val MAX_LABEL = 40

    /** What pinning a shortcut can do, so the screen can say something true in each case. */
    enum class Result {
        /** The launcher accepted the request and will ask the rider to confirm. */
        PINNED,

        /** This launcher does not accept pinned shortcuts at all (older or third-party launchers). */
        UNSUPPORTED,

        /** The launcher claimed support and then refused. Nothing was added. */
        FAILED,
    }

    /**
     * A name to start the rider off, based on what the ride actually was: the day, and the first named
     * road it touched. They can and should replace it - it is only a suggestion that beats an empty box.
     */
    fun defaultName(track: RideTrack): String {
        val day = SimpleDateFormat("MMM d", Locale.US).format(Date(track.startedAtMs))
        val road = track.roads
            .map { it.name }
            .firstOrNull { it.isNotBlank() && it != UNNAMED }
        return label(if (road != null) "Ride $day, $road" else "Ride $day")
    }

    /**
     * Clean up whatever the rider typed: one line, no runs of spaces, no leading/trailing space, and
     * never longer than a launcher will show. A blank name falls back rather than adding an icon with
     * no label on it.
     */
    fun label(raw: String, fallback: String = "Ride in Google Maps"): String {
        val cleaned = raw.replace(Regex("\\s+"), " ").trim()
        val chosen = if (cleaned.isEmpty()) fallback else cleaned
        return if (chosen.length <= MAX_LABEL) chosen else chosen.take(MAX_LABEL).trimEnd()
    }

    /** Stable per ride: saving the same ride again updates its icon instead of adding a second one. */
    fun idFor(signature: String): String = "ride-$signature"

    /** True when this phone's launcher can take a pinned shortcut at all. */
    fun supported(context: Context): Boolean =
        runCatching { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }.getOrDefault(false)

    /**
     * Ask the launcher to put [url] on the home screen under [name].
     *
     * Returns what actually happened; the caller must not claim an icon was added on the strength of
     * having asked, because a launcher is free to say no after saying it supports pinning.
     */
    fun pin(context: Context, track: RideTrack, name: String, url: String): Result {
        if (!supported(context)) return Result.UNSUPPORTED
        val app = context.applicationContext
        val label = label(name)
        return runCatching {
            val open = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val shortcut = ShortcutInfoCompat.Builder(app, idFor(track.signature))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                .setIntent(open)
                .build()
            if (ShortcutManagerCompat.requestPinShortcut(app, shortcut, null)) Result.PINNED else Result.FAILED
        }.getOrDefault(Result.FAILED)
    }

    private const val UNNAMED = "(unnamed)"
}
