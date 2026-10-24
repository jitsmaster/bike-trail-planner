package com.biketrailplanner

import com.biketrailplanner.data.Maneuver
import com.biketrailplanner.data.RouteResult
import com.biketrailplanner.data.TrailRepository
import com.biketrailplanner.data.MapAssetReader
import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.Corridor
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.GradedSegment
import com.biketrailplanner.domain.GradeBreakdown
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.NamedSegment
import com.biketrailplanner.domain.PlannedRoute
import com.biketrailplanner.domain.PolicyVerdict
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RawEdge
import com.biketrailplanner.domain.RideTrack
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.SpeedSource
import com.biketrailplanner.domain.Trail
import com.biketrailplanner.domain.TrailJoin
import com.biketrailplanner.domain.TrailNetwork
import java.io.File

/** Reads the real bundled assets straight off disk, so the tests grade the shipped data. */
object Assets {
    fun reader(): MapAssetReader {
        val dir = File("src/main/assets")
        check(dir.isDirectory) { "run the tests from the app module; ${dir.absolutePath} not found" }
        val map = dir.listFiles()!!.associate { it.name to it.readText() }
        return MapAssetReader(map)
    }

    fun repository(): TrailRepository.Loaded = TrailRepository(reader()).load()
}

object Fixtures {

    fun edge(
        wayId: Long = 1L,
        roadClass: String? = "tertiary",
        use: String? = "road",
        surface: String? = "paved_smooth",
        cycleLane: String = "none",
        bicycleNetwork: Int = 0,
        speedLimitKph: Int? = null,
        lengthM: Double = 100.0,
        name: String = "Test Street",
        unpaved: Boolean = false,
    ) = RawEdge(wayId, roadClass, use, surface, unpaved, cycleLane, bicycleNetwork, false, speedLimitKph, lengthM, listOf(name))

    fun segment(grade: Grade, metres: Double, name: String = "Street", speed: Int? = null): GradedSegment =
        GradedSegment(grade, metres, listOf(name), listOf(1L), "tertiary", "road", speed,
            if (speed == null) SpeedSource.NONE else SpeedSource.OSM, "test")

    /** D59/D60: a recorded ride, with the graded street segments a trace would have produced. */
    fun rideTrack(
        signature: String = "ride-1",
        points: List<LatLon> = listOf(LatLon(47.2400, -122.2620), LatLon(47.2500, -122.2620)),
        named: List<NamedSegment> = listOf(rideSegment("126th Avenue Court East", 1000.0, Grade.C)),
        label: String = "Recorded ride",
    ): RideTrack = RideTrack(
        signature = signature,
        startedAtMs = 1_000L,
        endedAtMs = 601_000L,
        distanceM = points.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum(),
        movingMs = 600_000L,
        maxSpeedMps = 5.0,
        gaps = 0,
        rejectedFixes = 0,
        points = points,
        wayIds = setOf(1L),
        named = named,
        gradedM = named.filter { it.grade != Grade.UNGRADED }.sumOf { it.metres },
        ungradedM = named.filter { it.grade == Grade.UNGRADED }.sumOf { it.metres },
        rejectedM = 0.0,
        breakdownTotalM = named.sumOf { it.metres },
        byGrade = named.filter { it.grade != Grade.UNGRADED && it.grade != Grade.REJECTED }
            .groupBy { it.grade }.mapValues { entry -> entry.value.sumOf { it.metres } },
        safetyStale = false,
        dataFingerprint = "test-fingerprint",
        label = label,
        createdAt = 2_000L,
    )

    /** A traced street segment, as the grader hands it to the ride. */
    fun rideSegment(name: String, metres: Double, grade: Grade, speedText: String = "25 mph") =
        NamedSegment(name = name, metres = metres, grade = grade, roadClass = "residential", speedText = speedText)

    fun breakdown(vararg pairs: Pair<Grade, Double>): GradeBreakdown {
        val byGrade = pairs.toMap()
        val graded = pairs.filter { it.first != Grade.UNGRADED && it.first != Grade.REJECTED }.sumOf { it.second }
        val ungraded = byGrade[Grade.UNGRADED] ?: 0.0
        val rejected = byGrade[Grade.REJECTED] ?: 0.0
        return GradeBreakdown(graded + ungraded + rejected, byGrade, graded, ungraded, rejected)
    }

