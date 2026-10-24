package com.biketrailplanner

import com.biketrailplanner.data.MapAssetReader
import com.biketrailplanner.data.RegionalMerge
import com.biketrailplanner.data.RegionalTrailLoader
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D23 answer (a), docs/REGIONAL-TRAILS.md: the bundled regional set. Every test here is offline - the
 * asset is either the one that ships or a hand-written one whose geometry is known exactly.
 */
class RegionalTrailLoaderTest {

    private val home = LatLon(47.240371, -122.262019)

    /** A straight east-west line, [km] long, starting at [start]. */
    private fun line(start: LatLon, km: Double): String {
        val degrees = km / (111.320 * Math.cos(Math.toRadians(start.lat)))
        return "[[${start.lat},${start.lon}],[${start.lat},${start.lon + degrees}]]"
    }

    private fun asset(vararg entries: String): MapAssetReader = MapAssetReader(
        mapOf(
            "trails_regional.json" to
                """{"schema_version":1,"verified":false,"trails":[${entries.joinToString(",")}]}""",
        ),
    )

    private fun entry(
        id: Long,
        name: String,
        segments: List<String>,
        surface: String? = "gravel",
        loop: Boolean = false,
        km: Double = 4.0,
    ): String = """{"id":$id,"name":"$name","route":"bicycle",""" +
        (surface?.let { """"surface":"$it",""" } ?: "") +
        """"region":"wa","length_km_clipped":$km,"offroad_share":1.0,"onroad_km_removed":0.0,""" +
        """"loop":$loop,"verified":false,"segments":[${segments.joinToString(",")}]}"""

    // ---- the shipped file ----------------------------------------------------------------------

    @Test
    fun `the shipped regional file loads every relation as an unverified trail`() {
        val loader = RegionalTrailLoader(Assets.reader())
        val loaded = loader.load(home, Assets.repository().network.trails)

        // 191 relations ship; 5 of them repeat a bundled corridor and are left out and reported.
        assertEquals(191, loaded.trails.size + loaded.coveredByBundled.size)
        assertEquals(186, loaded.trails.size)
        assertEquals(5, loaded.coveredByBundled.size)
        // 14,550 points ship in the file; the 120 belonging to the five covered relations are not
        // loaded into a Trail at all.
        assertEquals(14430, loaded.points)
        assertEquals(87, loaded.multiPiece)
        loaded.trails.forEach { trail ->
            assertFalse("${trail.name} must not claim a verification", trail.verified)
            assertTrue("${trail.name} has a relation id", trail.osmRelationId > 0)
            assertTrue("${trail.name} has rideable geometry", trail.rideableSpans.isNotEmpty())
            assertTrue("${trail.name} has no closures claimed", trail.closures.isEmpty())
            assertEquals("${trail.name} claims no closed metres", 0.0, trail.closedM, 1e-9)
            assertTrue("${trail.name} has a length", trail.lengthM > 0.0)
        }
    }

    @Test
    fun `the file is decoded once and the trail names are all different`() {
        val loader = RegionalTrailLoader(Assets.reader())
        assertSame("the file must be decoded once, not per plan", loader.rows(), loader.rows())

        val bundled = Assets.repository().network.trails
        val names0 = bundled.map { it.name }
        val loaded = loader.load(home, bundled)
        val names = loaded.trails.map { it.name }
        assertEquals("no two trails may share a name", names.size, names.distinct().size)
        assertTrue(
            "a regional relation a bundled trail already covers must be left out, not merged in",
            names.none { it in names0 },
        )
        assertEquals(
            "and left out loudly: measured, five of the shipped relations repeat a bundled corridor",
            5,
            loaded.coveredByBundled.size,
        )
        assertTrue(
            "the covered relation is named and explained",
            loaded.coveredByBundled.any { it.relationId == 9605519L && it.reason.contains("verified") },
        )
    }

    @Test
    fun `a multi-piece trail ships its pieces as separate rideable spans`() {
        val loader = RegionalTrailLoader(Assets.reader())
        val loaded = loader.load(home)
        // Measured from docs/REGIONAL-TRAILS.md: the build removed 21 road pieces from this trail.
        val palouse = loaded.trails.first { it.osmRelationId == 1889593L }
        assertEquals(21, palouse.rideableSpans.size)
        assertEquals(388.45, palouse.lengthM / 1000.0, 0.01)
        assertEquals(
            "the shipped length is the sum of the shipped pieces, not a walk across the removed roads",
            palouse.lengthM,
            palouse.rideableSpans.sumOf { it.lengthM },
            50.0,
        )
    }

    @Test
    fun `access points are capped, negative, unique and never claim a measured approach`() {
        val loader = RegionalTrailLoader(Assets.reader())
        val loaded = loader.load(home)
        val byTrail = loaded.accessPoints.groupBy { it.trailName }
        assertTrue("every trail gets an entrance near the rider", byTrail.isNotEmpty())
        val ids = HashSet<Long>()
        loaded.accessPoints.forEach { ap ->
            assertTrue("a sampled point is not an OSM node: ${ap.osmNodeId}", ap.osmNodeId < 0)
            assertTrue("ids must be unique", ids.add(ap.osmNodeId))
            assertEquals("the approach distance is unknown, not guessed", null, ap.probeRouteKmFromHome)
            assertEquals(null, ap.onRoad)
            assertTrue(ap.sharesTrailNode)
            assertEquals(
                "the crow-flies distance must be the real one",
                Geo.distanceM(home, ap.point),
                ap.straightLineMFromHome,
                0.5,
            )
        }
        byTrail.values.forEach { list ->
            assertTrue("at most twelve entrances per trail, got ${list.size}", list.size <= 12)
            assertTrue("at least the two ends", list.size >= 2)
        }
        // The nearest twelve, not an arbitrary twelve: the list is sorted by distance from the rider.
        val distances = byTrail.values.first().map { it.straightLineMFromHome }
        assertEquals(distances.sorted(), distances)
    }

