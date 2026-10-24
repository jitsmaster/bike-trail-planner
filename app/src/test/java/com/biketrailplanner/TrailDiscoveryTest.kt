package com.biketrailplanner

import com.biketrailplanner.data.DiscoveredTrailEntity
import com.biketrailplanner.data.DiscoveryCachePolicy
import com.biketrailplanner.data.DiscoveryMerge
import com.biketrailplanner.data.DiscoveryQuery
import com.biketrailplanner.data.InMemoryDiscoveryCache
import com.biketrailplanner.data.OverpassClient
import com.biketrailplanner.data.OverpassException
import com.biketrailplanner.data.OverpassHttpException
import com.biketrailplanner.data.OverpassMirrors
import com.biketrailplanner.data.OverpassTransport
import com.biketrailplanner.data.TrailDiscovery
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.RouteKey
import com.biketrailplanner.ui.RiderText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Live trail discovery (D23, docs/TRAIL-DISCOVERY.md): the Overpass query, the mirror rotation and
 * its freshness rule, turning an answer into the app's own Trail/AccessPoint model, the location-
 * bucketed cache, and merging with the bundled trails.
 *
 * The parsing tests run against a RECORDED real answer kept in docs/discovery-raw/, so they describe
 * what the public instances actually return, not what this test wishes they returned.
 */
class TrailDiscoveryTest {

    private val home = LatLon(47.240371, -122.262019)

    /** A fixed clock: freshness must not depend on when the suite runs. */
    private val now = Instant.parse("2026-09-16T19:00:00Z").toEpochMilli()

    private val de = OverpassMirrors.DE
    private val kumi = OverpassMirrors.KUMI

    // ---- recorded fixtures ----------------------------------------------------------------

    private val fixtureDir: File? =
        listOf(File("../docs/discovery-raw"), File("docs/discovery-raw")).firstOrNull { it.isDirectory }

    private fun fixture(name: String): String {
        val dir = fixtureDir
        assertNotNull("docs/discovery-raw is missing; the recorded answers must live in the repo", dir)
        val f = File(dir, name)
        assertTrue("recorded answer not found: ${f.path}", f.isFile)
        return f.readText()
    }

    /** The real 5 km answer for the home origin, recorded 2026-09-16 (7 relations, 102,827 bytes). */
    private fun nearHomeAnswer(): String = fixture("overpass-near-home-5km.json")

    // ---- stubs ----------------------------------------------------------------------------

    private class StubTransport(private val respond: (String) -> String) : OverpassTransport {
        val calls = ArrayList<String>()
        override suspend fun post(url: String, body: String): String {
            calls.add(url)
            return respond(url)
        }
    }

    private fun client(transport: OverpassTransport, mirrors: List<String> = OverpassMirrors.DEFAULT) =
        OverpassClient(mirrors = mirrors, transport = transport, clock = { now })

    private fun discovery(
        transport: OverpassTransport,
        cache: InMemoryDiscoveryCache? = null,
        clock: () -> Long = { now },
    ): TrailDiscovery = TrailDiscovery(
        client = OverpassClient(mirrors = OverpassMirrors.DEFAULT, transport = transport, clock = clock),
        cache = cache,
        clock = clock,
    )

    private fun answerBody(stamp: String, relations: List<String> = emptyList()): String =
        """{"version":0.6,"generator":"test","osm3s":{"timestamp_osm_base":"$stamp"},"elements":[${relations.joinToString(",")}]}"""

