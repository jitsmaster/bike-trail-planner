package com.biketrailplanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.PanelSize
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.TrailNear
import com.biketrailplanner.ui.theme.GradeColors

/**
 * docs/UI.md section 3: map-first, Google-Maps-style top pill, milestone chips, bottom sheet.
 * Nothing technical is shown here - server details and raw data live in Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(
    state: PlanViewModel.UiState,
    onTargetMiles: (Int) -> Unit,
    onPlan: () -> Unit,
    onRefreshLocation: () -> Unit,
    onRefreshTrailData: () -> Unit,
    onOpenSettings: () -> Unit,
    /** D59: open the rider's own recorded rides, on their own screen. */
    onOpenRide: () -> Unit = {},
    onOpenResult: () -> Unit,
    /** D23: ask the live trail service what is near the rider, and use the answer next time. */
    onFindTrailsNearHere: () -> Unit = {},
    /** D26: ride the saved trip with this signature. No routing call, no model call. */
    onUseSuggestion: (String) -> Unit = {},
    /** D38: plan with the trail the rider picked off the trails-near list. */
    onTrailPicked: (String) -> Unit = {},
    /** D39: stop a plan in flight rather than making the rider wait it out. */
    onCancelPlan: () -> Unit = {},
    /** D53: the rider resized the bottom panel to see more map; the choice is remembered. */
    onPanelFraction: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val result = state.result
    // D37: the summary under the title describes the ride the rider selected, not the one the
    // rotation happened to pick.
    val route: PlannedRoute? = state.selectedRoute

    // The basemap lives in MainActivity and stays alive across screens (one MapView, one
    // lifecycle: creating and destroying it per screen deadlocks MapLibre's surface thread).
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TopPill(title = "Plan a ride from here", onClick = onOpenSettings) {
                // D59: "Ride" sits next to Settings, in the same pill, because it is a place to go and
                // not a setting. The pill itself still opens Settings.
                TextButton(onClick = onOpenRide) {
                    Text("Ride", style = MaterialTheme.typography.labelMedium)
                }
                Text("Settings", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // The panel is bottom-anchored, and its height is derived from the FRAME it is in, not
            // from the screen: the chrome above the sheet is measured, the sheet is capped to what
            // is left, and it scrolls. A screen-size fraction (0.62) was wrong on a phone whose
            // frame differs from the one it was tuned on - the clipped bottom edge this replaces.
            val density = LocalDensity.current
            val framePx = constraints.maxHeight
            var chromePx by remember { mutableIntStateOf(0) }
            // The sheet's own furniture: the drag-handle block plus its bottom padding.
            val sheetFurniturePx = with(density) { 44.dp.toPx() }
            val gapPx = with(density) { 12.dp.toPx() }
            val minSheetPx = with(density) { 140.dp.toPx() }
            val fullCapPx = (framePx - chromePx - gapPx - sheetFurniturePx).coerceAtLeast(minSheetPx)
            // D53: the rider chooses how much of that the panel actually takes. Dragging the handle
            // moves it continuously; releasing snaps to a preset, so the panel is never mid-way.
            var dragFraction by remember { mutableFloatStateOf(state.panelFraction) }
            var dragInProgress by remember { mutableStateOf(false) }
            val shownFraction = if (dragInProgress) dragFraction else state.panelFraction
            val sheetCap = with(density) { (fullCapPx * shownFraction).toDp() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            ) {
            // Everything above the sheet, measured so the sheet can be given exactly the rest.
            Column(Modifier.fillMaxWidth().onSizeChanged { chromePx = it.height }) {
            Text(
                "Round-trip distance",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
            var customOpen by remember { mutableStateOf(false) }
            // D20: with eleven presets plus Custom, a single scrolling row would hide eight of the
            // twelve choices on a phone. The chips wrap instead, so every option is visible at once.
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // D56: two presets, 10 and 20 miles, plus Custom. The rider narrowed the range: below
                // 10 mi is a lap of the neighbourhood and above 20 mi is mostly commute, so the chips
                // were offering eight choices the rider never used and hiding the map for nothing.
                MILEAGE_PRESETS.forEach { miles ->
                    MileageChip(
                        label = "$miles mi",
                        selected = state.targetMiles.toIntOrNull() == miles,
                        onClick = { onTargetMiles(miles) },
                    )
                }
                // Anything that is not one of the presets is the rider's own number, so the chip names
                // it rather than claiming to be a Custom value that is currently unset.
                val customMiles = state.targetMiles.toIntOrNull()?.takeIf { it !in MILEAGE_PRESETS }
                MileageChip(
                    label = if (customMiles != null) "Custom: ${state.targetMiles} mi" else "Custom",
                    // Exactly one chip is ever highlighted: a preset, or Custom when the value in effect
                    // is the rider's own number. Otherwise a remembered 15 leaves the row with nothing
                    // selected, and the rider cannot see which distance is about to be planned.
                    selected = customMiles != null,
                    onClick = { customOpen = true },
                )
            }
            if (customOpen) {
                CustomMilesStepper(
                    initial = state.targetMiles.toIntOrNull() ?: com.biketrailplanner.AppContainer.DEFAULT_TARGET_MILES,
                    onCancel = { customOpen = false },
                    onConfirm = { miles ->
                        onTargetMiles(miles)
                        customOpen = false
                    },
                )
            }
            state.honestyLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
            }
            Spacer(Modifier.height(12.dp))
            BottomSheet(
                color = MaterialTheme.colorScheme.surface,
                onHandleDrag = { dy ->
                    if (fullCapPx > 0f) {
                        // A new drag starts from wherever the panel actually is, so a stale remembered
                        // value can never make the first pixel of a drag jump.
                        if (!dragInProgress) {
                            dragFraction = state.panelFraction
                            dragInProgress = true
                        }
                        dragFraction = PanelSize.clamp(dragFraction - dy / fullCapPx)
                    }
                },
                onHandleDragEnd = {
                    onPanelFraction(dragFraction)
                    dragInProgress = false
                },
                onHandleTap = {
                    dragFraction = PanelSize.next(state.panelFraction)
                    onPanelFraction(dragFraction)
                },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = sheetCap)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        route?.trailLabel ?: "Bike Trail Planner",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        StatBlock("Distance", formatMiles(state.targetMiles))
                        route?.let {
                            StatBlock("Trail", Formatting.miles1(it.trailRoundTripM))
                        }
                    }
                    state.fixNote?.let { note ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.originGood) MaterialTheme.colorScheme.onSurfaceVariant else GradeColors.d,
                        )
                    }
                    // docs/LOCATION-CONTROL.md layer 3: the tap must always answer. This line is the
                    // acknowledgement that the position was adopted, and it is only ever set by a tap.
                    state.positionNote?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.planning) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        // D39: a plan is dozens of sequential routing calls, so it can take minutes on
                        // the fallback router. An indeterminate spinner for minutes is indistinguishable
                        // from a hang, so the sheet shows the clock and a way out.
                        val started = state.planStartedAtMs
                        var elapsedS by remember(started) { mutableLongStateOf(0L) }
                        LaunchedEffect(started) {
                            while (true) {
                                elapsedS = started?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
                                kotlinx.coroutines.delay(1000)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                RiderText.Discovery.planningLine(state.planningTrail, elapsedS),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCancelPlan) { Text("Stop") }
                        }
                    }
                    state.riderMessage?.let { message ->
                        Spacer(Modifier.height(12.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // D23: the live trail lookup. It gets its own full-width control instead of a
                    // third button in the row below, which wrapped onto a second line on a phone and
                    // pushed the row itself off the bottom of the sheet.
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onFindTrailsNearHere,
                        enabled = !state.discoveryBusy,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) { Text(RiderText.Discovery.ACTION) }
                    // D23: what the last lookup found, and how strong that answer is.
                    state.discoveryNote?.let { note ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // D38: the trails the app can reach from here, closest first, each with its
                    // rideable length and how far away it starts. Tapping one plans with it.
                    if (state.trailsNear.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        TrailsNearCard(trails = state.trailsNear, onTrailPicked = onTrailPicked)
                    }
                    // D26: the rides already saved for this request. They are offered BEFORE any work
                    // is done, and one of them can be used instead of planning anything.
                    if (state.suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SavedRidesCard(
                            state = state,
                            onUseSuggestion = onUseSuggestion,
                        )
                    }
                    // D27: what the model suggested, in its own words about the rider's own ride.
                    if (state.aiUsed && state.strategyNotes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        AiNoteCard(state.strategyNotes)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onPlan,
                        enabled = !state.planning,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text("Plan ride") }
                    if (route != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenResult,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text("See the route") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onRefreshLocation) { Text("Use my location") }
                        TextButton(onClick = onRefreshTrailData, enabled = !state.refreshing) {
                            Text("Refresh trails")
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * D38: the trails within reach of the rider, CLOSEST FIRST.
 *
 * Two numbers per trail, which are the two a rider actually chooses with: how much trail there is, and
 * how far away it starts. Both come from the trail's own geometry (see [TrailsNear]) - the distance is
 * to the nearest point of the trail, not to the first node of the relation, so a trail that runs past
 * the rider reads as close rather than far.
 *
 * The row is the action (a Material button's 48 dp minimum would fit about three trails on a phone),
 * and tapping it plans with that trail - the same path as the D13 "try a different trail" action.
 */
@Composable
private fun TrailsNearCard(trails: List<TrailNear>, onTrailPicked: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Trails near you", style = MaterialTheme.typography.titleMedium)
            Text(
                "Closest first. Tap one to plan your ride on it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The word "unchecked" is defined once, here, rather than repeated per row where it
            // would be the first thing the narrow row ellipsizes away.
            if (trails.any { !it.verified }) {
                Text(
                    "An unchecked trail has had no closure or surface check yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trails.forEach { trail ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onTrailPicked(trail.name) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            trail.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // NOT "not verified": that word belongs to the D10 ride verdict on the
                            // result screen (docs/SPEC.md section 6), and this flag is a different
                            // fact - the trail came from live discovery, so no closure check and no
                            // surface audit has happened (D23). One word, one meaning.
                            Formatting.miles1(trail.rideableM) + " of trail" +
                                if (trail.verified) "" else " \u00b7 unchecked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    // The distance is the half of the choice that decides it, so it is never the part
                    // that gets truncated.
                    Text(
                        Formatting.miles1(trail.distanceM) + " away",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * D26 (docs/ROUTE-STORE.md): the rides the app has already worked out from here, offered before any
 * planning work happens. Using one costs no routing call and no model call. The card never blocks the
 * way to a new ride: the "Plan ride" button below stays exactly where it always was.
 */
@Composable
private fun SavedRidesCard(
    state: PlanViewModel.UiState,
    onUseSuggestion: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(RiderText.SavedTrips.HEADING, style = MaterialTheme.typography.titleMedium)
            state.suggestionNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Every saved ride, in the sheet's own scroll: the sheet is the scroller now, so this
            // no longer needs a second one (a nested scroller would trap the gesture).
            Column(Modifier.fillMaxWidth()) {
            // One compact row per saved ride.
            state.suggestions.forEach { saved ->
                // The whole row is the action, not a Material button: a button's 48 dp minimum touch
                // target makes every row 180 px tall, and five of those do not fit above the plan
                // action on a phone. A clickable row is ~75 px and every one of the five is visible.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onUseSuggestion(saved.signature) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // One line per saved ride: the name flexes, the numbers that decide the choice
                    // are never the part that gets truncated.
                    Text(
                        saved.trailLabel,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (saved.safetyStale) RiderText.SavedTrips.STALE_SHORT else RiderText.SavedTrips.cardLine(saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saved.safetyStale) GradeColors.d else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Text(
                        RiderText.SavedTrips.useAction(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            }
        }
    }
}

/** D27: the model's suggestions in rider language. Internal vocabulary is filtered out here. */
@Composable
private fun AiNoteCard(lines: List<String>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("What the AI suggested", style = MaterialTheme.typography.titleSmall)
            lines.forEach { line ->
                Text(
                    RiderText.aiLine(line),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * D56 (docs/UI.md section 3.3): the round-trip presets. Two choices plus Custom.
 *
 * The row used to be ten chips from 5 to 50 miles in 5-mile steps (D20). The rider narrowed it: under
 * 10 miles is a lap of the neighbourhood, and over 20 miles is mostly commute rather than trail, so the
 * other eight chips were choices that were never taken while costing most of the map.
 */
private val MILEAGE_PRESETS = listOf(10, 20)

@Composable
private fun MileageChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = if (selected) 1.dp else 0.dp,
        onClick = onClick,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** D20: a small stepper for any value from 1 to 200 mi, snapping to whole miles. */
@Composable
private fun CustomMilesStepper(initial: Int, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(initial.coerceIn(1, 200)) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("How far do you want to ride?", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { value = (value - 1).coerceAtLeast(1) }) { Text("\u2212") }
                Text(
                    "$value mi",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                OutlinedButton(onClick = { value = (value + 1).coerceAtMost(200) }) { Text("+") }
            }
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = { onConfirm(value) }) { Text("Use $value mi") }
            }
        }
    }
}

internal fun formatMiles(text: String): String {
    val value = text.toDoubleOrNull() ?: return text
    return Formatting.miles1(Geo.milesToMetres(value))
}
