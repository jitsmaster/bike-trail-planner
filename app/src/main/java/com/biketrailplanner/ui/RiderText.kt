package com.biketrailplanner.ui

import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.NamedSegment
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.domain.PlanOutcome
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.TrafficConfidence
import com.biketrailplanner.domain.fmt
import java.util.Locale

/**
 * docs/UI.md section 1: everything the app says to a rider is written here, in rider language.
 *
 * No internal vocabulary reaches a screen: no grade letters spoken as codes, no "vpd", no "STALE",
 * no OSM road classes, no decision numbers, no raw coordinates. The class and the raw counts stay
 * in the data; this file turns them into sentences.
 */
object RiderText {

    /** What a rider calls a grade. */
    fun gradeWord(grade: Grade): String = when (grade) {
        Grade.A -> "Separated path"
        Grade.B -> "Protected lane"
        Grade.C -> "Quiet road"
        Grade.D -> "Caution street"
        Grade.E -> "Busy road"
        Grade.UNGRADED -> "Not measured"
        Grade.REJECTED -> "Not rideable"
    }

    /**
     * The title of the warning card for a plan's outcome. The refusal title (see REFUSAL_TITLE)
     * belongs to the REFUSED state only: when a compliant route exists (even one that leaves a
     * token trail ride) the card is amber and says what the ride is, never that nothing was found.
     * This is the single source for that wording - no screen may spell it out itself.
     */
    /** The one and only copy of the refusal title. Nothing else may spell it out. */
    const val REFUSAL_TITLE = "No safe route today"

    /**
     * D42: the title for the case where the app HAS a route and it is not safe. It is deliberately
     * not the refusal title - nothing is being withheld, and saying "no safe route today" next to a
     * route the rider can already see and send to Google Maps would be a lie about what happened.
     */
    const val UNSAFE_TITLE = "Not safe as planned - your call"

    fun outcomeTitle(result: PlanResult, route: PlannedRoute): String? = when (result.outcome) {
        PlanOutcome.PLAN_OK -> null
        PlanOutcome.CAPPED -> null
        PlanOutcome.TRAIL_FLOOR -> "Only " + Formatting.miles1(route.trailRoundTripM) + " of this ride is on trail"
        PlanOutcome.UNSAFE -> UNSAFE_TITLE
        PlanOutcome.REFUSED -> REFUSAL_TITLE
        PlanOutcome.NO_TRAILS -> "No trails near you"
    }

    fun outcomeColorKey(result: PlanResult): String = when (result.outcome) {
        PlanOutcome.TRAIL_FLOOR -> "amber"
        PlanOutcome.UNSAFE -> "red"
        PlanOutcome.REFUSED -> "red"
        PlanOutcome.NO_TRAILS -> "amber"
        else -> "none"
    }

    /**
     * D22 rule 3, sharpened by docs/LOCATION-CONTROL.md B3: the bundle covers the area around the
     * SAVED home, which is not necessarily where the rider is. Say both facts, and say how far apart
     * they are, instead of calling the saved home "where you are".
     */
    fun noTrailsSentence(
        result: PlanResult,
        savedHomeLabel: String,
        planningOriginLabel: String? = null,
        distanceFromSavedHomeM: Double? = null,
    ): String {
        val head = result.refusal ?: "No trails in the app cover this area"
        val coverage = " The trails in this app cover the area around your saved home, " + savedHomeLabel + "."
        val from = planningOriginLabel?.let { label ->
            " This ride starts from " + label +
                (distanceFromSavedHomeM?.let { " about " + Formatting.miles1(it) + " away from there" } ?: "") + "."
        } ?: ""
        return head + "." + coverage + from
    }

    /** D22 rule 6: the counts cover the area near home only. */
    const val NO_LOCAL_DATA: String =
        "No local traffic data for this area, so most streets here could not be measured."

    /** D22 + rotation: never a silent repeat. */
    fun repeatSentence(result: PlanResult): String? = when {
        !result.repeatOfLast -> null
        result.options.size <= 1 -> "You rode this one last time. It is the only safe option today."
        else -> "You rode this one last time; nothing safer came up today, so this is the same ride again."
    }

    /** D22 rule 1: the origin is the live position, and the rider is told so. */
    fun originSentence(accuracyM: Float?): String? =
        accuracyM?.let { "Starting from your location (\u00b1" + fmt(it.toDouble(), 0) + " m)." }

