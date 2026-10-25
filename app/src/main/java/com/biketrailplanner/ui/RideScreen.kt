package com.biketrailplanner.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.biketrailplanner.BuildConfig
import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.GoogleMapsLink
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.PanelSize
import com.biketrailplanner.domain.RideHit
import com.biketrailplanner.domain.RideTrack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * D59 (docs/UI.md section 3.6): the rider's own rides, on their own screen **with the map still
 * visible**.
 *
 * Two things were wrong before. The recorder lived in Settings, which is where technical things go, and
 * the list drew a full-screen opaque surface over the one basemap - so the rider could read a ride's
 * distance and time but never see the route (their words: *"I don't see any route at all"*). This screen
 * follows the plan screen instead: a pill at the top, a resizable sheet at the bottom (D53), and the map
 * in the middle, where taps land on it (D60).
 */
@Composable
fun RideScreen(
    state: PlanViewModel.UiState,
    controller: MapController,
    onToggleRecording: () -> Unit,
    onSimulateRide: () -> Unit,
    onSelectRide: (String) -> Unit,
    onDeleteRide: (String) -> Unit,
    onPanelFraction: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        // Everything above the sheet is measured, so the sheet can be given exactly the rest, and the
        // map keeps the middle of the screen to itself.
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopPill(title = "Your rides", onClick = onBack) {
                Text("Back", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val framePx = constraints.maxHeight
            var chromePx by remember { mutableIntStateOf(0) }
            val furniturePx = with(density) { 44.dp.toPx() }
            val gapPx = with(density) { 12.dp.toPx() }
            val minSheetPx = with(density) { 160.dp.toPx() }
            val fullCapPx = (framePx - chromePx - gapPx - furniturePx).coerceAtLeast(minSheetPx)
            var fraction by remember { mutableFloatStateOf(state.panelFraction) }
            var dragging by remember { mutableStateOf(false) }
            // D59: this screen OPENS with the map showing, whatever the plan panel was left at. The
            // rider's complaint was "I don't see any route at all", and a remembered full-height panel
            // reproduces it exactly - measured on the emulator, where the sheet covered all but a sliver
            // of the map. It only takes the remembered value once the rider has adjusted it HERE.
            var adjusted by remember { mutableStateOf(false) }
            val shown = when {
                dragging -> fraction
                adjusted -> state.panelFraction
                else -> state.panelFraction.coerceAtMost(PanelSize.HALF)
            }
            // The sheet's own height, in pixels, so the pinned answer above can be taken off the
            // scrolling part exactly (a sheet is furniture + pinned + scroll).
            val sheetPx = fullCapPx * shown

            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().onSizeChanged { chromePx = it.height }) {
                    // Nothing here yet: the pill above is the whole chrome, and it stays measured so a
                    // row added later cannot silently steal the map's height (D48).
                }
                Spacer(Modifier.height(12.dp))
                BottomSheet(
                    color = MaterialTheme.colorScheme.surface,
                    onHandleDrag = { dy ->
                        if (fullCapPx > 0f) {
                            if (!dragging) {
                                fraction = state.panelFraction
                                dragging = true
                                adjusted = true
                            }
                            fraction = PanelSize.clamp(fraction - dy / fullCapPx)
                        }
                    },
                    onHandleDragEnd = {
                        onPanelFraction(fraction)
                        dragging = false
                    },
                    onHandleTap = {
                        adjusted = true
                        fraction = PanelSize.next(shown)
                        onPanelFraction(fraction)
                    },
                ) {
                    // D60: the answer to the tap is PINNED under the handle, never inside the scroll.
                    // It is the response to the rider's last action, and an answer that needs scrolling to
                    // find is an answer they will not read - measured on the emulator: the street name sat
                    // just below the fold at half height.
                    var answerPx by remember { mutableIntStateOf(0) }
                    state.rideHit?.let { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { answerPx = it.height }
                                .padding(horizontal = 16.dp),
                        ) {
                            TapAnswer(hit, state)
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    val scrollCap = with(density) { (sheetPx - answerPx).coerceAtLeast(minSheetPx).toDp() }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = scrollCap)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                    ) {
                        Text("Record", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onToggleRecording, modifier = Modifier.height(44.dp)) {
                                Text(if (state.recordingActive) "Stop recording" else "Record a ride")
                            }
                            if (BuildConfig.DEBUG) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = onSimulateRide, enabled = !state.recordingActive) {
                                    Text("Simulate")
                                }
                            }
                        }
                        Text(
                            if (state.recordingActive) {
                                "Recording now. Your actual route is being saved, whatever streets you take."
                            } else {
                                "Record a ride you did not plan. It is saved here, graded like any other."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(16.dp))
                        Text("Your rides", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (state.selectedRide == null) {
                                "Tap a ride to see its route on the map, then tap the map to see the streets you rode."
                            } else {
                                "Tap the map to see the street names you rode. Tap a point for its address."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))

                        when {
                            state.rides.isEmpty() && state.ridesLoading ->
                                Text(
                                    "Reading your rides\u2026",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            state.rides.isEmpty() ->
                                Text(
                                    "No recorded rides yet. Start one above, or it starts by itself when you " +
                                        "hand a route to Google Maps.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            else -> state.rides.forEach { ride ->
                                RideCard(
                                    ride = ride,
                                    selected = ride.signature == state.selectedRide?.signature,
                                    onSelect = { onSelectRide(ride.signature) },
                                    onDelete = { onDeleteRide(ride.signature) },
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** D60: the answer to one tap. Every line here is something the app actually knows. */
@Composable
private fun TapAnswer(hit: RideHit, state: PlanViewModel.UiState) {
    AppCard(container = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            when (hit) {
                is RideHit.Node -> {
                    Text(
                        "Point ${hit.index + 1} \u00b7 ${Formatting.miles1(hit.riddenM)} in",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        hit.street?.let { "on $it" } ?: "No street was matched at this point",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        state.addressLoading -> Text(
                            "Looking up the address\u2026",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.rideAddress != null -> Text(
                            state.rideAddress,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.addressNeedsConsent -> Text(
                            "For a postal address, turn on public servers in Settings \u2014 it asks OpenStreetMap.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is RideHit.Edge -> {
                    Text(hit.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${RiderText.gradeWord(hit.grade)} \u00b7 ${Formatting.miles1(hit.lengthM)} \u00b7 ${hit.speedText}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "About ${hit.distanceFromTapM.roundToInt()} m from your tap.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RideCard(ride: RideTrack, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    AppCard(
        container = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ride.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        dateText(ride.endedAtMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            if (ride.hasTrack) {
                Spacer(Modifier.height(8.dp))
                RideGradeBar(ride)
            }
            Spacer(Modifier.height(8.dp))
            InfoRow("Distance", Formatting.miles1(ride.distanceM))
            InfoRow("Time", durationText(ride.durationMs))
            if (ride.gaps > 0) InfoRow("GPS gaps", ride.gaps.toString())
            if (!ride.hasTrack) {
                Text(
                    "Too short to map: the recording caught a single GPS fix, so there is no route to draw.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (ride.ungradedShare > 0.0) {
                Text(
                    Formatting.percent(ride.ungradedShare) +
                        " of this ride had no speed information, so it could not be measured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected && ride.hasTrack) {
                Spacer(Modifier.height(8.dp))
                // D61: hand this ride to Google Maps. The recorded points become Maps' stops, so the
                // navigation visits the exact places the rider visited. Maps still chooses the streets
                // between them - no public API lets us send it the recorded line - and the note below
                // says so rather than implying a turn-by-turn replay of this ride.
                val context = LocalContext.current
                Button(
                    onClick = {
                        val url = GoogleMapsLink.url(ride)
                        if (url == null) {
                            Toast.makeText(context, "This ride has no route to open.", Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure {
                                    Toast.makeText(context, "No app could open Google Maps.", Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Navigate this ride in Google Maps") }
                Text(
                    when {
                        // D64: say what the stops ARE, so the choice is never a mystery.
                        ride.roads.isNotEmpty() -> {
                            val stops = GoogleMapsLink.chosenStops(ride)
                            "Google Maps takes at most ${GoogleMapsLink.MAX_INTERMEDIATE} stops, so this ride " +
                                "names its turns: " + stops.take(3).joinToString("; ") { it.why } +
                                (if (stops.size > 3) "; and ${stops.size - 3} more" else "") +
                                ". It picks the streets between them, and the way back is its own choice."
                        }
                        ride.points.size > GoogleMapsLink.MAX_STOPS ->
                            "Google Maps takes at most 9 stops between the start and the end, so it gets " +
                                "evenly spaced points from this ride. It picks the streets between them."
                        else -> "Google Maps follows its own streets between these stops."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!selected) {
                Text(
                    "Tap to see this route on the map",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * The same bar as a plan's, drawn from a saved ride's grade breakdown. It reads `RideTrack.metres`, not
 * `byGrade` directly: `byGrade` deliberately carries only the graded metres, so reading UNGRADED or
 * REJECTED out of it draws nothing - which is how a fully ungraded ride once ended up with an empty bar
 * on a real device (docs/RIDE-RECORDER.md section 7).
 */
@Composable
private fun RideGradeBar(ride: RideTrack) {
    val total = ride.breakdownTotalM.takeIf { it > 0.0 } ?: 1.0
    val grades = listOf(Grade.A, Grade.B, Grade.C, Grade.D, Grade.E, Grade.UNGRADED, Grade.REJECTED)
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.outline),
    ) {
        grades.forEach { grade ->
            val fraction = (ride.metres(grade) / total).toFloat()
            if (fraction <= 0.001f) return@forEach
            Box(
                Modifier
                    .weight(fraction)
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(gradeColor(grade)),
            )
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)

private fun dateText(atMs: Long): String =
    runCatching { Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()).format(DATE_FORMAT) }
        .getOrDefault("")

private fun durationText(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
