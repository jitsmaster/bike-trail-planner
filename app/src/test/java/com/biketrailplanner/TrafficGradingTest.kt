package com.biketrailplanner

import com.biketrailplanner.data.TrafficLoader
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.LimitsTable
import com.biketrailplanner.domain.Polyline
import com.biketrailplanner.domain.SafetyGrader
import com.biketrailplanner.domain.TrafficConfidence
import com.biketrailplanner.domain.TrafficCount
import com.biketrailplanner.domain.TrafficTable
import com.biketrailplanner.domain.VolumeBand
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC section 6b: measured volume first, LTS as an advisory, OSM next, UNGRADED last. */
class TrafficGradingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun count(
        name: String,
        aadt: Int?,
        year: Int?,
        confidence: TrafficConfidence,
        lts: Int? = null,
        band: VolumeBand = TrafficTable.bandFor(aadt),
        wayIds: List<Long> = emptyList(),
        polyline: Polyline? = null,
        note: String? = null,
    ) = TrafficCount(name, "test", aadt, year, confidence, lts, band, wayIds, listOf(name), polyline, note)

    // ------------------------------------------------------------------ the bands and 0

    @Test
    fun `bands follow the published Puyallup thresholds`() {
        assertEquals(VolumeBand.UNKNOWN, TrafficTable.bandFor(null))
        assertEquals(VolumeBand.UNKNOWN, TrafficTable.bandFor(0))
        assertEquals(VolumeBand.LOW, TrafficTable.bandFor(1499))
        assertEquals(VolumeBand.MEDIUM, TrafficTable.bandFor(1500))
        assertEquals(VolumeBand.MEDIUM, TrafficTable.bandFor(7000))
        assertEquals(VolumeBand.HIGH, TrafficTable.bandFor(7001))
    }

    @Test
    fun `a published zero is Not Known, never zero traffic`() {
        val loaded = TrafficLoader.load(
            json,
            """[{"road_name":"Tacoma Avenue","aadt":0,"aadt_year":2008,"aadt_confidence":"unknown","volume_band":"unknown"}]""",
        )
        assertEquals(1, loaded.size)
        val tacoma = loaded.single()
        assertNull(tacoma.aadt)
        assertEquals(VolumeBand.UNKNOWN, tacoma.volumeBand)
        assertEquals(TrafficConfidence.UNKNOWN, tacoma.confidence)
        assertTrue(tacoma.note!!.contains("Not Known"))
        assertTrue(tacoma.text.contains("not known"))
    }

    @Test
    fun `an estimate is parsed but never used as a measured volume`() {
        val table = TrafficTable(
            listOf(count("Fryar Avenue", 846, 1983, TrafficConfidence.ESTIMATE, band = VolumeBand.UNKNOWN)),
        )
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "Fryar Avenue"))
        // still UNGRADED: an 1983 estimate cannot lift a road out of "no speed limit known"
        assertEquals(Grade.UNGRADED, seg.grade)
        assertEquals(VolumeBand.UNKNOWN, seg.volumeBandApplied)
        assertTrue(seg.volumeText!!.contains("estimate"))
    }

    @Test
    fun `a stale count keeps its year and is flagged STALE`() {
        val table = TrafficTable(listOf(count("122nd Avenue East", 2626, 1996, TrafficConfidence.STALE, lts = 4)))
        val match = table.lookup(emptyList(), listOf("122nd Avenue East"))
        assertEquals("2626 vpd (1996) STALE", match!!.count.text)
        assertEquals(30, match.staleYears)
        assertEquals(4, match.lts)
        assertEquals(VolumeBand.MEDIUM, match.band)
    }

    @Test
    fun `a count with no year is treated as measured`() {
        val table = TrafficTable(listOf(count("Some Road", 4000, null, TrafficConfidence.MEASURED)))
        val match = table.lookup(emptyList(), listOf("Some Road"))!!
        assertNull(match.staleYears)
        assertEquals(VolumeBand.MEDIUM, match.band)
    }

    // ------------------------------------------------------------------ low volume cannot be D

    @Test
    fun `low volume lifts an UNGRADED road to C`() {
        val table = TrafficTable(listOf(count("18th Street East", 381, 1996, TrafficConfidence.STALE)))
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "18th Street East"))
        assertEquals(Grade.C, seg.grade)
        assertEquals(VolumeBand.LOW, seg.volumeBandApplied)
        assertTrue(seg.reason.contains("low volume cannot be grade D"))
        assertTrue(seg.reason.contains("381 vpd"))
    }

    @Test
    fun `low volume also lifts a grade D road to C`() {
        val table = TrafficTable(listOf(count("Quiet Street", 900, 2020, TrafficConfidence.MEASURED)))
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 56, name = "Quiet Street"))
        assertEquals(Grade.C, seg.grade)
    }

    @Test
    fun `medium volume moves nothing and unknown never becomes low`() {
        val table = TrafficTable(listOf(count("122nd Avenue East", 2626, 1996, TrafficConfidence.STALE, lts = 4)))
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        val medium = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "122nd Avenue East"))
        assertEquals(Grade.UNGRADED, medium.grade)
        assertEquals(VolumeBand.MEDIUM, medium.volumeBandApplied)
        assertEquals(4, medium.ltsAdvisory)

        val unknown = grader.gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = null, name = "No Count Road"))
        assertEquals(Grade.UNGRADED, unknown.grade)
        assertNull(unknown.volumeText)
        assertEquals(VolumeBand.UNKNOWN, unknown.volumeBandApplied)
    }

    @Test
    fun `an LTS rating is an advisory only and never moves the grade`() {
        val advisory = TrafficTable(listOf(count("122nd Avenue East", 2626, 1996, TrafficConfidence.STALE, lts = 4)))
        val noAdvisory = TrafficTable(listOf(count("122nd Avenue East", 2626, 1996, TrafficConfidence.STALE, lts = null)))
        val a = SafetyGrader(LimitsTable.EMPTY, advisory)
            .gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 40, name = "122nd Avenue East"))
        val b = SafetyGrader(LimitsTable.EMPTY, noAdvisory)
            .gradeEdge(Fixtures.edge(roadClass = "tertiary", speedLimitKph = 40, name = "122nd Avenue East"))
        assertEquals(b.grade, a.grade)
        assertEquals(4, a.ltsAdvisory)
        assertNull(b.ltsAdvisory)
    }

    // ------------------------------------------------------------------ high volume is never quiet

    @Test
    fun `high volume makes a quiet-class road grade D`() {
        val table = TrafficTable(listOf(count("Busy Residential", 12000, 2025, TrafficConfidence.MEASURED)))
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        val seg = grader.gradeEdge(Fixtures.edge(roadClass = "residential", speedLimitKph = 40, name = "Busy Residential"))
        assertEquals(Grade.D, seg.grade)
        assertEquals(VolumeBand.HIGH, seg.volumeBandApplied)
        assertTrue(seg.reason.contains("12000 vpd"))
    }

    @Test
    fun `high volume on a signed bicycle network is not grade A`() {
        val table = TrafficTable(listOf(count("Meridian Avenue East", 20000, 2025, TrafficConfidence.MEASURED, lts = 4)))
        val grader = SafetyGrader(LimitsTable.EMPTY, table)
        // 25 mph keeps the OSM ladder from rejecting it outright, so this isolates the volume rule
        val signed = grader.gradeEdge(
            Fixtures.edge(roadClass = "secondary", bicycleNetwork = 1, speedLimitKph = 40, name = "Meridian Avenue East"),
        )
        assertEquals(Grade.D, signed.grade)
        // at 45 mph the OSM ladder rejects it outright, which is also correct
        assertEquals(
            Grade.E,
            grader.gradeEdge(
                Fixtures.edge(roadClass = "secondary", bicycleNetwork = 1, speedLimitKph = 72, name = "Meridian Avenue East"),
            ).grade,
        )
        // a genuinely separated facility survives, because it is not the road
        val separated = grader.gradeEdge(
            Fixtures.edge(roadClass = "secondary", cycleLane = "separated", speedLimitKph = 72, name = "Meridian Avenue East"),
        )
        assertEquals(Grade.A, separated.grade)
    }

    // ------------------------------------------------------------------ joining

    @Test
    fun `a geometry entry is matched within fifty metres`() {
        val line = Polyline(listOf(LatLon(47.25, -122.25), LatLon(47.26, -122.25)))
        val table = TrafficTable(listOf(count("Unnamed Collector", 5000, 2025, TrafficConfidence.MEASURED, polyline = line)))
        val near = table.lookup(emptyList(), emptyList(), LatLon(47.2555, -122.2499))
        assertEquals("geometry", near!!.matchKind)
        // past the end of the line, ~3 km away: no match at all, so the segment stays ungraded
        // rather than borrowing somebody else's count
        assertNull(table.lookup(emptyList(), emptyList(), LatLon(47.29, -122.25)))
    }

    @Test
    fun `a way id match wins over the name`() {
        val table = TrafficTable(
            listOf(
                count("Road A", 300, 2024, TrafficConfidence.MEASURED, wayIds = listOf(777L)),
                count("Road B", 9000, 2024, TrafficConfidence.MEASURED),
            ),
        )
        val match = table.lookup(listOf(777L), listOf("Road B"))
        assertEquals("way id", match!!.matchKind)
        assertEquals(300, match.count.aadt)
    }

    @Test
    fun `the shipped traffic table loads and marks its staleness honestly`() {
        val loaded = Assets.repository()
        // six usable counts: 18th St E, 122nd Ave E, Edgewood Dr E, 48th St E, West Valley Hwy E,
        // East Valley Hwy E and Meridian Ave E (SR-161)
        assertTrue("usable counts = ${loaded.traffic.size}", loaded.traffic.size >= 6)
        val home = loaded.traffic.lookup(emptyList(), listOf("18th Street East"))!!
        assertEquals(381, home.count.aadt)
        assertEquals(1996, home.count.aadtYear)
        assertEquals(VolumeBand.LOW, home.band)
        assertEquals(TrafficConfidence.STALE, home.count.confidence)
        // the producer keys many rows by OSM way id, so at least one LTS rating must be reachable
        // by way id, and the high band must be applied to the roads that really are busy
        val withLts = Assets.repository().traffic.entries.first { it.lts != null && it.wayIds.isNotEmpty() }
        val byWayId = loaded.traffic.lookup(withLts.wayIds, emptyList())!!
        assertEquals(withLts.lts, byWayId.lts)
        val busy = Assets.repository().traffic.entries.first { it.volumeBand == VolumeBand.HIGH && it.wayIds.isNotEmpty() }
        assertEquals(VolumeBand.HIGH, loaded.traffic.lookup(busy.wayIds, emptyList())!!.band)
        // a road the producer lists as "unknown" (Tacoma Avenue, published 0 = Not Known) has no
        // usable volume, so it stays ungraded rather than becoming "low traffic"
        val tacoma = loaded.traffic.lookup(emptyList(), listOf("Tacoma Avenue"))
        assertTrue(tacoma == null || !tacoma.usableVolume)
    }
}