    /**
     * docs/LOCATION-CONTROL.md B5: the screen that confirms the route must also name the place the
     * ride is planned around, not only its accuracy.
     */
    fun planningFromSentence(planningOriginLabel: String?): String? =
        planningOriginLabel?.let { PlanningOrigin.planningFromSentence(it) }

    /**
     * D13 rule 1 / D40: the streets this plan was forced through to stay off a road it will not ride.
     *
     * Shown because the choice has to be visible, and so does its REASON: a measured count and a road
     * class are not the same strength of evidence. A measured link reads "A St SE (1,200 vpd, 2019)";
     * a street picked by class reads "18th St E (residential street, no posted limit)". Neither is
     * ever presented as a speed limit the street does not have.
     */
    fun detourSentence(route: PlannedRoute): String? {
        if (route.via.isEmpty()) return null
        return "Way around: " + route.via.joinToString(", ") { via ->
            // Built here rather than from `ViaRef.text`, which writes the count as "1,200 vpd" and
            // appends "LTS 4": both are internal vocabulary the screens must not print (`vpd` and
            // `LTS` are on the banned list in `tools/verify_ui.py`, and docs/UI.md section 1 requires
            // "vehicles/day" and "older data").
            val count = via.aadt?.takeIf { it > 0 }?.let { vehicles(it, via.aadtYear) }
            if (count == null) via.label else via.label + " \u00b7 " + count
        }
    }

    /**
     * D31 (`docs/LOOPS.md`): what the rider is told about the way back.
     *
     * Two states, and the second one matters as much as the first: a loop is a PREFERENCE the rider
     * asked for ("the preference not to take the same way back"), so when no different safe way back
     * exists the app says so plainly instead of leaving the rider to wonder. It never claims a loop
     * that did not happen - the share is measured from the OSM road ways both legs actually use.
     */
    fun loopSentence(route: PlannedRoute): String? {
        val loop = route.loop
        return when {
            loop != null -> "You come back a different way: " +
                fmt(loop.newRoadShare * 100, 0) + "% of the roads on the way back are new."
            route.loopSearched -> "No different safe way back was found, so this one comes back the same way."
            else -> null
        }
    }

    /** The caption under the segmented bar. */
    fun measuredCaption(route: PlannedRoute): String =
        Formatting.percent(route.breakdown.gradedShare) + " of this ride is measured \u2014 see what we know"

    /** The one human sentence under the title. */
    fun distanceSentence(route: PlannedRoute): String =
        Formatting.miles1(route.totalM) + " round trip \u00b7 " + Formatting.miles1(route.trailRoundTripM) + " on trail"

    /** "2,300 vehicles/day (1996 data)" or "no count available". */
    fun vehicles(aadt: Int?, year: Int?): String {
        if (aadt == null || aadt <= 0) return "no count available"
        val rounded = if (aadt >= 1000) {
            String.format(Locale.US, "%,d", (aadt / 100) * 100)
        } else {
            String.format(Locale.US, "%,d", aadt)
        }
        return if (year != null) "$rounded vehicles/day (${year} data)" else "$rounded vehicles/day"
    }

    /** A street row: "114th Avenue East - 2.1 mi - 2,300 vehicles/day (1996 data)". */
    fun streetRow(segment: NamedSegment): String {
        val parts = ArrayList<String>()
        parts.add(Formatting.miles1(segment.metres))
        segment.volumeText?.let { raw ->
            parts.add(vehiclesFromText(raw))
        } ?: parts.add("no count available")
        return segment.name + " \u00b7 " + parts.joinToString(" \u00b7 ")
    }

