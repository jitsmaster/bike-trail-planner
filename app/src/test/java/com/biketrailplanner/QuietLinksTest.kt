package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.QuietLinks
import com.biketrailplanner.domain.QuietWay
import com.biketrailplanner.domain.SpeedSource
import com.biketrailplanner.domain.VolumeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D40: which streets may become a D13 via-point. The rider's complaint was that the planner is
 * "too fixed on the big roads - there are a lot of smaller road under 25 miles it's not using at
 * all"; the code-level cause was that only ways with a measured AADT could become a via-point, and
 * near home the counted roads are the bigger ones.
 */
class QuietLinksTest {

    private val point = LatLon(47.24, -122.26)

    private fun way(
        wayId: Long = 1L,
        label: String = "18th St E",
        band: VolumeBand = VolumeBand.UNKNOWN,
        aadt: Int? = null,
        speedMph: Int? = null,
        roadClass: String? = "residential",
        use: String? = null,
    ) = QuietWay(
        wayId = wayId,
        point = point,
        label = label,
        band = band,
        aadt = aadt,
        aadtYear = if (aadt != null) 2019 else null,
        lts = null,
        speedMph = speedMph,
        speedSource = if (speedMph != null) SpeedSource.OSM else SpeedSource.NONE,
        roadClass = roadClass,
        use = use,
    )

    @Test
    fun `a measured low volume road is still the first thing tried`() {
        val measured = way(band = VolumeBand.LOW, aadt = 900)
        assertTrue(measured.measuredQuiet)
        assertTrue(measured.eligible)
        assertEquals(0, measured.rank)
        assertEquals("a measured way keeps its bare name; the count is appended by ViaRef", "18th St E", measured.viaLabel)
    }

    @Test
    fun `a small street with no count is now a via-point, and says why`() {
        // The whole point of D40: no count exists, so before this rule the street could never be a
        // via-point, and the planner fell back on the counted (bigger) roads near home.
        val small = way(label = "18th St E", roadClass = "residential", speedMph = null)
        assertFalse("without D40 this street is not eligible", small.measuredQuiet)
        assertTrue(small.classQuiet)
        assertTrue(small.eligible)
        assertEquals(1, small.rank)
        assertEquals("18th St E (residential street, no posted limit)", small.viaLabel)
    }

    @Test
    fun `a posted limit under the threshold is described with its number`() {
        val signed = way(roadClass = "residential", speedMph = 20)
        assertTrue(signed.eligible)
        assertEquals("18th St E (residential street at 20 mph, no count)", signed.viaLabel)
    }

    @Test
    fun `a measurement always wins over the road class`() {
        // A residential street carrying 9,000 vehicles a day is not a quiet link, whatever its class.
        val busy = way(band = VolumeBand.HIGH, aadt = 9000, roadClass = "residential")
        assertFalse(busy.classQuiet)
        assertFalse("a HIGH measured volume makes the street ineligible", busy.eligible)
        assertTrue(QuietLinks.select(listOf(busy), 8).isEmpty())
    }

    @Test
    fun `a small street posted over the threshold is refused`() {
        // Residential is not a licence to ignore a 35 mph sign.
        val signed35 = way(roadClass = "residential", speedMph = 35)
        assertFalse(signed35.classQuiet)
        assertFalse(signed35.eligible)
    }

    @Test
    fun `a tertiary road is never admitted by class`() {
        // Tertiary defaults to 39 mph in Valhalla's Washington table, so admitting it by class would
        // be exactly the lie D10 exists to prevent.
        val tertiary = way(roadClass = "tertiary", speedMph = null)
        assertFalse(QuietLinks.isSmallRoad("tertiary", null))
        assertFalse(tertiary.eligible)
        assertTrue(QuietLinks.select(listOf(tertiary), 8).isEmpty())
    }

    @Test
    fun `living street is admitted as a small road`() {
        val living = way(roadClass = null, use = "living_street")
        assertTrue(living.classQuiet)
        assertEquals("living street, no posted limit", QuietLinks.basis(living))
    }