    // ---- a hand-written file, so the sampling itself is checked --------------------------------

    @Test
    fun `sampling keeps both ends and a point about every 300 m, capped at twelve`() {
        val start = LatLon(47.0, -122.0)
        val loader = RegionalTrailLoader(
            asset(entry(1L, "Synthetic Rail Trail", listOf(line(start, 4.0)))),
        )
        // The rider stands at the west end, so the twelve nearest samples are the first twelve.
        val loaded = loader.load(start)
        val points = loaded.accessPoints
        assertEquals(12, points.size)
        assertEquals("the end nearest the rider is always kept", start.lat, points.first().point.lat, 1e-6)
        assertEquals("...and so is its longitude", start.lon, points.first().point.lon, 1e-6)
        val gaps = points.zipWithNext { a, b -> Geo.distanceM(a.point, b.point) }
        gaps.forEach { gap -> assertEquals("interior spacing is about 300 m", 300.0, gap, 5.0) }
        assertTrue(
            "the far end is dropped by the cap, not offered as a way in",
            points.none { Geo.distanceM(it.point, loaded.trails.single().rideableSpans.single().points.last()) < 60.0 },
        )
    }

    @Test
    fun `every span of a piecewise trail offers its own ends`() {
        val start = LatLon(47.0, -122.0)
        val gapStart = LatLon(47.0, -121.9)
        val loader = RegionalTrailLoader(
            asset(entry(2L, "Two Piece Trail", listOf(line(start, 0.5), line(gapStart, 0.5)), km = 1.0)),
        )
        val loaded = loader.load(start)
        val trail = loaded.trails.single()
        assertEquals(2, trail.rideableSpans.size)
        // 0.5 km per piece samples to its two ends plus one interior point: six entrances in total.
        assertEquals(6, loaded.accessPoints.size)
        val offered = loaded.accessPoints.map { it.point }.toSet()
        trail.rideableSpans.forEach { span ->
            assertTrue("a piece's west end must be an entrance", offered.contains(span.points.first()))
            assertTrue("a piece's east end must be an entrance", offered.contains(span.points.last()))
        }
    }

    @Test
    fun `a name already in use is given the relation id, and the surface is only claimed when tagged`() {
        val start = LatLon(47.0, -122.0)
        val loader = RegionalTrailLoader(
            asset(
                entry(11L, "Same Name Trail", listOf(line(start, 1.0)), surface = "asphalt", km = 1.0),
                entry(12L, "Same Name Trail", listOf(line(LatLon(47.1, -122.0), 1.0)), surface = null, km = 1.0),
            ),
        )
        val loaded = loader.load(start, Fixtures.trail("Same Name Trail", start, 1_000.0).let { listOf(it) })
        val names = loaded.trails.map { it.name }
        assertEquals(listOf("Same Name Trail (r11)", "Same Name Trail (r12)"), names)
        assertTrue("an asphalt tag is a paved claim", loaded.trails.first().paved)
        assertFalse("no surface tag is not a paved claim", loaded.trails.last().paved)
        assertEquals(
            "untagged metres are counted, so the app can keep saying it has no surface audit",
            1000.0,
            loaded.trails.last().untaggedSurfaceM,
            1.0,
        )
    }

    @Test
    fun `the synthetic ids sit far below anything the live discoverer can build`() {
        val loader = RegionalTrailLoader(asset(entry(1900725L, "X", listOf(line(home, 1.0)), km = 1.0)))
        val id = loader.syntheticId(1900725L, 3)
        assertTrue(id < 0)
        // The discoverer's scheme is -(relationId * 100_000 + index); measured, the largest real
        // relation id is under 10^8, so its ids stay two orders of magnitude above this range.
        assertTrue("a discovered id (-" + (1900725L * 100_000) + ") must never equal " + id, id < -1900725L * 100_000)
        assertNotEquals(loader.syntheticId(1900725L, 3), loader.syntheticId(1900725L, 4))
        assertNotEquals(loader.syntheticId(1900725L, 3), loader.syntheticId(1900726L, 3))
    }

    @Test
    fun `merging keeps the bundled network intact and appends the regional trails`() {
        val bundled = Assets.repository().network
        val loaded = RegionalTrailLoader(Assets.reader()).load(home, bundled.trails)
        val merged = RegionalMerge.network(bundled, loaded)

        assertEquals(bundled.trails.size + loaded.trails.size, merged.trails.size)
        assertEquals("the bundled six lead the list", bundled.trails.size, merged.trails.take(bundled.trails.size).size)
        assertEquals("corridors are the bundled ones", bundled.corridors, merged.corridors)
        assertEquals("joins are the bundled ones", bundled.joins, merged.joins)
        assertEquals(
            "the bundled trails keep the very flags they shipped with (two of the six are unverified)",
            bundled.trails.map { it.name to it.verified },
            merged.trails.take(bundled.trails.size).map { it.name to it.verified },
        )
        assertTrue("a bundled name still resolves to the bundled trail", merged.trail(bundled.trails.first().name)!!.verified)
        assertTrue(
            "nothing the region adds may be verified - a regional trail has had no check at all",
            merged.trails.drop(bundled.trails.size).none { it.verified },
        )
    }

    @Test
    fun `a file that cannot be read is not a reason to refuse to plan`() {
        val loader = RegionalTrailLoader(MapAssetReader(emptyMap()))
        val loaded = runCatching { loader.load(home) }.getOrElse { RegionalTrailLoader.Loaded.NONE }
        assertTrue(loaded.empty)
    }
}