    private fun vehiclesFromText(raw: String): String {
        val count = raw.substringBefore(" vpd").replace(",", "").toIntOrNull()
        val year = Regex("\\((\\d{4})\\)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return vehicles(count, year)
    }

    /** A grade row: "Quiet road - 1.3 mi". */
    fun gradeRow(grade: Grade, metres: Double): String = gradeWord(grade) + " \u00b7 " + Formatting.miles1(metres)

    /** Why the ride is shorter than asked, in rider language. */
    fun capSentence(route: PlannedRoute): String? {
        if (!route.capped) return null
        return "Shorter than you asked: " + Formatting.miles1(route.totalM) + " instead of " +
            fmt(route.targetMiles, 1) + " mi \u2014 the trail ends here"
    }

    /** Why a plan was refused, with no codes. */
    fun refusalSentence(result: PlanResult): String? {
        val route = result.bestAttempt ?: return null
        val dMiles = Geo.metresToMiles(route.breakdown.metres(Grade.D))
        val eMiles = Geo.metresToMiles(route.breakdown.metres(Grade.E))
        return when {
            eMiles > 0.05 -> "This route would put you on " + fmt(eMiles, 1) + " mi of busy road, " +
                "which is over your limit."
            dMiles > result.dCapMiles -> "This route has " + fmt(dMiles, 1) + " mi of caution streets, more than the " +
                fmt(result.dCapMiles, 1) + " mi we allow."
            result.trailFloorOffer != null -> "The safest way to the trail only leaves " +
                Formatting.miles1(Geo.milesToMetres(result.trailFloorOffer.bestTrailMiles)) + " of trail."
            else -> "Nothing we found today meets the safety limits."
        }
    }

    /**
     * D43: what the rider is told after "Try a safer detour" came back empty-handed. The button used
     * to do nothing at all in this state (it only acted when the plan carried a trail-floor offer),
     * so a tap could produce no visible change - which reads as a broken button, not as an answer.
     * Now the app always answers, even when the answer is no.
     */
    const val SEARCH_HARDER_EMPTY =
        "We searched further out for a safer way and could not find one. " +
            "Try a different trail, or ride this one and take care."

    /** What the rider is told when the wider search DID find a compliant ride. */
    const val SEARCH_HARDER_FOUND = "We searched further out and found a safer way."

    /** What the sheet says while that wider search is running. */
    const val SEARCH_HARDER_RUNNING = "Searching further out for a safer way\u2026"

    /** The named streets that stopped the plan. */
    fun namedRefusalStreets(result: PlanResult): List<String> =
        result.disqualifying.take(6).map { streetRow(it) }

    /** A closed trail, said the way a rider needs to hear it. */
    fun closureSentence(route: PlannedRoute): String? {
        val detour = route.detours.firstOrNull() ?: return null
        val km = fmt(detour.closure.wayLengthM / 1000, 1)
        return "Part of the " + detour.closure.trailName + " is closed: $km mi in the middle has been shut " +
            "off by private property since " + (detour.closure.closedSince ?: "2026") + ". This ride goes " +
            "around it on " + detour.streetNames.take(3).joinToString(", ") + "."
    }

    /** The street names a detour uses, deduplicated and human. */
    fun detourStreets(route: PlannedRoute): List<String> =
        route.detours.flatMap { it.streetNames }.distinct().filterNot { it.startsWith("(") }

    /** Why an unmeasured street is not a safe street. */
    const val NOT_MEASURED_EXPLANATION: String =
        "Not measured means we could not verify the speed limit or the traffic there. It is not a " +
            "statement that the street is safe."

    /**
     * The producer's own flags about suspicious rows (D14/D16). They are shown, never suppressed,
     * but in words a rider can use: no "vpd", no "STALE", no grade letters.
     */
    fun dataFlags(flags: List<String>): List<String> = flags.map { flag ->
        val body = flag.substringAfter(": ", flag)
        when {
            body.contains("vpd") && body.contains("grades it D") -> {
                val count = Regex("([\\d,]+) vpd").find(body)?.groupValues?.get(1) ?: "a high number of"
                val year = Regex("\\((\\d{4})\\)").find(body)?.groupValues?.get(1)
                val road = flag.substringBefore(":").replace(" speed suspect", "").split(" ").joinToString(" ") {
                    it.replaceFirstChar { c -> c.uppercase() }
                }
                "$road: the posted speed looks low for " + count + " vehicles/day" +
                    (year?.let { " ($it)" } ?: "") + ", so we treat it as a caution street."
            }
            body.startsWith("RESOLVED") -> {
                val road = flag.substringBefore(":").replace(" speed suspect", "").split(" ").joinToString(" ") {
                    it.replaceFirstChar { c -> c.uppercase() }
                }
                val mph = Regex("(\\d+) mph").find(body)?.groupValues?.get(1)
                "$road: you confirmed " + (mph?.let { "$it mph" } ?: "the posted speed") +
                    "; it stays a caution street because of the traffic volume."
            }
            body.contains("Every usable LOCAL count") ->
                "The traffic counts in this app are from 1994-1996 for the local roads. Only the state highways have counts from 2010 or later."
            else -> body
                .replace(" vpd", " vehicles/day")
                .replace("STALE", "older data")
                .replace("grades it D", "makes it a caution street")
        }
    }

    /** Why the lines below are there, or why the app planned without them. */
    fun aiSummary(used: Boolean): String =
        if (used) "What the AI suggested" else "About the AI"

    /**
     * One line the model wrote, said the way a rider reads. The model is asked for plain sentences,
     * but a screen may never show internal vocabulary (docs/UI.md section 1), so anything that slips
     * through - a raw unit, a state word, a decision number - is rewritten here rather than shown.
     */
    fun aiLine(text: String): String = text
        .replace(" vpd", " vehicles/day")
        .replace("vpd", "vehicles/day")
        .replace("STALE", "older data")
        .replace("UNGRADED", "not measured")
        .replace("ungraded", "not measured")
        .replace(Regex("\\(D\\d+\\)"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun confidenceWord(confidence: TrafficConfidence): String = when (confidence) {
        TrafficConfidence.STALE -> "older data"
        TrafficConfidence.ESTIMATE -> "estimate"
        else -> "measured"
    }

    /**
     * D26 (docs/ROUTE-STORE.md): the words for a ride the app already worked out. No technical
     * vocabulary: the rider is told it is a ride they saved, how far it is, and that using it costs
     * nothing.
     */
    object SavedTrips {

        const val HEADING = "Rides you already saved"

        fun intro(count: Int): String =
            "You have " + count + " ride" + (if (count == 1) "" else "s") +
                " saved from here. Using one costs nothing."

        /**
         * The numbers that decide which ride a rider picks, kept SHORT on purpose: the plan screen
         * already carries fifteen distance chips, the position note and the plan action, and a card
         * per saved ride has to stay on one line for five of them to fit above the action row.
         * "10.0 mi - 3.4 mi trail - 70% measured".
         */
        fun cardLine(route: com.biketrailplanner.domain.ReusableRoute): String =
            Formatting.miles1(route.totalM) + " \u00b7 " +
                Formatting.miles1(route.trailRoundM) + " trail \u00b7 " +
                Formatting.percent(route.measuredShare)

        /** A saved verdict computed from older safety data is said out loud, never hidden. */
        fun staleLine(): String = "Saved with older safety data: the app will work it out again."

        /** The same warning where only one short line fits. */
        const val STALE_SHORT = "older safety data \u2014 checked again"

        fun adoptedLine(route: com.biketrailplanner.domain.ReusableRoute): String =
            "This is the ride you saved: " + route.trailLabel + ", " +
                Formatting.miles1(route.totalM) + " round trip."

        fun useAction(): String = "Use this ride"
    }

    /**
     * D23: the words for the live trail lookup. The strength of a "nothing found" matters, so it is
     * never flattened into a plain "no trails here".
     */
    object Discovery {

        const val CHECKING: String = "Looking for trails near you\u2026"

        /**
         * D39: what the sheet says while a plan is running. The elapsed time matters because a plan is
         * dozens of sequential routing calls: on the fallback router that is minutes, and without a
         * clock those minutes are indistinguishable from a hang. When the rider picked a trail, naming
         * it is the confirmation that their tap was the thing that started this.
         */
        fun planningLine(pickedTrail: String?, elapsedS: Long): String = buildString {
            append("Finding a safe way to the trail")
            if (!pickedTrail.isNullOrBlank()) append(" on ").append(pickedTrail)
            append("\u2026 ")
            append(if (elapsedS >= 60) "${elapsedS / 60} min ${elapsedS % 60} s" else "$elapsedS s")
        }

        /** The one action label, used on the plan screen and on the "no trails near you" card. */
        const val ACTION: String = "Find trails near here"

        fun whatHappensNext(added: Int): String = when {
            added > 0 -> "The next plan will use them."
            else -> "Nothing was added, so the next plan is unchanged."
        }

        fun failed(error: Throwable): String = when {
            // A rate limit is the one failure the rider can actually do something about, and it is not
            // a network fault, so it gets its own plain sentence instead of a mirror dump.
            error is com.biketrailplanner.data.OverpassException && error.rateLimited ->
                "The trail service is busy right now (it limits how often it can be asked), so nothing " +
                    "was added. The app still has the trails it ships with. Try again in a minute."
            error is com.biketrailplanner.data.OverpassException ->
                "Could not look up trails just now (" + error.summary +
                    "). The app still has the trails it ships with, and nothing else changed."
            else ->
                "Could not look up trails just now. The app still has the trails it ships with, and nothing else changed."
        }
    }

    /**
     * The words for the place a ride is planned AROUND (docs/LOCATION-CONTROL.md B2/B3). That place
     * is the live position (D22). The SAVED home is a different idea: it only marks where the
     * bundled trail and traffic data is valid. All of this is pure text and pure geometry, so it is
     * unit-tested directly (PlanningOriginTest).
     */
    object PlanningOrigin {

        /** Within this distance of the saved home the rider counts as being AT it (GPS jitter). */
        const val NEAR_SAVED_HOME_M = 150.0

        /** Used when no fix is good enough to plan from: there is no place to name yet. */
        const val NO_FIX_LABEL = "your saved home"

        /** What a tap on "Use my location" must do, decided from the state alone. */
        enum class TapAction { NOT_USABLE, ALREADY_PLANNING, REPLAN, CONFIRM_ONLY }

        /**
         * The tap must never plan while a plan is already running (`planning`), and must never
         * re-plan from a position that is not usable.
         */
        fun tapAction(originGood: Boolean, hasPlan: Boolean, planning: Boolean): TapAction = when {
            !originGood -> TapAction.NOT_USABLE
            planning -> TapAction.ALREADY_PLANNING
            hasPlan -> TapAction.REPLAN
            else -> TapAction.CONFIRM_ONLY
        }

        fun atSavedHome(origin: LatLon, savedHome: LatLon): Boolean =
            Geo.distanceM(origin, savedHome) <= NEAR_SAVED_HOME_M

        /**
         * "12424 18th St E, Edgewood, WA 98372" when the rider is at the saved home, otherwise
         * "your current position (±5 m)". A noun phrase, so it fits inside a sentence.
         */
        fun label(accuracyM: Float?, atSavedHome: Boolean, savedHomeLabel: String): String = when {
            atSavedHome -> savedHomeLabel
            accuracyM == null -> "your current position"
            else -> "your current position (±" + fmt(accuracyM.toDouble(), 0) + " m)"
        }

        /** Shown the instant the button is tapped, before the GPS has answered. */
        const val CHECKING: String = "Checking your position\u2026"

        /** "Planning from <label>." */
        fun planningFromSentence(label: String): String = "Planning from " + label + "."

        /**
         * The explicit confirmation the rider gets after a tap, so the tap is never invisible.
         * Null for NOT_USABLE, which is answered by [notUsable] instead.
         */
        fun confirmation(label: String, action: TapAction): String? = when (action) {
            TapAction.REPLAN -> "Updated your position. Planning again from " + label + "."
            TapAction.ALREADY_PLANNING -> "Updated your position. A plan from " + label + " is already under way."
            TapAction.CONFIRM_ONLY -> "Updated your position. Planning from " + label + ". Tap Plan ride to go."
            TapAction.NOT_USABLE -> null
        }

        /** Rider language for a fix too old or too vague, with no silent re-plan. */
        fun notUsable(ageSeconds: Long?, accuracyM: Float?): String {
            val reason = when {
                ageSeconds != null && ageSeconds > 60 -> "the last position is " + ageSeconds + " s old"
                accuracyM != null && accuracyM > 50f ->
                    "the position is only accurate to about " + fmt(accuracyM.toDouble(), 0) + " m"
                else -> "no position has arrived yet"
            }
            return "Your position is not usable yet: " + reason +
                ". The ride still starts from where it did, and nothing was planned again."
        }

        /** The settings row wording, so the two ideas cannot be confused. */
        const val SAVED_HOME_EXPLANATION: String =
            "A ride always starts and finishes where you are, wherever that is. This saved home only " +
                "marks the area the trail and traffic data bundled with this app covers."

        /**
         * The home bundled in `assets/config.json` is labelled "Home — <address>", which is exactly
         * the word this change retires. Normalise it once, so a row called "Saved home" does not
         * repeat the old idea back at the rider.
         */
        fun savedHomeValue(bundledLabel: String): String =
            bundledLabel.removePrefix("Home \u2014 ").removePrefix("Home - ").trim()

        /** The label for a home the rider saves themselves; the bundled label is kept when it fits. */
        fun savedHomeLabel(nearBundledArea: Boolean, bundledLabel: String, today: String): String =
            if (nearBundledArea) bundledLabel else "Spot you saved on " + today

        fun savedHomeMessage(label: String): String =
            "Saved. " + label + " is now your saved home. The trails and traffic counts in this app " +
                "have not moved: they still cover the area around the home it shipped with."

        const val FORGOTTEN_MESSAGE: String =
            "Back to the home this app shipped with."
    }
}