    private fun relation(
        id: Long,
        name: String?,
        ways: List<Pair<Long, List<LatLon>>>,
        route: String = "bicycle",
    ): String = buildString {
        append("""{"type":"relation","id":$id,"tags":{"type":"route","route":"$route"""")
        if (name != null) append(""","name":"$name"""")
        append("""},"members":[""")
        append(
            ways.joinToString(",") { (ref, points) ->
                """{"type":"way","ref":$ref,"role":"","geometry":[""" +
                    points.joinToString(",") { """{"lat":${it.lat},"lon":${it.lon}}""" } + "]}"
            },
        )
        append("]}")
    }

    private fun line(from: LatLon, segments: Int, stepM: Double = 20.0): List<LatLon> =
        (0..segments).map { from.copy(lat = from.lat + it * stepM / 111_320.0) }

    // ---- the query ------------------------------------------------------------------------

    @Test
    fun `the query asks for bicycle and mtb relations within the radius, geometry inline`() {
        val ql = DiscoveryQuery.text(home, 5.0)
        assertTrue(ql.startsWith("[out:json][timeout:25];"))
        assertTrue(ql.contains("relation(around:5000,47.240371,-122.262019)[\"route\"=\"bicycle\"];"))
        assertTrue(ql.contains("relation(around:5000,47.240371,-122.262019)[\"route\"=\"mtb\"];"))
        assertTrue(ql.trimEnd().endsWith("out geom;"))
        // The radius is the configured trail_radius_km, in metres.
        assertTrue(DiscoveryQuery.text(home, 12.5).contains("around:12500,"))
    }

    // ---- the recorded answer becomes trails and access points ------------------------------

    @Test
    fun `a recorded answer becomes trails with geometry, honest flags and access points`() {
        val transport = StubTransport { nearHomeAnswer() }
        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        assertEquals(
            listOf(
                "King County Interurban Trail",
                "Sumner Link Trail",
                "Riverwalk Trail",
                "Interurban Trail Milton",
                "White River Trail",
                "Lake Tapps Parkway Trail",
                "Interurban Trail Edgewood",
            ).sorted(),
            result.trails.map { it.name }.sorted(),
        )
        assertEquals(7, result.trails.size)
        assertEquals(de, result.mirror)
        assertEquals("2026-09-16T18:33:04Z", result.timestampOsmBase)

        val interurban = result.trails.single { it.name == "King County Interurban Trail" }
        // The full relation geometry comes back, in member order, with the shared node between ways
        // not repeated: 52 member ways, 530 points, 83.6 km.
        assertEquals(530, interurban.fullGeometry.points.size)
        assertEquals(83_589.0, interurban.lengthM, 250.0)
        assertTrue(interurban.osmUrl.endsWith("/relation/1900725"))

        for (trail in result.trails) {
            assertFalse("a discovered trail is never verified: ${trail.name}", trail.verified)
            assertTrue("no closures are known for ${trail.name}", trail.closures.isEmpty())
            assertEquals(0.0, trail.closedM, 1e-9)
            // No surface audit exists for a discovered trail, so nothing may be claimed paved.
            assertFalse(trail.paved)
            assertTrue(trail.surface.isEmpty())
            assertEquals(trail.lengthM, trail.untaggedSurfaceM, 1e-9)
            // Nothing is known to be closed, so the whole geometry counts as rideable.
            assertEquals(trail.lengthM, trail.rideableM, 1e-9)
            assertEquals(1, trail.rideableSpans.size)
            assertEquals(trail.lengthM, trail.rideableSpans.single().lengthM, 1e-9)
        }
    }

    @Test
    fun `access points keep both endpoints, cap at twelve, and never invent an approach distance`() {
        val transport = StubTransport { nearHomeAnswer() }
        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        assertEquals(78, result.accessPoints.size)
        for (trail in result.trails) {
            val aps = result.accessPoints.filter { it.trailName == trail.name }
            assertTrue("${trail.name} has too many access points: ${aps.size}", aps.size <= 12)
            assertTrue("${trail.name} has no access point", aps.size >= 2)
            // Both endpoints of the trail are always among them, however far away they are.
            assertTrue(aps.any { it.point == trail.fullGeometry.points.first() })
            assertTrue(aps.any { it.point == trail.fullGeometry.points.last() })
            for (ap in aps) {
                // Genuinely unknown: the planner MEASURES the approach live (Planner.liveApproachM).
                assertEquals(null, ap.probeRouteKmFromHome)
                assertEquals(null, ap.onRoad)
                assertEquals(trail.osmRelationId, ap.osmRelationId)
                assertTrue(ap.straightLineMFromHome > 0.0)
                // A sampled point is not an OSM node, and a negative id can never collide with one.
                assertTrue("synthetic ids are negative: ${ap.osmNodeId}", ap.osmNodeId < 0)
            }
            // Ids are unique, so two access points can never be confused for one another.
            assertEquals(aps.size, aps.map { it.osmNodeId }.distinct().size)
            // Nearest first: the planner ranks by approach, and crow-flies is only the fallback.
            assertEquals(aps.sortedBy { it.straightLineMFromHome }.map { it.osmNodeId }, aps.map { it.osmNodeId })
        }
        // A short trail is not padded out to twelve: Edgewood is 1.33 km, so it samples to six.
        assertEquals(6, result.accessPoints.count { it.trailName == "Interurban Trail Edgewood" })
    }

    @Test
    fun `the endpoints are always kept even when the trail runs far past the origin`() {
        // 20 km of straight trail, origin at the near end. The far endpoint is ~20 km away, so a
        // pure "twelve nearest" rule would drop it - it must not.
        val far = line(home, 1000)
        val body = answerBody("2026-09-16T18:30:00Z", listOf(relation(42L, "Long Test Trail", listOf(7L to far))))
        val transport = StubTransport { body }
        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        val trail = result.trails.single()
        val aps = result.accessPoints.filter { it.trailName == trail.name }
        assertEquals(12, aps.size)
        assertTrue(aps.any { it.point == far.first() })
        assertTrue(aps.any { it.point == far.last() })
        // Interior points are every ~300 m, so the nearest ten sit about 3 km out at most.
        val nearTen = aps.sortedBy { it.straightLineMFromHome }.take(10)
        assertTrue(nearTen.last().straightLineMFromHome < 3_200.0)
        for (i in 1 until nearTen.size) {
            val gap = Geo.distanceM(nearTen[i - 1].point, nearTen[i].point)
            assertTrue("interior spacing should be ~300 m, was $gap", gap in 280.0..320.0)
        }
    }

    @Test
    fun `a repeat of the same relation is collapsed and reported`() {
        // The real near-home answer contains no duplicate relation, so the duplicate is injected:
        // the recorded Sumner Link relation is repeated under a new id with the same ways and name,
        // which is exactly the shape (a relation and its copy) the collapse exists for.
        val root = Json.parseToJsonElement(nearHomeAnswer()).jsonObject
        val elements = (root["elements"] as JsonArray)
        val sumner = elements.map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == "9605518" }
        assertNotNull("the recorded answer should still carry the Sumner Link relation", sumner)
        val copy = JsonObject(sumner + ("id" to JsonPrimitive(9999999L)))
        val doubled = JsonObject(root + ("elements" to JsonArray(elements + copy))).toString()
        assertEquals(8, (Json.parseToJsonElement(doubled).jsonObject["elements"] as JsonArray).size)

        val transport = StubTransport { doubled }
        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        assertEquals(7, result.trails.size)
        assertEquals(1, result.duplicatesCollapsed.size)
        assertTrue(result.duplicatesCollapsed.single().contains("r9999999"))
        assertEquals(78, result.accessPoints.size)
    }

    @Test
    fun `a second relation with the same name on the same corridor is collapsed`() {
        // No duplicate exists in the regional data near home (measured 2026-09-16: 38 relations
        // within 25 km, zero duplicate pairs), so this is the shape itself: different member ways
        // and the same name on the same line, which is what a superrelation or a re-mapped
        // duplicate looks like.
        val points = line(home, 300)
        val a = relation(100L, "Twin Trail", listOf(1L to points))
        val duplicate = relation(200L, "Twin Trail", listOf(2L to points.dropLast(1)))
        val otherCorridor = relation(300L, "Twin Trail", listOf(3L to line(LatLon(48.5, -121.0), 300)))
        val body = answerBody("2026-09-16T18:30:00Z", listOf(a, duplicate, otherCorridor))

        val result = runBlocking { discovery(StubTransport { body }).discover(home, 5.0) }

        assertEquals(2, result.trails.size)
        assertEquals(1, result.duplicatesCollapsed.size)
        assertTrue(result.duplicatesCollapsed.single().contains("r200"))
        // The same name on a genuinely different corridor is NOT a duplicate, and it cannot reuse
        // the first trail's name either.
        assertEquals(listOf("Twin Trail", "Twin Trail (r300)"), result.trails.map { it.name })
    }

    // ---- an empty answer is not a verdict -----------------------------------------------------

    @Test
    fun `an empty answer from one mirror is not a verdict - another mirror is asked`() {
        val fresh = "2026-09-16T18:30:00Z"
        val empty = answerBody(fresh)
        val found = answerBody(fresh, listOf(relation(77L, "Second Mirror Trail", listOf(7L to line(home, 200)))))
        val transport = StubTransport { url -> if (url == de) empty else found }

        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        // The second mirror's data wins, and the first mirror's "nothing here" is on the record.
        assertEquals(listOf("Second Mirror Trail"), result.trailNames)
        assertEquals(kumi, result.mirror)
        assertEquals(listOf(de), result.emptyMirrors)
        assertFalse(result.emptyConfirmed)
        assertEquals(2, transport.calls.size)
        assertTrue(result.attempts.first().outcome.startsWith("EMPTY"))
        assertTrue(result.attempts.last().outcome.startsWith("OK"))
    }

    @Test
    fun `two mirrors that both find nothing confirm that there is nothing here`() {
        val transport = StubTransport { answerBody("2026-09-16T18:30:00Z") }

        val result = runBlocking { discovery(transport).discover(home, 5.0) }

        assertTrue(result.empty)
        assertTrue(result.emptyConfirmed)
        assertEquals(listOf(de, kumi), result.emptyMirrors)
        // Bounded: exactly one extra mirror, never a third question.
        assertEquals(2, transport.calls.size)
        assertTrue(result.attempts.last().outcome.startsWith("EMPTY: confirmed"))
        val summary = DiscoveryMerge.summary(result, now)
        assertTrue(summary, summary.contains("no trails found within 5.0 km"))
        assertTrue(summary, summary.contains("confirmed by overpass-api.de and overpass.kumi.systems"))
    }

    @Test
    fun `one mirror with nobody left to ask is reported as one mirror's word`() {
        val client = client(StubTransport { answerBody("2026-09-16T18:30:00Z") }, listOf(de))

        val answer = runBlocking { client.query(DiscoveryQuery.text(home, 5.0)) }

        assertEquals(0, answer.relationsInAnswer)
        assertEquals(listOf(de), answer.emptyMirrors)
        // Nothing was confirmed by a second instance, so the app must not present it as a verdict.
        assertFalse(answer.emptyConfirmed)
    }

    // ---- stale cache beats no answer ----------------------------------------------------------

    @Test
    fun `when every mirror fails an expired cached answer is returned, marked stale`() {
        val cache = InMemoryDiscoveryCache()
        runBlocking {
            cache.put(
                DiscoveredTrailEntity(
                    bucket = RouteKey.bucket(home),
                    originLat = home.lat,
                    originLon = home.lon,
                    radiusKm = 5.0,
                    payload = nearHomeAnswer(),
                    // 40 days old: past the 30-day TTL, so the network is tried first.
                    fetchedAt = now - 40L * 24 * 60 * 60 * 1000,
                    timestampOsmBase = "2026-08-07T00:00:00Z",
                    mirror = de,
                ),
            )
        }
        val transport = StubTransport { throw java.io.IOException("no route to host") }

        val result = runBlocking { discovery(transport, cache).discover(home, 5.0) }

        assertTrue(result.fromStaleCache)
        assertTrue(result.fromCache)
        assertFalse(result.empty)
        assertEquals(7, result.trails.size)
        assertEquals(de, result.mirror)
        assertEquals("2026-08-07T00:00:00Z", result.timestampOsmBase)
        assertTrue(result.fetchedAgeMs(now) > DiscoveryCachePolicy.TTL_MS)
        // The rider is told why the answer is old, and which mirrors were tried.
        assertTrue(result.staleReason!!.contains("overpass-api.de"))
        assertTrue(result.staleReason!!.contains("kumi"))
        assertEquals(OverpassMirrors.DEFAULT.size, transport.calls.size)
        val summary = DiscoveryMerge.summary(result, now)
        assertTrue(summary, summary.contains("Overpass could not be reached"))
        assertTrue(summary, summary.contains("cached answer"))
        assertTrue(summary, summary.contains("40 days"))
        assertTrue(summary, summary.contains("may be out of date"))
    }

    @Test
    fun `when every mirror fails and nothing is cached the failure is reported`() {
        val cache = InMemoryDiscoveryCache()
        val transport = StubTransport { throw java.io.IOException("no route to host") }

        val failure = runCatching { runBlocking { discovery(transport, cache).discover(home, 5.0) } }
            .exceptionOrNull()

        assertTrue("expected OverpassException, got $failure", failure is OverpassException)
        assertEquals(OverpassMirrors.DEFAULT.size, (failure as OverpassException).attempts.size)
        assertTrue(failure.summary.contains("overpass-api.de"))
    }

    @Test
    fun `a rate limit is reported as a rate limit and stops the lookup`() {
        // Measured 2026-09-20: overpass-api.de answers HTTP 429 on the third query of a burst (after
        // ~14 s). This used to arrive as "FAILED: IOException" - the status was thrown away - so the
        // rider was shown "IOException" and the cause was invisible.
        val transport = StubTransport { throw OverpassHttpException(OverpassClient.RATE_LIMIT, retryAfterS = 60) }

        val failure = runCatching {
            runBlocking { client(transport).query(DiscoveryQuery.text(home, 5.0)) }
        }.exceptionOrNull()

        assertTrue("expected OverpassException, got $failure", failure is OverpassException)
        val overpass = failure as OverpassException
        // ONE attempt: re-asking after a 429 is exactly what keeps the app blocked.
        assertEquals(1, overpass.attempts.size)
        assertTrue(overpass.attempts.single().outcome, overpass.attempts.single().outcome.contains("HTTP 429"))
        assertTrue(overpass.attempts.single().outcome, overpass.attempts.single().outcome.contains("retry after 60s"))
        assertTrue(overpass.rateLimited)
        // The rider gets the one thing they can act on, and no mirror URLs.
        val note = RiderText.Discovery.failed(overpass)
        assertTrue(note, note.contains("busy"))
        assertFalse(note, note.contains("overpass-api.de"))
    }

    @Test
    fun `a network fault keeps its own words, not the exception class name`() {
        val transport = StubTransport { throw java.net.UnknownHostException("overpass-api.de") }

        val failure = runCatching {
            runBlocking { client(transport).query(DiscoveryQuery.text(home, 5.0)) }
        }.exceptionOrNull() as OverpassException

        assertEquals("FAILED: no address for the mirror", failure.attempts.first().outcome)
    }

    @Test
    fun `a cached answer too narrow for this search is not used as a fallback`() {
        val cache = InMemoryDiscoveryCache()
        runBlocking {
            cache.put(
                DiscoveredTrailEntity(
                    bucket = RouteKey.bucket(home),
                    originLat = home.lat,
                    originLon = home.lon,
                    radiusKm = 1.0,
                    payload = nearHomeAnswer(),
                    fetchedAt = now - 40L * 24 * 60 * 60 * 1000,
                    timestampOsmBase = "2026-08-07T00:00:00Z",
                    mirror = de,
                ),
            )
        }
        val transport = StubTransport { throw java.io.IOException("no route to host") }

        // Answering a 5 km search with a 1 km answer would under-report, which is the wrong answer
        // this whole hardening exists to prevent - so it fails instead.
        val failure = runCatching { runBlocking { discovery(transport, cache).discover(home, 5.0) } }
            .exceptionOrNull()
        assertTrue("expected OverpassException, got $failure", failure is OverpassException)
    }

    // ---- mirror rotation and freshness ------------------------------------------------------

    @Test
    fun `a mirror serving stale data is skipped and the fresh mirror wins`() {
        // The first mirror's OSM data is six days old: past the 48 h stale age.
        val stale = answerBody("2026-09-10T00:00:00Z", listOf(relation(1L, "Stale Trail", listOf(11L to line(home, 40)))))
        val fresh = answerBody("2026-09-16T18:30:00Z", listOf(relation(2L, "Fresh Trail", listOf(22L to line(home, 40)))))
        val transport = StubTransport { url -> if (url == de) stale else fresh }
        val client = client(transport, listOf(de, kumi))

        val answer = runBlocking { client.query(DiscoveryQuery.text(home, 5.0)) }

        assertEquals(kumi, answer.mirror)
        assertEquals(listOf(de), answer.staleMirrors)
        assertEquals(2, transport.calls.size)
        assertTrue(answer.attempts.first().outcome.startsWith("STALE"))
        assertTrue(answer.attempts.first().outcome.contains("48 h stale age"))
        assertEquals("OK", answer.attempts.last().outcome)

        // The same story survives into what discovery returns, so the UI can say which mirror was
        // skipped and why.
        val discovered = runBlocking { discovery(transport).discover(home, 5.0) }
        assertEquals(listOf(de), discovered.attempts.filter { it.outcome.startsWith("STALE") }.map { it.mirror })
        assertEquals("2026-09-10T00:00:00Z", discovered.attempts.first().timestampOsmBase)
        // The data that won is the fresh mirror's, not the stale one's.
        val result = runBlocking { discovery(StubTransport { fresh }).discover(home, 5.0) }
        assertEquals(listOf("Fresh Trail"), result.trails.map { it.name })
    }

    @Test
    fun `data older than what another mirror already served is refused as well`() {
        // Both stamps are inside the 48 h window, so only the monotonic rule can reject the second.
        val newest = answerBody("2026-09-16T18:30:00Z", listOf(relation(1L, "Newest Trail", listOf(11L to line(home, 40)))))
        val lagging = answerBody("2026-09-16T06:00:00Z", listOf(relation(2L, "Lagging Trail", listOf(22L to line(home, 40)))))
        val bodies = HashMap<String, String>()
        bodies[de] = newest
        bodies[kumi] = lagging
        val transport = StubTransport { url -> bodies.getValue(url) }
        val client = client(transport, listOf(de, kumi))

        val first = runBlocking { client.query("first") }
        assertEquals(de, first.mirror)
        assertEquals(newest, first.json)

        // The reference mirror now serves yesterday's data; the client must not accept it.
        bodies[de] = lagging
        bodies[kumi] = newest
        val second = runBlocking { client.query("second") }
        assertEquals(kumi, second.mirror)
        assertTrue(second.attempts.first().outcome.contains("older than the newest data already seen"))
        assertEquals(newest, second.json)
        // one call for the first query, then the lagging mirror and the good one for the second
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `a mirror that does not date its data cannot be trusted`() {
        // Recorded 2026-09-16 from overpass.osm.ch: HTTP 200, no elements, and an
        // osm3s.timestamp_osm_base of "117059" that is not an instant at all.
        val broken = fixture("overpass-osm-ch-2026-09-16.json")
        val transport = StubTransport { broken }
        val client = client(transport, listOf(OverpassMirrors.OSM_CH))

        val failure = runCatching { runBlocking { client.query(DiscoveryQuery.text(home, 5.0)) } }
            .exceptionOrNull()

        assertTrue("expected OverpassException, got $failure", failure is OverpassException)
        val attempts = (failure as OverpassException).attempts
        assertEquals(1, attempts.size)
        assertTrue(attempts.single().outcome.contains("not an ISO-8601 instant"))
    }

    @Test
    fun `when every mirror fails the error lists the mirrors and hides the coordinates`() {
        val transport = StubTransport { url -> if (url == de) "" else throw java.io.IOException("no route") }
        val client = client(transport, listOf(de, kumi))
        val failure = runCatching { runBlocking { client.query(DiscoveryQuery.text(home, 5.0)) } }
            .exceptionOrNull() as OverpassException

        assertEquals(2, failure.attempts.size)
        assertTrue(failure.summary.contains("overpass-api.de"))
        assertFalse("the query must never be logged with the caller's position", failure.message!!.contains("47.24"))
        assertFalse(failure.summary.contains("47.24"))
    }

    // ---- the cache ---------------------------------------------------------------------------

    @Test
    fun `a second call for the same bucket does not touch the network`() {
        val transport = StubTransport { nearHomeAnswer() }
        val cache = InMemoryDiscoveryCache()
        val discovery = discovery(transport, cache)

        val first = runBlocking { discovery.discover(home, 5.0) }
        assertFalse(first.fromCache)
        assertEquals(1, transport.calls.size)

        // ~330 m away: the same ~1 km bucket, so the cached answer is used.
        val jittered = LatLon(home.lat + 0.003, home.lon)
        val second = runBlocking { discovery.discover(jittered, 5.0) }
        assertTrue(second.fromCache)
        assertEquals(1, transport.calls.size)
        assertEquals(first.trailNames, second.trailNames)
        assertEquals(first.accessPoints.size, second.accessPoints.size)
        assertEquals(first.timestampOsmBase, second.timestampOsmBase)
        assertEquals(de, second.mirror)

        // The rider asked again: the cache is skipped, and the answer is written back.
        val third = runBlocking { discovery.discover(home, 5.0, forceRefresh = true) }
        assertFalse(third.fromCache)
        assertEquals(2, transport.calls.size)

        // A different bucket is a different question.
        runBlocking { discovery.discover(LatLon(home.lat + 0.5, home.lon), 5.0) }
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `a cached answer older than the ttl is not used`() {
        val transport = StubTransport { nearHomeAnswer() }
        val cache = InMemoryDiscoveryCache()
        // A row written one millisecond past the TTL: old enough that the app must ask again.
        runBlocking {
            cache.put(
                DiscoveredTrailEntity(
                    bucket = RouteKey.bucket(home),
                    originLat = home.lat,
                    originLon = home.lon,
                    radiusKm = 5.0,
                    payload = nearHomeAnswer(),
                    fetchedAt = now - DiscoveryCachePolicy.TTL_MS - 1,
                    timestampOsmBase = "2026-09-01T00:00:00Z",
                    mirror = de,
                ),
            )
        }

        val result = runBlocking { discovery(transport, cache).discover(home, 5.0) }

        assertFalse(result.fromCache)
        assertEquals(1, transport.calls.size)
    }

    @Test
    fun `a cached answer that was searched at a smaller radius is not reused for a wider one`() {
        val transport = StubTransport { nearHomeAnswer() }
        val cache = InMemoryDiscoveryCache()
        val discovery = discovery(transport, cache)
        runBlocking { discovery.discover(home, 5.0) }
        assertEquals(1, transport.calls.size)

        val wider = runBlocking { discovery.discover(home, 20.0) }
        assertFalse(wider.fromCache)
        assertEquals(2, transport.calls.size)
    }

    @Test
    fun `the cache policy is the documented thirty days and twenty-four rows`() {
        assertEquals(30L * 24 * 60 * 60 * 1000, DiscoveryCachePolicy.TTL_MS)
        assertEquals(24, DiscoveryCachePolicy.MAX_ROWS)

        val cache = InMemoryDiscoveryCache()
        runBlocking {
            for (i in 0..80) {
                cache.put(
                    DiscoveredTrailEntity(
                        bucket = String.format(Locale.US, "%d.50,-122.00", i),
                        originLat = home.lat,
                        originLon = home.lon,
                        radiusKm = 5.0,
                        payload = "{}",
                        fetchedAt = now + i,
                        timestampOsmBase = "2026-09-16T18:30:00Z",
                        mirror = de,
                    ),
                )
            }
        }
        assertEquals(DiscoveryCachePolicy.MAX_ROWS, runBlocking { cache.count() })
    }

    // ---- merging with the bundled trails ------------------------------------------------------

    @Test
    fun `a discovered relation a bundled trail already covers is reported, not emitted twice`() {
        val transport = StubTransport { nearHomeAnswer() }
        val discovery = discovery(transport)
        val all = runBlocking { discovery.discover(home, 5.0) }
        val bundled = all.trails.single { it.name == "Sumner Link Trail" }

        val merged = runBlocking { discovery.discover(home, 5.0, bundled = listOf(bundled)) }

        assertEquals(6, merged.trails.size)
        assertFalse(merged.trails.any { it.name == "Sumner Link Trail" })
        assertEquals("Sumner Link Trail", merged.coveredByBundled.single().name)
        assertEquals(9605518L, merged.coveredByBundled.single().osmRelationId)
        assertTrue(merged.coveredByBundled.single().reason.contains("already covers this corridor"))
    }

    @Test
    fun `a bundled trail of the same name on a different corridor does not swallow the discovery`() {
        val transport = StubTransport { nearHomeAnswer() }
        val discovery = discovery(transport)
        val all = runBlocking { discovery.discover(home, 5.0) }
        val sumner = all.trails.single { it.name == "Sumner Link Trail" }
        val elsewhere = sumner.fullGeometry.points.first()
        val bundled = sumner.copy(
            fullGeometry = Polyline(listOf(elsewhere.copy(lat = 47.9), elsewhere.copy(lat = 47.91))),
            rideableSpans = emptyList(),
        )

        val merged = runBlocking { discovery.discover(home, 5.0, bundled = listOf(bundled)) }

        assertTrue(merged.coveredByBundled.isEmpty())
        assertEquals(7, merged.trails.size)
        // Names are the app's trail identity, so the second corridor cannot reuse the first's name.
        assertTrue(merged.trails.map { it.name }.contains("Sumner Link Trail (r9605518)"))
    }

    @Test
    fun `the merged network keeps the bundled trails and adds the discovered ones unverified`() {
        val loaded = Assets.repository()
        val transport = StubTransport { nearHomeAnswer() }
        val discovery = discovery(transport)
        // The caller hands the bundled set to discovery, exactly as the app will.
        val result = runBlocking { discovery.discover(home, 5.0, bundled = loaded.network.trails) }

        // Near home the six bundled trails ARE the six discovered relations, so discovery adds
        // nothing but the one longer relation the bundled set does not carry.
        assertEquals(listOf("King County Interurban Trail"), result.trailNames)
        assertEquals(6, result.coveredByBundled.size)

        val merged = DiscoveryMerge.network(loaded.network, result)

        assertEquals(loaded.network.trails.size + result.trails.size, merged.trails.size)
        assertEquals(loaded.network.joins, merged.joins)
        assertEquals(loaded.network.corridors, merged.corridors)
        // No name discovery produced can ever address a verified bundled trail.
        assertTrue(merged.trails.filter { it.name in result.trailNames }.all { !it.verified })
        // The bundled six are untouched, verified flags included.
        loaded.network.trails.forEach { original ->
            assertEquals(original.verified, merged.trail(original.name)!!.verified)
            assertEquals(original.osmRelationId, merged.trail(original.name)!!.osmRelationId)
        }
        assertEquals(
            loaded.accessPoints.size + result.accessPoints.size,
            DiscoveryMerge.accessPoints(loaded.accessPoints, result).size,
        )
        val summary = DiscoveryMerge.summary(result, now)
        assertTrue(summary.contains("unverified"))
        assertTrue(summary.contains("overpass-api.de"))
        assertTrue(summary.contains("2026-09-16T18:33:04Z"))
    }

    // ---- live smoke probe ---------------------------------------------------------------------

    /**
     * LIVE PROBE - not a pass/fail test. Asks the real mirrors the real query for the real home
     * position and writes what came back to build/live-discovery-probe.txt. Skipped when Overpass
     * cannot be reached, like LivePlanProbeTest skips when Valhalla is not up.
     *
     * Usage: JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest \
     *   --tests 'com.biketrailplanner.TrailDiscoveryTest'
     */
    @Test
    fun probeLiveDiscovery() {
        val loaded = Assets.repository()
        val origin = LatLon(loaded.config.home.lat, loaded.config.home.lon)
        val radiusKm = loaded.config.planner.trailRadiusKm
        val discovery = TrailDiscovery(client = OverpassClient(), cache = null)
        val started = System.currentTimeMillis()
        val outcome = runCatching { runBlocking { discovery.discover(origin, radiusKm, bundled = loaded.network.trails) } }
        val elapsed = System.currentTimeMillis() - started
        val result = outcome.getOrNull()
        if (result == null) {
            Assume.assumeTrue(
                "Overpass is not reachable from here (${outcome.exceptionOrNull()?.message}); skipping the live probe",
                false,
            )
            return
        }

        val text = StringBuilder()
        text.appendLine("LIVE TRAIL DISCOVERY PROBE (D23)")
        text.appendLine("origin=${origin.lat},${origin.lon} radius=${radiusKm} km")
        text.appendLine("query:")
        DiscoveryQuery.text(origin, radiusKm).trimEnd().lines().forEach { text.appendLine("  $it") }
        text.appendLine("round trip: $elapsed ms (query + parse + merge)")
        text.appendLine("query: ${result.queryMs} ms on the accepted mirror")
        text.appendLine("mirror: ${result.mirror}")
        text.appendLine("timestamp_osm_base: ${result.timestampOsmBase}")
        text.appendLine("raw answer: ${result.jsonBytes} bytes")
        text.appendLine("route relations in the answer: ${result.relationsInAnswer}")
        text.appendLine("relations kept: ${result.trails.size}")
        result.trails.forEach { t ->
            text.appendLine(
                "  ${t.name} (r${t.osmRelationId}): ${"%.2f".format(Locale.US, t.lengthM / 1000)} km, " +
                    "${t.fullGeometry.points.size} points, loop=${t.loop}, " +
                    "access points=${result.accessPoints.count { it.trailName == t.name }}",
            )
        }
        text.appendLine("access points: ${result.accessPoints.size}")
        text.appendLine("already covered by a bundled trail: ${result.coveredByBundled.size}")
        result.coveredByBundled.forEach { text.appendLine("  ${it.name} (r${it.osmRelationId}): ${it.reason}") }
        text.appendLine("duplicate relations collapsed: ${result.duplicatesCollapsed.size}")
        result.duplicatesCollapsed.forEach { text.appendLine("  $it") }
        result.attempts.forEach { text.appendLine("attempt: ${it.mirror} -> ${it.outcome} @ ${it.timestampOsmBase}") }
        text.appendLine("summary line: ${DiscoveryMerge.summary(result, System.currentTimeMillis())}")
        val file = File(System.getProperty("probe.discoveryOut", "build/live-discovery-probe.txt"))
        file.parentFile?.mkdirs()
        file.writeText(text.toString())
        println(text)
    }

}
