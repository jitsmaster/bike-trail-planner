package com.biketrailplanner

import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.SafetyOutcome
import com.biketrailplanner.domain.SpeedOverride
import com.biketrailplanner.domain.SpeedSource
import com.biketrailplanner.domain.d10Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC section 6, including the UNGRADED state and the missing-speed-limit case. */
class SafetyGraderTest {

    private val grader = SafetyGrader(LimitsTable.EMPTY)

    @Test
    fun `grade A for a mapped trail`() {
        val seg = grader.gradeEdge(Fixtures.edge(use = "cycleway", roadClass = "service_other"))
        assertEquals(Grade.A, seg.grade)
        assertTrue(seg.reason.contains("off-street"))
    }

    @Test
    fun `grade A for a separated lane and for a bicycle network`() {
        assertEquals(Grade.A, grader.gradeEdge(Fixtures.edge(cycleLane = "separated", speedLimitKph = 56)).grade)
        assertEquals(Grade.A, grader.gradeEdge(Fixtures.edge(bicycleNetwork = 1, speedLimitKph = 40)).grade)
    }

    @Test
    fun `grade B needs a dedicated lane at 25 mph or less`() {
        val good = grader.gradeEdge(Fixtures.edge(cycleLane = "dedicated", speedLimitKph = 40))
        assertEquals(Grade.B, good.grade)
        assertEquals(SpeedSource.OSM, good.speedSource)
        assertEquals(25, good.speedMph)
    }

    @Test
    fun `grade C for a quiet shared road at 25 mph`() {
        assertEquals(Grade.C, grader.gradeEdge(Fixtures.edge(roadClass = "residential", speedLimitKph = 40)).grade)
        assertEquals(Grade.C, grader.gradeEdge(Fixtures.edge(roadClass = "service_other", speedLimitKph = 40)).grade)
        assertEquals(Grade.C, grader.gradeEdge(Fixtures.edge(roadClass = "unclassified", use = "living_street", speedLimitKph = 32)).grade)
    }

