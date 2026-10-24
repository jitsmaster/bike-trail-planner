package com.biketrailplanner

import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.SpeedOverride
import com.biketrailplanner.domain.TrafficConfidence
import com.biketrailplanner.domain.TrafficCount
import com.biketrailplanner.domain.TrafficTable
import com.biketrailplanner.domain.VolumeBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D14: measured volume leads, with caps for stale counts and published LTS 4. */
class D14GradingTest {

    private fun count(
        name: String,
        aadt: Int?,
        year: Int?,
        band: VolumeBand,
        lts: Int? = null,
        confidence: TrafficConfidence = TrafficConfidence.MEASURED,
    ) = TrafficCount(name, "test", aadt, year, confidence, lts, band, emptyList(), listOf(name))

    private fun grader(vararg counts: TrafficCount, overrides: List<SpeedOverride> = emptyList()) =
        SafetyGrader(LimitsTable(overrides), TrafficTable(counts.toList()))

    @Test
    fun `a stale count caps a signed bicycle network at C and never at B`() {
        // bicycle_network gives grade A, but the only evidence for how quiet it is is a 1996 count
        val g = grader(count("Old Quiet Road", 900, 1996, VolumeBand.LOW, confidence = TrafficConfidence.STALE))
        val seg = g.gradeEdge(Fixtures.edge(bicycleNetwork = 1, roadClass = "tertiary", name = "Old Quiet Road"))
        assertEquals(Grade.C, seg.grade)
        assertTrue(seg.reason.contains("capped at C"))
    }

    @Test
    fun `a published LTS 4 caps a protected lane at C`() {
        val g = grader(count("Busy Lane Street", 5000, 2025, VolumeBand.MEDIUM, lts = 4))
        val seg = g.gradeEdge(Fixtures.edge(cycleLane = "dedicated", speedLimitKph = 40, name = "Busy Lane Street"))
        assertEquals(Grade.C, seg.grade)
        assertTrue(seg.reason.contains("LTS 4"))
    }

    @Test
    fun `fresh low volume may reach B`() {
        val g = grader(
            count("Fresh Quiet Avenue", 400, 2025, VolumeBand.LOW),
            overrides = listOf(SpeedOverride(null, "Fresh Quiet Avenue", 25, source = "survey")),
        )
        val seg = g.gradeEdge(Fixtures.edge(cycleLane = "dedicated", speedLimitKph = null, name = "Fresh Quiet Avenue"))
        assertEquals(Grade.B, seg.grade)
    }

    @Test
    fun `high volume stays D even when the user reports a 25 mph limit`() {
        // the Jovita case: 35 mph override (D16a), 10,358 vpd (1996). Volume leads, so it is still D.
        val g = grader(
            count("Jovita Blvd E", 10358, 1996, VolumeBand.HIGH, lts = 4, confidence = TrafficConfidence.STALE),
        )
        val seg = g.gradeEdge(Fixtures.edge(roadClass = "secondary", speedLimitKph = 40, name = "Jovita Boulevard East"))
        assertEquals(Grade.D, seg.grade)
        assertEquals(VolumeBand.HIGH, seg.volumeBandApplied)
        assertTrue(seg.reason.contains("10358 vpd"))
    }

    @Test
    fun `a quiet medium-volume road with a known 25 mph limit is a shared quiet road`() {
        val g = grader(count("122nd Avenue East", 2626, 1996, VolumeBand.MEDIUM, confidence = TrafficConfidence.STALE))
        val seg = g.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 40, name = "122nd Avenue East"))
        assertEquals(Grade.C, seg.grade)
        assertTrue(seg.reason.contains("shared quiet road"))
    }

    @Test
    fun `a true off-street facility is never moved by volume or LTS`() {
        val g = grader(count("Meridian Avenue East", 20000, 2025, VolumeBand.HIGH, lts = 4))
        val path = g.gradeEdge(Fixtures.edge(use = "cycleway", roadClass = "service_other", name = "Meridian Avenue East"))
        assertEquals(Grade.A, path.grade)
        val separated = g.gradeEdge(Fixtures.edge(cycleLane = "separated", speedLimitKph = 72, name = "Meridian Avenue East"))
        assertEquals(Grade.A, separated.grade)
    }

    @Test
    fun `an unknown volume never becomes low traffic and never improves a grade`() {
        val g = grader(count("Mystery Road", null, null, VolumeBand.UNKNOWN, confidence = TrafficConfidence.UNKNOWN))
        val seg = g.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "Mystery Road"))
        assertEquals(Grade.UNGRADED, seg.grade)
        assertTrue(seg.volumeText!!.contains("not known"))
    }

    @Test
    fun `the shipped tables grade the home roads the way the data supports`() {
        val loaded = Assets.repository()
        val g = SafetyGrader(loaded.limits, loaded.traffic)
        // 122nd Avenue East: 25 mph (user override) + 2,626 vpd (1996, stale) -> C
        val road122 = g.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "122nd Avenue East"))
        assertEquals(Grade.C, road122.grade)
        // 18th Street East: 381 vpd (1996, low) -> C, and it may never be D
        val home = g.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "18th Street East"))
        assertEquals(Grade.C, home.grade)
        // Jovita Boulevard East: 10,358 vpd (1996, high) -> D whatever the posting (35 mph per D16a)
        val jovita = g.gradeEdge(Fixtures.edge(roadClass = "secondary", speedLimitKph = null, name = "Jovita Boulevard East"))
        assertEquals(Grade.D, jovita.grade)
        // SR-161 has no override and is a primary road -> E, never acceptable
        val sr161 = g.gradeEdge(Fixtures.edge(roadClass = "primary", speedLimitKph = null, name = "Meridian Avenue East"))
        assertEquals(Grade.E, sr161.grade)
    }
}