    @Test
    fun `no OSM class word ever reaches the screen`() {
        // tools/verify_ui.py bans "tertiary", "service_other" and the other tag words on screen.
        val service = way(roadClass = "service_other")
        assertEquals("service road, no posted limit", QuietLinks.basis(service))
        assertEquals("service road, no posted limit", service.viaLabel.substringAfter("(").dropLast(1))
        // No class word may reach the screen with an underscore in it: the app's own UI check treats
        // OSM tag vocabulary as a defect.
        for (roadClass in listOf("residential", "service", "service_other", "living_street", "tertiary")) {
            val text = QuietLinks.basis(way(roadClass = roadClass))
            assertFalse("$roadClass must not be printed verbatim", text.contains(roadClass) && roadClass.contains("_"))
            assertFalse(text.contains("_"))
        }
    }

    @Test
    fun `the pool is ordered measured-low, then small streets, then measured-medium`() {
        val medium = way(wayId = 9L, label = "Medium Rd", band = VolumeBand.MEDIUM, aadt = 4000, roadClass = "tertiary")
        val small = way(wayId = 5L, label = "Small St", roadClass = "residential")
        val low = way(wayId = 7L, label = "Low Rd", band = VolumeBand.LOW, aadt = 800)

        val pool = QuietLinks.select(listOf(medium, small, low), limit = 8)
        assertEquals(listOf("Low Rd", "Small St", "Medium Rd"), pool.map { it.label })
    }

    @Test
    fun `the limit is real and the order is stable`() {
        val ways = (1L..5L).map { way(wayId = it, label = "St $it", roadClass = "residential") }
        val first = QuietLinks.select(ways, limit = 3)
        val shuffled = QuietLinks.select(ways.reversed(), limit = 3)
        assertEquals(3, first.size)
        assertEquals("the same three streets are chosen whatever order they arrived in", first.map { it.wayId }, shuffled.map { it.wayId })
        assertEquals(listOf(1L, 2L, 3L), first.map { it.wayId })
        assertTrue(QuietLinks.select(ways, limit = 0).isEmpty())
    }

    @Test
    fun `the best sighting of a street wins, not the first`() {
        // A small street is admitted on its class; a later trace of the same street may carry a real
        // count. The measured sighting must replace the class-only one, or the app would print the
        // weaker reason next to a better one it had already seen.
        val seenFirstByClass = way(wayId = 4L, roadClass = "residential", speedMph = null)
        val thenMeasured = way(wayId = 4L, band = VolumeBand.LOW, aadt = 600, roadClass = "residential")
        assertEquals(
            "a measured sighting replaces the class-only one",
            thenMeasured,
            QuietLinks.best(seenFirstByClass, thenMeasured),
        )
        assertEquals(
            "and a measured sighting is never downgraded by a later class-only one",
            thenMeasured,
            QuietLinks.best(thenMeasured, seenFirstByClass),
        )
        val thenSigned = way(wayId = 4L, roadClass = "residential", speedMph = 20)
        assertEquals("a posted limit outranks the class alone", thenSigned, QuietLinks.best(seenFirstByClass, thenSigned))
        assertEquals(
            "and a posted limit is not replaced by a class-only sighting",
            thenSigned,
            QuietLinks.best(thenSigned, seenFirstByClass),
        )
        assertEquals("nothing seen before means the new sighting is it", thenMeasured, QuietLinks.best(null, thenMeasured))
    }

    @Test
    fun `the summary never claims a measured volume it does not have`() {
        val low = way(wayId = 1L, band = VolumeBand.LOW, aadt = 800)
        val smallA = way(wayId = 2L, label = "A St", roadClass = "residential")
        val smallB = way(wayId = 3L, label = "B St", roadClass = "service")

        assertEquals("2 quiet link(s) with a measured volume", QuietLinks.summary(listOf(low, low)))
        assertEquals("2 small street(s) under 25 mph by road class", QuietLinks.summary(listOf(smallA, smallB)))
        assertEquals(
            "1 quiet link(s) with a measured volume and 1 small street(s) by road class",
            QuietLinks.summary(listOf(low, smallA)),
        )
    }
}
