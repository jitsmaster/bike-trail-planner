package com.biketrailplanner.domain

/**
 * D40: which streets may become a D13 via-point.
 *
 * A via-point is the planner's way around a road it will not ride: it forces the route through a
 * named quiet street. Until D40 the only eligible streets were those with a MEASURED AADT in the low
 * or medium band - and near home the streets that carry a published count are the BIGGER roads, so
 * the small residential streets the rider actually wants could never become a via-point. That is the
 * code-level reason the generator kept choosing arterials.
 *
 * So a street is eligible in one of two ways, and the two are labelled differently so the rider can
 * tell them apart:
 *
 *  - **measured** ([measuredQuiet], D13 rule 1, unchanged): a real count, low or medium band. This is
 *    still tried first, because a measured quiet road is the best evidence the app has.
 *  - **by class** ([classQuiet], new): no count exists, but the street's road class has a class
 *    default speed of at most [MAX_CLASS_MPH], and any posted limit agrees. Valhalla's own Washington
 *    defaults put residential at 18 mph - the classes admitted here are the ones whose default is
 *    genuinely under 25, never tertiary (39) or above.
 *
 * Two honesty rules hold on both paths:
 *  - **a measurement always wins.** A small street with a count of its own is judged by that count,
 *    so a residential street carrying 9,000 vehicles a day is NOT a quiet link.
 *  - **no speed is invented.** Choosing a street by class does not claim a speed for it: the route is
 *    graded by the D10 ladder as before, and an unposted, uncounted street still grades UNGRADED, so
 *    the ride can still come back UNVERIFIED. The via-point only steers the route; it never marks
 *    anything safe.
 *
 * Pure logic and no Android or network types, so it is unit-tested directly (`QuietLinksTest`).
 */
data class QuietWay(
    val wayId: Long,
    /** A point ON the street, used as the via-point. */
    val point: LatLon,
    /** The street name, bare: this is what the AI strategist names and what is matched against. */
    val label: String,
    val band: VolumeBand,
    val aadt: Int?,
    val aadtYear: Int?,
    val lts: Int?,
    /** Posted speed in mph, or null when OSM has none. */
    val speedMph: Int?,
    val speedSource: SpeedSource,
    val roadClass: String?,
    val use: String?,
) {
    /**
     * D13 rule 1 as it always was: low volume first, then medium - and both only with a REAL count.
     * A published 0 means "Not Known" (D14), never "no traffic".
     */
    val measuredQuiet: Boolean
        get() = aadt != null && aadt > 0 && (band == VolumeBand.LOW || band == VolumeBand.MEDIUM)

    /**
     * D40: a small street chosen by its road class, because no count exists to choose it by.
     *
     * Refused when a measurement exists (it decides instead) and when a posted limit says the street
     * is not quiet - which is exactly the mistake this rule could otherwise make: a residential
     * street signed at 35 mph is not a quiet street.
     */
    val classQuiet: Boolean
        get() {
            if (!QuietLinks.isSmallRoad(roadClass, use)) return false
            if (aadt != null && aadt > 0) return false
            if (speedMph != null && speedMph > QuietLinks.MAX_CLASS_MPH) return false
            return true
        }

    /** D13 rule 1 and D40 together: the streets this app is willing to force a route through. */
    val eligible: Boolean get() = measuredQuiet || classQuiet

    /** 0 = measured low volume, 1 = small street by class, 2 = measured medium volume. */
    val rank: Int
        get() = when {
            measuredQuiet && band == VolumeBand.LOW -> 0
            classQuiet -> 1
            else -> 2
        }

    /**
     * The label for a rider-facing mention: the street name, plus the reason it was chosen when the
     * reason is not a count. A measured way keeps its bare name here because [ViaRef.text] appends the
     * count itself, and it must not be printed twice.
     */
    val viaLabel: String
        get() = if (measuredQuiet) label else label + " (" + QuietLinks.basis(this) + ")"
}

object QuietLinks {

    /**
     * The road classes admitted by class ([QuietLinks] doc). These mirror the grader's own
     * `residentialClasses` plus `living_street`, and each has a class default speed under 25 mph in
     * Valhalla's Washington table (residential 18). Tertiary (39) and anything larger are excluded on
     * purpose: their defaults are over the D10 quiet threshold, so admitting them would be a lie.
     */
    val SMALL_CLASSES: Set<String> = setOf("residential", "service", "service_other", "living_street")

    /** The speed a street chosen by class must not exceed. D10's own quiet threshold. */
    const val MAX_CLASS_MPH: Int = 25

    /** True for the classes whose own default speed is at or under [MAX_CLASS_MPH]. */
    fun isSmallRoad(roadClass: String?, use: String?): Boolean =
        (roadClass != null && roadClass in SMALL_CLASSES) || use == "living_street"

    /**
     * The rider's word for a road class. The screens must not print OSM vocabulary - the app's own UI
     * check bans `tertiary`, `service_other` and the rest of the tag words (`tools/verify_ui.py`) -
     * so the class is translated here, once.
     */
    fun riderClass(roadClass: String?, use: String?): String = when {
        use == "living_street" || roadClass == "living_street" -> "living street"
        roadClass == "residential" -> "residential street"
        roadClass == "service" || roadClass == "service_other" -> "service road"
        roadClass != null -> roadClass
        else -> "small street"
    }

    /** Why this street is eligible, in words, for the rider-facing label. */
    fun basis(way: QuietWay): String {
        val kind = riderClass(way.roadClass, way.use)
        return when {
            way.measuredQuiet -> "measured volume"
            way.speedMph != null -> kind + " at " + way.speedMph + " mph, no count"
            else -> kind + ", no posted limit"
        }
    }

    /**
     * The via-point pool, best evidence first: measured low volume, then small streets by class, then
     * measured medium volume. Bounded by [limit], and ordered by way id as the last tiebreak so two
     * runs from one fix try the same streets in the same order.
     */
    fun select(ways: Collection<QuietWay>, limit: Int): List<QuietWay> {
        if (limit <= 0) return emptyList()
        return ways.filter { it.eligible }
            .sortedWith(compareBy({ it.rank }, { it.aadt ?: Int.MAX_VALUE }, { it.wayId }))
            .take(limit)
    }

    /**
     * Which observation of one street to keep — the BEST evidence, not the first.
     *
     * A small street is admitted on its class (D40) and the same street can be seen again on another
     * traced candidate, this time with a real count attached. Keeping the first sighting would hold the
     * weaker evidence and print the weaker reason beside a better one the app had already seen. The
     * order of evidence is: a measured count, then a posted limit, then the road class alone.
     */
    fun best(previous: QuietWay?, seen: QuietWay): QuietWay =
        if (previous == null || quality(seen) > quality(previous)) seen else previous

    /** Evidence quality of one sighting: 2 = a measured count, 1 = a posted limit, 0 = class alone. */
    private fun quality(way: QuietWay): Int = when {
        way.measuredQuiet -> 2
        way.speedMph != null -> 1
        else -> 0
    }

    /**
     * What the rider is told about the pool, without overclaiming: the old wording said every link
     * had a measured volume, which stopped being true the moment class-based streets were admitted.
     */
    fun summary(ways: List<QuietWay>): String {
        val measured = ways.count { it.measuredQuiet }
        val byClass = ways.size - measured
        return when {
            byClass == 0 -> ways.size.toString() + " quiet link(s) with a measured volume"
            measured == 0 -> byClass.toString() + " small street(s) under " + MAX_CLASS_MPH +
                " mph by road class"
            else -> measured.toString() + " quiet link(s) with a measured volume and " + byClass +
                " small street(s) by road class"
        }
    }
}
