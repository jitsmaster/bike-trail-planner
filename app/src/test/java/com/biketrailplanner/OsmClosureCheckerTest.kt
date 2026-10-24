package com.biketrailplanner

import com.biketrailplanner.data.OsmClosureChecker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC section 7b item 4: a closure is unlocked only when the data proves it is gone. */
class OsmClosureCheckerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val checker = OsmClosureChecker(json = json)

    private fun way(tags: String, version: Int = 7) = json.parseToJsonElement(
        """{"version":"0.6","elements":[{"type":"way","id":419747831,"timestamp":"2026-06-01T12:00:00Z","version":$version,"tags":$tags}]}""",
    ).let { it as kotlinx.serialization.json.JsonObject }

    @Test
    fun `the closed way is recognised as closed`() {
        val tags = checker.parseTags(way("""{"highway":"unclassified","access":"no","bicycle":"no","foot":"no"}"""))
        assertEquals("no", tags["bicycle"])
        assertEquals("unclassified", tags["highway"])
        val state = OsmClosureChecker.WayState(wayId = 419747831L, reachable = true, tags = tags)
        assertFalse(state.usable)
        assertFalse(state.reopened)
        assertTrue(state.summary.contains("still closed"))
    }

    @Test
    fun `a way whose bicycle no tag is gone is reported as a hygiene finding, not an unlock`() {
        // D15: the Sumner Link closure is permanent, so the check reports and never promises a
        // reopening, and nothing is unlocked automatically.
        val tags = checker.parseTags(way("""{"highway":"cycleway","bicycle":"designated"}"""))
        val state = OsmClosureChecker.WayState(wayId = 419747831L, reachable = true, tags = tags, version = 8, timestamp = "2026-10-01T00:00:00Z")
        assertTrue(state.usable)
        assertTrue(state.reopened)
        assertTrue(state.summary.contains("no longer carries bicycle=no"))
        assertTrue(state.summary.contains("recorded as permanent"))
        assertTrue(state.summary.contains("Only a human"))
        assertFalse(state.summary.contains("unlocked"))
    }

    @Test
    fun `access equals no also keeps it closed even without a bicycle tag`() {
        val tags = checker.parseTags(way("""{"highway":"cycleway","access":"no"}"""))
        assertFalse(OsmClosureChecker.WayState(wayId = 1L, reachable = true, tags = tags).usable)
    }

    @Test
    fun `an unreachable API leaves the closure in place`() {
        val state = OsmClosureChecker.WayState(wayId = 419747831L, reachable = false, error = "timeout")
        assertFalse(state.usable)
        assertTrue(state.summary.contains("could not check"))
    }

    @Test
    fun `the element version and timestamp are read from the way element, not the root`() {
        val element = checker.parseElement(way("""{"highway":"cycleway"}""", version = 9))
        assertEquals(9, element!!["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("2026-06-01T12:00:00Z", element["timestamp"]!!.jsonPrimitive.content)
    }

    @Test
    fun `history versions are counted`() {
        val history = json.parseToJsonElement(
            """{"version":"0.6","elements":[{"version":1},{"version":2},{"version":7}]}""",
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(3, checker.parseHistoryVersions(history))
    }

    @Test
    fun `the urls are the documented ones`() {
        assertEquals("https://api.openstreetmap.org/api/0.6/way/419747831.json", OsmClosureChecker.wayJsonUrl(419747831L))
        assertEquals("https://api.openstreetmap.org/api/0.6/way/419747831/history.json", OsmClosureChecker.historyUrl(419747831L))
    }

    @Test
    fun `a reopened way makes the shipped trail continuous again`() {
        val reader = Assets.reader()
        val closed = com.biketrailplanner.data.TrailRepository(reader, json).load()
        val sumnerClosed = closed.network.trail("Sumner Link Trail")!!
        assertEquals(2, sumnerClosed.rideableSpans.size)
        assertTrue(sumnerClosed.hasClosure)

        val reopened = com.biketrailplanner.data.TrailRepository(reader, json).load(setOf(419747831L))
        val sumnerOpen = reopened.network.trail("Sumner Link Trail")!!
        assertEquals(1, sumnerOpen.rideableSpans.size)
        assertFalse(sumnerOpen.hasClosure)
        assertEquals(sumnerOpen.fullGeometry.lengthM, sumnerOpen.rideableSpans.single().lengthM, 0.001)
    }
}
