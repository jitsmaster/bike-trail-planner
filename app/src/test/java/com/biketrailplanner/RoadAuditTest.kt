package com.biketrailplanner

import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.SafetyGrader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which roads near home still come out as caution (D) or rejected (E) once the user's 25 mph
 * postings (D16) and the measured counts (D14) are applied. The road classes are the OSM classes
 * recorded in docs/research/traffic-data-wa.md section 0.
 *
 * This is the answer to "what still stops the app from passing": a road stays caution when a
 * measured count puts it over 7,000 vehicles/day (volume leads, whatever the posted speed), and
 * SR-161 stays rejected because `highway=primary` is a never-E road class on its own.
 */
class RoadAuditTest {

    private val loaded = Assets.repository()
    private val grader = SafetyGrader(loaded.limits, loaded.traffic)

    private fun grade(name: String, roadClass: String, speedLimitKph: Int? = null) =
        grader.gradeEdge(Fixtures.edge(roadClass = roadClass, speedLimitKph = speedLimitKph, name = name)).grade

    @Test
    fun `the two corrected postings read 35 mph and the grade does not move`() {
        // D16a: the user confirmed 35 mph on both; the grade stays D on volume alone.
        assertEquals(35, loaded.limits.row(names = listOf("Jovita Boulevard East"))!!.speedMph)
        assertEquals(35, loaded.limits.row(names = listOf("Stewart Road Southwest"))!!.speedMph)
        assertEquals(Grade.D, grade("Jovita Boulevard East", "secondary"))
        assertEquals(Grade.D, grade("Stewart Road Southwest", "secondary"))
        // and the rider-facing reason says 35, through the segment's own speed text
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "secondary", speedLimitKph = null, name = "Jovita Boulevard East"))
        assertEquals(35, seg.speedMph)
        assertTrue(seg.reason.contains("10358 vpd") || seg.reason.contains("10,358"))
    }

    @Test
    fun `the roads that stay caution or rejected from home`() {
        val expectations = listOf(
            // road, OSM class, expected grade
            Triple("Meridian Avenue East", "primary", Grade.E),      // SR-161: never-E road class
            Triple("Jovita Boulevard East", "secondary", Grade.D),   // 10,358 vpd (1996): volume leads
            Triple("Stewart Road Southwest", "secondary", Grade.D),  // 14,669 vpd (1995): volume leads
        )
        expectations.forEach { (road, roadClass, expected) ->
            val actual = grade(road, roadClass)
            assertEquals("$road should grade $expected", expected, actual)
        }
    }

    @Test
    fun `the local roads with a real count grade C once the 25 mph posting is applied`() {
        listOf(
            "18th Street East" to "tertiary",          // 381 vpd (1996), low: cannot be D
            "122nd Avenue East" to "tertiary",         // 2,626 vpd (1996), medium + stale -> capped at C
            "Edgewood Drive East" to "tertiary",       // 2,875 vpd (1994): low-medium, capped at C
            "48th Street East" to "tertiary",          // 2,469 vpd (1996)
        ).forEach { (road, roadClass) ->
            assertEquals("$road should grade C", Grade.C, grade(road, roadClass))
        }
    }

    @Test
    fun `a 25 mph local road with NO usable count stays caution, not quiet`() {
        // D14: unknown never becomes low traffic. These are the closure-detour streets: the count is
        // published as 0 ("Not Known") or does not exist, so the ladder hole closes as caution.
        listOf(
            "Tacoma Avenue" to "tertiary",             // ADTVolume 0 = Not Known
            "Fryar Avenue" to "tertiary",              // ADTVolume 0 = Not Known
            "142nd Avenue East" to "tertiary",         // no count at all
        ).forEach { (road, roadClass) ->
            assertEquals("$road should grade D", Grade.D, grade(road, roadClass))
        }
    }

    @Test
    fun `the state highways without a posting fall back to class and volume`() {
        // no 25 mph override was applied to these on purpose (D16)
        assertTrue(loaded.limits.row(names = listOf("Meridian Avenue East"))!!.speedMph == null)
        assertTrue(loaded.limits.row(names = listOf("East Valley Highway East"))!!.speedMph == null)
        assertTrue(loaded.limits.row(names = listOf("West Valley Highway East"))!!.speedMph == null)
        // East Valley Hwy: no OSM speed, 11,050 vpd (2010) -> high volume makes it caution
        assertEquals(Grade.D, grade("East Valley Highway East", "secondary"))
        // West Valley Hwy: no OSM speed, 5,750 vpd (2025) -> medium volume promotes nothing
        assertEquals(Grade.UNGRADED, grade("West Valley Highway East", "tertiary"))
    }

    @Test
    fun `every road in the shipped tables was audited`() {
        val rows = loaded.limits.rows.mapNotNull { it.name }
        assertTrue("the override table must list the local roads", rows.size >= 10)
        val audio = rows.count { row ->
            grade(row, "tertiary") != null
        }
        assertEquals(rows.size, audio)
        assertNotNull(loaded.traffic.lookup(emptyList(), listOf("Jovita Boulevard East")))
    }

    @Test
    fun `the corridor routes break D10 because of the volume-led caution roads`() {
        // Jovita is on the Lake Tapps / Sumner Link approach: about 2.1 mi of it, against a 0.5 mi cap
        val jovitaSegments = 2.1 * 1609.344
        val breakdown = Fixtures.breakdown(Grade.A to 4_000.0, Grade.C to 5_000.0, Grade.D to jovitaSegments)
        val verdict = grader.evaluateD10(breakdown)
        assertTrue("2.1 mi of caution must fail the cap", !verdict.hasNoKnownViolation)
        assertTrue(verdict.violations.any { it.contains("grade D") })
    }
}
