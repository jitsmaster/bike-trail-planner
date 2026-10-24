package com.biketrailplanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.SafetyOutcome
import com.biketrailplanner.ui.theme.GradeColors
import com.biketrailplanner.ui.theme.SheetShape

/**
 * docs/UI.md section 4: the result sheet. Rider language only - the trail name, one distance
 * sentence, the segmented bar with a Details expander, and the two actions pinned so they are
 * always on screen.
 */
@Composable
fun ResultScreen(
    state: PlanViewModel.UiState,
    onBack: () -> Unit,
    onOpenInMaps: (Boolean) -> Unit,
    onDetourAccepted: (Boolean) -> Unit,
    onTrySaferDetour: () -> Unit,
    onPlanLongerTotal: (Double) -> Unit,
    onTryTrail: (String) -> Unit,
    /** D37: the rider chose a different one of the plan's options. No re-planning happens. */
    onSelectRoute: (String) -> Unit = {},
    /** D23: the way out of "no trails near you": ask the live service what is here. */
    onFindTrailsNearHere: () -> Unit = {},
    /**
     * D35 (option A, piece 2): the Velotric assist. Null when the Velotric app is not installed, so
     * the button simply is not shown. It only opens the Velotric app; the rider taps START RIDING.
     */
    onStartVelotricRide: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val result = state.result
    // D37: the header, the numbers below it and the hand-off all describe the option the rider
    // picked, defaulting to the one the rotation chose. Never a fixed single route.
    val route: PlannedRoute? = state.selectedRoute

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1.7f),
            shape = SheetShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outline),
                    )
                }
                // The header (trail name + the one distance sentence) is pinned: it is the summary
                // the rider must always be able to read, whatever the sheet is scrolled to.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(
                        route?.trailLabel ?: "No route yet",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) { Text("Back") }
                }
                if (route != null) {
                    Text(
                        RiderText.distanceSentence(route),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    // D37: every ride the plan found for this distance, pinned under the summary so
                    // the rider can switch without hunting, and compact enough that the safety bar
                    // below stays on screen. Only shown when there is a real choice.
                    if (result != null && result.options.size > 1) {
                        RouteOptionsRow(
                            options = result.options,
                            selectedSignature = route.signature,
                            targetMiles = result.targetMiles,
                            onSelectRoute = onSelectRoute,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    // D22 rule 1 and docs/LOCATION-CONTROL.md B5: name the place the ride is
                    // planned around, not only how accurate the position was.
                    (RiderText.planningFromSentence(state.planningOrigin?.let { state.planningOriginLabel })
                        ?: RiderText.originSentence(state.originAccuracyM))?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    // D31: whether the rider comes back a different way - or that no different safe
                    // way back exists. Without this line the preference is invisible.
                    RiderText.loopSentence(route)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    // D13/D40: name the streets the plan routed around a bad road through, and say
                    // whether each was chosen by a measured count or by its road class.
                    RiderText.detourSentence(route)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    // D26: say plainly when the route on screen is one the rider already saved, so
                    // the numbers below are read as "what was checked then", not as a fresh plan.
                    state.savedTripNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    if (route == null) {
                        Text("Plan a ride first.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        GradeSummary(route)
                        RiderText.capSentence(route)?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = GradeColors.d)
                        }
                        RiderText.closureSentence(route)?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = GradeColors.d)
                        }
                        if (result != null) {
                            when (result.outcome) {
                                // a. PLAN OK: no card at all.
                                com.biketrailplanner.domain.PlanOutcome.PLAN_OK -> Unit
                                // b. CAPPED BY D9: the amber "Shorter than you asked" line above.
                                com.biketrailplanner.domain.PlanOutcome.CAPPED -> Unit
                                // c. TRAIL FLOOR BREACHED (D13): amber, and a real plan exists.
                                com.biketrailplanner.domain.PlanOutcome.TRAIL_FLOOR -> {
                                    Spacer(Modifier.height(12.dp))
                                    TrailFloorCard(
                                        state = state,
                                        route = route,
                                        onPlanLongerTotal = onPlanLongerTotal,
                                        onTryTrail = onTryTrail,
                                    )
                                }
                                // d. D42: a route exists and it is NOT safe. The card warns, the
                                //    buttons below still hand it over - the rider decides.
                                com.biketrailplanner.domain.PlanOutcome.UNSAFE -> {
                                    Spacer(Modifier.height(12.dp))
                                    NoSafeRouteCard(
                                        result = result,
                                        title = RiderText.outcomeTitle(result, route) ?: RiderText.UNSAFE_TITLE,
                                        onTrySaferDetour = onTrySaferDetour,
                                    )
                                }
                                // e. REFUSED: no route at all could be computed.
                                com.biketrailplanner.domain.PlanOutcome.REFUSED -> {
                                    Spacer(Modifier.height(12.dp))
                                    // the title comes from the single source in RiderText, never from
                                    // a literal here: changing the wording there changes this card
                                    NoSafeRouteCard(
                                        result = result,
                                        title = RiderText.outcomeTitle(result, route) ?: RiderText.REFUSAL_TITLE,
                                        onTrySaferDetour = onTrySaferDetour,
                                    )
                                }
                                // e. D22 rule 3: nothing in the bundle covers where the rider is.
                                com.biketrailplanner.domain.PlanOutcome.NO_TRAILS -> {
                                    Spacer(Modifier.height(12.dp))
                                    NoTrailsCard(
                                        result = result,
                                        savedHomeLabel = state.homeLabel,
                                        planningOriginLabel = state.planningOrigin?.let { state.planningOriginLabel },
                                        distanceFromSavedHomeM = state.planningOriginFromSavedHomeM,
                                        onFindTrailsNearHere = onFindTrailsNearHere,
                                        discoveryBusy = state.discoveryBusy,
                                    )
                                }
                            }
                        }
                        if (result != null && route.explicitDetour &&
                            route.totalM > Geo.milesToMetres(result.targetMiles) + 1.0
                        ) {
                            Spacer(Modifier.height(12.dp))
                            LongerRideCard(
                                route = route,
                                requestedMiles = result.targetMiles,
                                accepted = state.detourAccepted,
                                onDetourAccepted = onDetourAccepted,
                            )
                        }
                        // D27: what the model suggested, or why it was not used. Rider language only.
                        //
                        // MEASURED 2026-09-26, and the reason this block is INSIDE the scrolling part
                        // rather than above it: the sheet's fixed rows (handle, header, distance, the
                        // ride chips, the origin and way-around lines, and this card) are laid out
                        // above `Column.weight(1f).verticalScroll`, so when they are taller than the
                        // sheet the scrolling column is squeezed to ZERO height. On the Galaxy S23
                        // Ultra with a DeepSeek key set, the four AI rows alone did that: the grade
                        // bar, the "N% of this ride is measured" caption and the whole `Details`
                        // expander were unreachable with no way to scroll to them, and one more line
                        // (the D31 way-back sentence) pushed "Start navigating" off the sheet as well.
                        // The safety summary comes FIRST and the model's notes scroll with it; the
                        // header and the two hand-off buttons stay pinned, which is what the sheet
                        // design promises (docs/UI.md section 4).
                        if (state.strategyNotes.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                RiderText.aiSummary(state.aiUsed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.strategyNotes.forEach { note ->
                                Text(
                                    RiderText.aiLine(note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
                // D42: the hand-off is available whenever there IS a route, including one the card
                // above has just called unsafe. Only a true refusal (no route at all) has nothing to
                // hand over, and that is what the null check is for.
                if (route != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // D37: acceptance follows the ride on screen. Choosing a different ride clears it,
                    // so the long way to one trail can never silently accept the long way to another.
                    // (`route` is non-null here - D42 gates this block on it - so the only conditions
                    // left are the distance ones.)
                    val needsAcceptance = result != null && route.explicitDetour &&
                        route.totalM > Geo.milesToMetres(result.targetMiles) + 1.0
                    val enabled = !needsAcceptance || state.detourAccepted
                    Button(
                        onClick = { onOpenInMaps(false) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text("Confirm on map") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onOpenInMaps(true) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) { Text("Start navigating") }
                    if (onStartVelotricRide != null) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onStartVelotricRide,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                        ) { Text("Start Velotric ride") }
                    }
                    state.handOffMessage?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * D37: the ride options for this distance, as a pinned row of chips the rider picks from.
 *
 * The planner already computed every one of these (D6/D29: distinct rides, not the same trip entered
 * at several entrances), so picking one costs nothing - no routing call and no model call. The row is
 * horizontal so it stays one line tall: the grade bar and the named streets must not be pushed off
 * the bottom of the sheet by the list of choices.
 */
@Composable
private fun RouteOptionsRow(
    options: List<PlannedRoute>,
    selectedSignature: String,
    targetMiles: Double,
    onSelectRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "Choose your ride",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val selected = option.signature == selectedSignature
                Surface(
                    onClick = { onSelectRoute(option.signature) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    // D10's third state, as a glanceable outline. A border draws inside the existing
                    // shape, so the one-line-tall row this row must stay is untouched.
                    border = if (!selected && option.verdict.outcome == SafetyOutcome.UNVERIFIED) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    } else {
                        null
                    },
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(
                            option.trailLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            optionChipLine(option, targetMiles),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** One option, short enough for a chip: the two distances that decide the choice, and any caveat. */
private fun optionChipLine(route: PlannedRoute, targetMiles: Double): String {
    val parts = ArrayList<String>(5)
    // The D10 verdict goes FIRST. A chip is narrow and ellipsizes, so a caveat appended at the end is
    // the first thing to be cut - which would hide the state exactly when it matters.
    if (route.verdict.outcome == SafetyOutcome.UNVERIFIED) parts.add("not verified")
    parts.add(Formatting.miles1(route.totalM) + " round trip")
    parts.add(Formatting.miles1(route.trailRoundTripM) + " on trail")
    if (route.explicitDetour && route.totalM > Geo.milesToMetres(targetMiles) + 1.0) parts.add("longer")
    if (route.capped) parts.add("the trail runs out")
    return parts.joinToString(" \u00b7 ")
}

/** A detour the rider must accept before navigating (D13 rule 2). */
@Composable
private fun LongerRideCard(
    route: PlannedRoute,
    requestedMiles: Double,
    accepted: Boolean,
    onDetourAccepted: (Boolean) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("This ride is longer than you asked for", style = MaterialTheme.typography.titleMedium)
            Text(
                Formatting.miles1(route.totalM) + " instead of " +
                    Formatting.miles1(Geo.milesToMetres(requestedMiles)) +
                    ", because the shorter ways to the trail have busy roads on them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, onCheckedChange = onDetourAccepted)
                Text("I am happy to ride the longer way", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * docs/SPEC.md section 7c step 3: offer the choice instead of only stating the tradeoff.
 * (i) accept a longer total, (ii) plan a different trail, (iii) see why the direct route was refused.
 */
@Composable
private fun TrailFloorCard(
    state: PlanViewModel.UiState,
    route: PlannedRoute,
    onPlanLongerTotal: (Double) -> Unit,
    onTryTrail: (String) -> Unit,
) {
    val result = state.result ?: return
    var whyOpen by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GradeColors.c.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                RiderText.outcomeTitle(result, route) ?: "Trail is short at this distance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GradeColors.d,
            )
            Text(
                "This is a real route: it meets the safety limits, but most of it is the way there and back.",
                style = MaterialTheme.typography.bodySmall,
            )
            val offer = result.trailFloorOffer
            if (offer != null && offer.canReachFloor) {
                Text(
                    "Ride further to keep more trail: about " + Formatting.miles1(Geo.milesToMetres(offer.suggestedTotalMiles)) +
                        " round trip, which keeps " + Formatting.miles1(Geo.milesToMetres(offer.floorMiles)) + " of trail.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { onPlanLongerTotal(offer.suggestedTotalMiles) },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                ) { Text("Ride " + Formatting.miles1(Geo.milesToMetres(offer.suggestedTotalMiles)) + " instead") }
            } else if (offer != null) {
                Text(
                    "At this distance the trail runs out: " + offer.trailName + " only has " +
                        Formatting.miles1(Geo.milesToMetres(offer.bestTrailMiles)) + " of trail to ride.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Try a shorter round trip: the further the trail is, the more busy road the ride needs.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.alternativeTrails.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Plan a different trail", style = MaterialTheme.typography.titleMedium)
                state.alternativeTrails.forEach { name ->
                    TextButton(onClick = { onTryTrail(name) }) { Text("Try $name") }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { whyOpen = !whyOpen }) { Text("See why the direct route was refused") }
            if (whyOpen) {
                RiderText.refusalSentence(result)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val streets = RiderText.namedRefusalStreets(result)
                if (streets.isEmpty()) {
                    Text(
                        "Every way we tried to the trail crosses streets we cannot vouch for.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    streets.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                val cautionNames = route.cautions.take(3).joinToString(", ") { it.name }
                if (cautionNames.isNotEmpty()) {
                    Text(
                        "Take care on: $cautionNames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** D22 rule 3: honest, with the placeholder discovery entry point. */
@Composable
private fun NoTrailsCard(
    result: com.biketrailplanner.domain.PlanResult,
    savedHomeLabel: String,
    planningOriginLabel: String?,
    distanceFromSavedHomeM: Double?,
    onFindTrailsNearHere: () -> Unit,
    discoveryBusy: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GradeColors.c.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "No trails near you",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GradeColors.d,
            )
            Text(
                RiderText.noTrailsSentence(
                    result = result,
                    savedHomeLabel = savedHomeLabel,
                    planningOriginLabel = planningOriginLabel,
                    distanceFromSavedHomeM = distanceFromSavedHomeM,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            // D23: this is the answer to "the app has no trail here" - ask, instead of giving up.
            OutlinedButton(
                onClick = onFindTrailsNearHere,
                enabled = !discoveryBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(RiderText.Discovery.ACTION)
            }
        }
    }
}

@Composable
private fun NoSafeRouteCard(
    result: com.biketrailplanner.domain.PlanResult,
    title: String,
    onTrySaferDetour: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GradeColors.e.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GradeColors.e,
            )
            RiderText.refusalSentence(result)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            val streets = RiderText.namedRefusalStreets(result)
            if (streets.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("On the way:", style = MaterialTheme.typography.labelMedium)
                streets.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onTrySaferDetour) { Text("Try a safer detour") }
        }
    }
}