    /** A straight trail heading north-east from [start]. */
    fun trail(
        name: String,
        start: LatLon,
        lengthM: Double,
        loop: Boolean = false,
        closures: List<com.biketrailplanner.domain.TrailClosure> = emptyList(),
    ): Trail {
        val points = ArrayList<LatLon>()
        if (loop) {
            // a real ring: the last point is the first one
            val radius = lengthM / (2 * Math.PI)
            val steps = 36
            for (i in 0..steps) {
                val angle = 2 * Math.PI * i / steps
                val dLat = radius * Math.cos(angle) / 111_320.0
                val dLon = radius * Math.sin(angle) / (111_320.0 * Math.cos(Math.toRadians(start.lat)))
                points.add(LatLon(start.lat + dLat, start.lon + dLon))
            }
        } else {
            val steps = 20
            val dLat = lengthM / steps / 111_320.0
            for (i in 0..steps) points.add(LatLon(start.lat + dLat * i, start.lon))
        }
        val full = Polyline(points)
        val rideable = if (closures.isEmpty()) listOf(full) else {
            // split in half around the closure
            listOf(Polyline(full.subLine(0.0, lengthM * 0.4)), Polyline(full.subLine(lengthM * 0.6, lengthM)))
        }
        return Trail(
            name = name, osmRelationId = name.hashCode().toLong(), osmUrl = "", lengthM = lengthM,
            rideableM = lengthM, paved = true, loop = loop, verified = true, surface = listOf("asphalt"),
            untaggedSurfaceM = 0.0, nearestFromHomeM = 1000.0, farthestFromHomeM = 2000.0,
            closedM = if (closures.isEmpty()) 0.0 else lengthM * 0.2, closures = closures,
            fullGeometry = full, rideableSpans = rideable,
        )
    }

    fun network(trails: List<Trail>, joins: List<TrailJoin> = emptyList()): TrailNetwork =
        TrailNetwork(trails, joins, listOf(Corridor("A", "test corridor", trails.map { it.name })), emptyList())

    fun accessPoint(trail: String = "Test Trail", point: LatLon = LatLon(47.25, -122.25)) = AccessPoint(
        trailName = trail, osmRelationId = 1L, osmNodeId = 42L, point = point, onRoad = "Test Street",
        highway = "tertiary", sharesTrailNode = true, straightLineMFromHome = 1000.0, probeRouteKmFromHome = 1.5,
    )

    fun routeResult(
        points: List<LatLon>,
        lengthM: Double,
        maneuvers: List<Maneuver> = emptyList(),
    ) = RouteResult(
        shape = com.biketrailplanner.domain.PolylineCodec.encode(points),
        points = points, lengthM = lengthM, timeS = 0.0, maneuvers = maneuvers,
        baseUrl = "http://127.0.0.1:8002", usedFallback = false,
    )

    /** A tidy synthetic plan: [commuteEdgeCount] road edges, then a trail leg. */
    fun plannedRoute(
        home: LatLon = LatLon(47.240371, -122.262019),
        roadSegments: List<GradedSegment> = listOf(segment(Grade.C, 1000.0)),
        trailOneWayM: Double = 2000.0,
    ): PlannedRoute {
        val ap = accessPoint(point = LatLon(47.25, -122.25))
        val straightCommute = Polyline(listOf(home, ap.point))
        val commute = routeResult(
            points = straightCommute.points,
            lengthM = Polyline(straightCommute.points).lengthM,
            maneuvers = listOf(Maneuver("Turn left onto Nowhere Road", 100.0, 90, LatLon(47.245, -122.255))),
        )
        val trailPoints = listOf(LatLon(47.25, -122.25), LatLon(47.26, -122.24), LatLon(47.27, -122.23))
        val legs = listOf(com.biketrailplanner.domain.TrailRideLeg("Test Trail", 0.0, trailOneWayM, trailPoints))
        val all = roadSegments + roadSegments + listOf(segment(Grade.A, trailOneWayM * 2, "Test Trail"))
        val grader = SafetyGrader(LimitsTable.EMPTY)
        val breakdown = grader.breakdown(all)
        return PlannedRoute(
            signature = "test-signature",
            trailNames = listOf("Test Trail"),
            accessPoint = ap,
            turnaround = trailPoints.last(),
            commute = commute,
            trailLegs = legs,
            trailOneWayM = trailOneWayM,
            trailRoundTripM = trailOneWayM * 2,
            targetMiles = 10.0,
            capped = false,
            capReason = null,
            roundTrip = commute.points + trailPoints,
            segments = all,
            roadSegments = roadSegments,
            breakdown = breakdown,
            verdict = grader.evaluateD10(breakdown),
            named = emptyList(),
            cautions = emptyList(),
            ungraded = emptyList(),
            rejections = emptyList(),
            detours = emptyList(),
            detourOneWayM = 0.0,
            safetyNotes = emptyList(),
            notes = emptyList(),
        )
    }
}