    @Test
    fun `grade D for a tertiary or secondary at 30 to 35 mph without a lane`() {
        val tertiary = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 56, name = "Tacoma Avenue"))
        assertEquals(Grade.D, tertiary.grade)
        assertEquals(35, tertiary.speedMph)
        assertEquals(Grade.D, grader.gradeEdge(Fixtures.edge(roadClass = "secondary", speedLimitKph = 48)).grade)
    }

    @Test
    fun `grade E for a primary road and for 40 mph without a facility`() {
        val primary = grader.gradeEdge(Fixtures.edge(roadClass = "primary", speedLimitKph = 72, cycleLane = "shared"))
        assertEquals(Grade.E, primary.grade)
        val fast = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 72))
        assertEquals(Grade.E, fast.grade)
    }

    @Test
    fun `a fully ungraded route is UNVERIFIED, never PASS and never a refusal`() {
        // 122nd Avenue East in OSM: {"highway":"tertiary"} and nothing else.
        val edge = Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "122nd Avenue East", lengthM = 3038.0)
        val seg = grader.gradeEdge(edge)
        assertEquals(Grade.UNGRADED, seg.grade)
        assertEquals(SpeedSource.NONE, seg.speedSource)
        assertTrue(seg.reason.contains("no maxspeed in OSM"))
        val breakdown = grader.breakdown(listOf(seg))
        assertEquals(0.0, breakdown.gradedM, 0.0)
        assertEquals(3038.0, breakdown.ungradedM, 0.001)
        assertEquals(1.0, breakdown.ungradedShare, 1e-9)
        // THE BUG THIS FIXES: this assertion used to be `assertTrue(verdict.passes)`. An empty graded
        // set produced no violations, so D10 reported a pass on a route where nothing was graded.
        val verdict = grader.evaluateD10(breakdown)
        assertEquals(SafetyOutcome.UNVERIFIED, verdict.outcome)
        // ...and it is still OFFERED. "Not assessed" is not "failed" - measured, 48.53% of the local
        // network is ungraded, so refusing on coverage would refuse nearly every ride from home.
        assertTrue(verdict.hasNoKnownViolation)
        assertTrue(verdict.unverified)
        assertEquals(0.0, verdict.gradedShare, 1e-9)
        assertTrue(breakdown.hasUngraded)
    }

    @Test
    fun `a residential road with no posted limit is UNGRADED too`() {
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "residential", speedLimitKph = null))
        assertEquals(Grade.UNGRADED, seg.grade)
    }

    @Test
    fun `an override in limits json is merged over OSM and changes the grade`() {
        val table = LimitsTable(
            listOf(
                SpeedOverride(osmWayId = 12193741L, name = "122nd Avenue East", speedMph = 25, aadt = 3000, lanes = 2, source = "field survey"),
                SpeedOverride(osmWayId = null, name = "Taylor Street", speedMph = 45, aadt = null, lanes = null, source = "WSDOT"),
            ),
        )
        val g = SafetyGrader(table)
        // 122nd Avenue East is `tertiary`; the SPEC ladder has no rule for a tertiary at 25 mph,
        // so the override lifts it out of UNGRADED but the hole is still closed as D, not C.
        val quiet = g.gradeEdge(Fixtures.edge(wayId = 12193741L, roadClass = "tertiary", speedLimitKph = null, name = "122nd Avenue East"))
        assertEquals(Grade.D, quiet.grade)
        assertEquals(SpeedSource.OVERRIDE, quiet.speedSource)
        assertTrue(quiet.ladderHole)
        // the same override on a residential road grades C
        assertEquals(
            Grade.C,
            g.gradeEdge(Fixtures.edge(wayId = 12193741L, roadClass = "residential", speedLimitKph = null, name = "122nd Avenue East")).grade,
        )
        val fast = g.gradeEdge(Fixtures.edge(wayId = 999L, roadClass = "tertiary", speedLimitKph = null, name = "Taylor Street"))
        assertEquals(Grade.E, fast.grade)
        // OSM still grades the roads the table does not cover.
        val unknown = g.gradeEdge(Fixtures.edge(wayId = 555L, roadClass = "tertiary", speedLimitKph = null, name = "Some Other Road"))
        assertEquals(Grade.UNGRADED, unknown.grade)
    }

    @Test
    fun `the ladder hole is closed as D, flagged, never as C`() {
        // 25 mph on a tertiary road matches no rule in the SPEC ladder.
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 40))
        assertEquals(Grade.D, seg.grade)
        assertTrue(seg.ladderHole)
        assertTrue(seg.reason.contains("ladder hole"))
    }

    @Test
    fun `D11 rejects dirt rough and impassable surfaces`() {
        listOf("dirt", "rough", "impassable").forEach { surface ->
            val seg = grader.gradeEdge(Fixtures.edge(surface = surface))
            assertEquals("surface $surface", Grade.REJECTED, seg.grade)
        }
        // gravel and compacted are allowed by D11
        assertEquals(Grade.C, grader.gradeEdge(Fixtures.edge(surface = "gravel", roadClass = "residential", speedLimitKph = 40)).grade)
    }

    @Test
    fun `D10 allows up to 1_1 miles of grade D and never allows E`() {
        // The rider's rule (2026-09-16): "caution street under 1.1 miles is good enough, allow them".
        // 1.1 mi = 1770 m. Under the cap is fine, over the cap is not, and grade E is never allowed.
        val under = Fixtures.breakdown(Grade.A to 10_000.0, Grade.C to 2_000.0, Grade.D to 1_700.0)
        assertTrue("1.06 mi of caution is under the rider's 1.1 mi limit", grader.evaluateD10(under).hasNoKnownViolation)

        val overMiles = Fixtures.breakdown(Grade.A to 30_000.0, Grade.D to 1_800.0)
        val milesVerdict = grader.evaluateD10(overMiles)
        assertFalse("1.12 mi of caution is over it", milesVerdict.hasNoKnownViolation)
        assertTrue(milesVerdict.violations.any { it.contains("mi (cap") })

        val hasE = Fixtures.breakdown(Grade.A to 10_000.0, Grade.E to 12.0)
        val eVerdict = grader.evaluateD10(hasE)
        assertFalse(eVerdict.hasNoKnownViolation)
        assertTrue(eVerdict.violations.any { it.contains("grade E") })
    }

    @Test
    fun `the caution SHARE no longer refuses a route on its own`() {
        // It used to cap grade D at 10% of the graded route as well. The rider replaced that with an
        // absolute distance, so a high proportion of a SHORT ride is now allowed - and the share is
        // still computed, so the screens can show it. This test pins the deliberate change.
        val highShareButShort = Fixtures.breakdown(Grade.A to 2_000.0, Grade.D to 1_000.0)
        assertTrue("0.62 mi of caution on a 1.9 mi ride is under the distance limit", grader.evaluateD10(highShareButShort).hasNoKnownViolation)
        // ...and the old share limit can still be applied by any caller that wants it back
        assertFalse("with the old 10% share limit the same ride would fail", grader.evaluateD10(highShareButShort, dCapShare = 0.10).hasNoKnownViolation)
    }

    @Test
    fun `the breakdown reports ungraded and rejected metres through metres()`() {
        // a caller asking for UNGRADED must not silently get zero
        val b = Fixtures.breakdown(Grade.A to 1_000.0, Grade.UNGRADED to 2_500.0, Grade.REJECTED to 30.0)
        assertEquals(2_500.0, b.metres(Grade.UNGRADED), 1e-9)
        assertEquals(30.0, b.metres(Grade.REJECTED), 1e-9)
        assertEquals(b.ungradedM, b.metres(Grade.UNGRADED), 1e-9)
        assertEquals(1_000.0, b.metres(Grade.A), 1e-9)
    }

    @Test
    fun `caution and ungraded segments are named`() {
        val segments = listOf(
            Fixtures.segment(Grade.D, 572.0, "Tacoma Avenue", 35),
            Fixtures.segment(Grade.D, 300.0, "Tacoma Avenue", 35),
            Fixtures.segment(Grade.UNGRADED, 3038.0, "122nd Avenue East", null),
        )
        val named = grader.namedSegments(segments)
        val tacoma = named.first { it.name == "Tacoma Avenue" }
        assertEquals(872.0, tacoma.metres, 0.001)
        assertEquals("35 mph (OSM)", tacoma.speedText)
        val ungraded = named.first { it.name == "122nd Avenue East" }
        assertEquals("no speed limit", ungraded.speedText)
        assertEquals(Grade.UNGRADED, ungraded.grade)
    }

    // ---- the coverage gate (added 2026-09-21) --------------------------------------------------

    @Test
    fun `one ungraded metre does not flag a fully graded route`() {
        // The old rule was `ungradedShare > 0.0`, so a single ungraded metre marked a ride unverified
        // while 48.53% of the measured network is ungraded - i.e. it was always true and said nothing.
        val almost = Fixtures.breakdown(Grade.A to 10_000.0, Grade.C to 5_000.0, Grade.UNGRADED to 5.0)
        assertEquals(SafetyOutcome.PASS, grader.evaluateD10(almost).outcome)
        assertFalse(grader.evaluateD10(almost).unverified)
    }

    @Test
    fun `coverage at the threshold passes and just below it is UNVERIFIED`() {
        val atThreshold = Fixtures.breakdown(Grade.A to 1_000.0, Grade.UNGRADED to 1_000.0)
        assertEquals(0.5, atThreshold.gradedShare, 1e-9)
        assertEquals(SafetyOutcome.PASS, grader.evaluateD10(atThreshold).outcome)

        val below = Fixtures.breakdown(Grade.A to 999.0, Grade.UNGRADED to 1_001.0)
        val verdict = grader.evaluateD10(below)
        assertEquals(SafetyOutcome.UNVERIFIED, verdict.outcome)
        // UNVERIFIED carries no violation and is still offered; only FAIL refuses.
        assertTrue(verdict.violations.isEmpty())
        assertTrue(verdict.hasNoKnownViolation)
    }

    @Test
    fun `the coverage threshold comes from the caller, not a hardcoded constant`() {
        // This is the `unverified_ungraded_share` knob in assets/config.json. It was parsed and then
        // never passed to the rule, so the gate could not be tuned at all.
        val half = Fixtures.breakdown(Grade.A to 1_000.0, Grade.UNGRADED to 1_000.0)
        assertEquals(SafetyOutcome.PASS, grader.evaluateD10(half).outcome)
        assertEquals(SafetyOutcome.UNVERIFIED, grader.evaluateD10(half, minGradedShare = 0.8).outcome)
        assertEquals(SafetyOutcome.PASS, grader.evaluateD10(half, minGradedShare = 0.2).outcome)
    }

    @Test
    fun `a route with nothing to judge is UNVERIFIED, never PASS`() {
        // The vacuously-passing case in its purest form.
        val empty = Fixtures.breakdown()
        val verdict = grader.evaluateD10(empty)
        assertEquals(SafetyOutcome.UNVERIFIED, verdict.outcome)
        assertTrue(verdict.gradedShare == 0.0)

        val allUngraded = Fixtures.breakdown(Grade.UNGRADED to 5_000.0)
        assertEquals(SafetyOutcome.UNVERIFIED, grader.evaluateD10(allUngraded).outcome)
    }

    @Test
    fun `a known violation outranks coverage, however little was graded`() {
        // 7 m of grade E on an almost entirely ungraded ride is still a FAIL, not an UNVERIFIED.
        val mostlyUnknown = Fixtures.breakdown(Grade.E to 7.0, Grade.UNGRADED to 40_000.0)
        val verdict = grader.evaluateD10(mostlyUnknown)
        assertEquals(SafetyOutcome.FAIL, verdict.outcome)
        assertFalse(verdict.hasNoKnownViolation)
        assertTrue(verdict.violations.any { it.contains("grade E") })
    }

    @Test
    fun `grade D inside a mostly ungraded ride is still charged to the cap`() {
        // The D cap is measured over graded metres only, but it is measured whenever ANYTHING was
        // graded. The old `gradedM > 1.0` guard skipped the check entirely for a barely-graded ride.
        val barely = Fixtures.breakdown(Grade.D to 2.0 * 1609.344, Grade.UNGRADED to 30_000.0)
        val verdict = grader.evaluateD10(barely)
        assertEquals(SafetyOutcome.FAIL, verdict.outcome)
        assertTrue(verdict.violations.any { it.contains("mi (cap") })
    }

    @Test
    fun `the grader and the shared rule cannot drift`() {
        // Two verdict constructors existed and had already drifted. Both paths must be one function.
        val breakdown = Fixtures.breakdown(Grade.A to 10_000.0, Grade.UNGRADED to 15_000.0)
        assertEquals(d10Verdict(breakdown), grader.evaluateD10(breakdown))
        assertEquals(
            d10Verdict(breakdown, dCapShare = 1.0, dCapMiles = 1.1, minGradedShare = 0.9).outcome,
            grader.evaluateD10(breakdown, 1.0, 1.1, 0.9).outcome,
        )
    }
}
