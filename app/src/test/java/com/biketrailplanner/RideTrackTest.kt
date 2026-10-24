package com.biketrailplanner

import com.biketrailplanner.data.TraceMode
import com.biketrailplanner.data.TraceResult
import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.InMemoryRideTrackStore
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RawEdge
import com.biketrailplanner.domain.RecordedRide
import com.biketrailplanner.domain.RideTrackCodec
import com.biketrailplanner.domain.RideTrackGrader
import com.biketrailplanner.domain.RideTrackSignature
import com.biketrailplanner.domain.SafetyGrader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D51: the storage + grading half of the ride recorder. The recorder logic itself is exercised by
 * `RideRecorderTest`; this file pins the conversion of a recorded track into a graded, storable ride.
 */
class RideTrackTest {

    private val points = listOf(
        LatLon(47.000, -122.0),
        LatLon(47.001, -122.0),
        LatLon(47.002, -122.0),
    )

    private fun ride(startedAtMs: Long = 1_000L) = RecordedRide(
        points = points,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 60_000L,
        distanceM = Geo.distanceM(points.first(), points.last()),
        movingMs = 60_000L,
        maxSpeedMps = 5.0,
        rejectedFixes = 0,
        gaps = 0,
    )

    private fun edge(speedKph: Int?, roadClass: String = "residential", surface: String = "paved_smooth") = RawEdge(
        wayId = 42L,
        roadClass = roadClass,
        use = "road",
        surface = surface,
        unpaved = surface != "paved_smooth",
        cycleLane = "none",
        bicycleNetwork = 0,
        shoulder = false,
        speedLimitKph = speedKph,
        lengthM = ride().distanceM,
        names = listOf("A Street"),
    )

    private fun trace(vararg edges: RawEdge) =
        TraceResult(edges = edges.toList(), mode = TraceMode.EDGE_WALK, baseUrl = "test")

    @Test
    fun `a partly matched trace counts the unmatched metres as UNGRADED`() {
        // A recorded track that only half matches the road network must not report itself as fully
        // measured. Observed on a real device: 1.66 km ridden, 843 m matched, and the share claimed
        // 73% until the remainder was counted.
        val short = edge(speedKph = 40).copy(lengthM = ride().distanceM / 2.0)
        val track = RideTrackGrader.grade(ride(), trace(short), SafetyGrader(), "fp", nowMs = 1L)
        assertEquals(
            "the breakdown adds up to the distance the rider rode",
            track.distanceM,
            track.breakdownTotalM,
            1.0,
        )
        assertEquals(track.distanceM / 2.0, track.gradedM, 1.0)
        assertEquals(track.distanceM / 2.0, track.ungradedM, 1.0)
        assertEquals(0.5, track.measuredShare, 0.01)
    }

    @Test
    fun `a fully matched trace is not penalised by the remainder rule`() {
        val track = RideTrackGrader.grade(ride(), trace(edge(speedKph = 40)), SafetyGrader(), "fp", nowMs = 1L)
        assertEquals(track.distanceM, track.breakdownTotalM, 1.0)
        assertEquals(1.0, track.measuredShare, 0.01)
    }

    @Test
    fun `a recording with no track at all is still graded, stored and listed`() {
        // D55: "no matter the distance". A recording too short to draw is still a ride the rider took,
        // and it must reach Past rides instead of vanishing on the way there.
        val oneFix = RecordedRide(
            points = listOf(points.first()),
            startedAtMs = 1_000L,
            endedAtMs = 4_000L,
            distanceM = 0.0,
            movingMs = 0L,
            maxSpeedMps = null,
            rejectedFixes = 0,
            gaps = 0,
        )
        val track = RideTrackGrader.grade(oneFix, trace(edge(40)), SafetyGrader(), "fp", nowMs = 1L)
        assertFalse(track.hasTrack)
        assertTrue("there is still something to keep", track.points.isNotEmpty())
        assertEquals("Recorded ride", track.label)

        // It must survive the payload the store writes ...
        assertEquals(1, RideTrackCodec.decode(RideTrackCodec.encode(track)).points.size)

        // ... and the store must hand it straight back for the list.
        runBlocking {
            val store = InMemoryRideTrackStore()
            store.save(track)
            assertEquals(1, store.count())
            assertEquals(track.signature, store.all().single().signature)
        }
    }

    @Test
    fun `a store keeps a short ride and a long one alike`() = runBlocking {
        val store = InMemoryRideTrackStore()
        val long = RideTrackGrader.grade(ride(1_000L), trace(edge(40)), SafetyGrader(), "fp", nowMs = 1L)
        val short = RideTrackGrader.grade(
            ride(2_000L).copy(points = listOf(points.first()), distanceM = 0.0),
            trace(edge(40)),
            SafetyGrader(),
            "fp",
            nowMs = 2L,
        )
        store.save(long)
        store.save(short)
        assertEquals(2, store.count())
        assertTrue("the short one is listed, not filtered out", store.all().any { !it.hasTrack })
    }

    @Test
    fun `a recorded ride is graded by the same ladder as a planned one`() {
        // 40 km/h ~= 25 mph on a residential road is grade C, so the ride is fully graded.
        val track = RideTrackGrader.grade(
            ride = ride(),
            trace = trace(edge(speedKph = 40)),
            grader = SafetyGrader(),
            fingerprint = "fp",
            nowMs = 5_000L,
        )
        assertEquals(Grade.C, track.named.first().grade)
        assertEquals(track.distanceM, track.gradedM, 1.0)
        assertEquals(0.0, track.ungradedM, 0.0)
        assertTrue(track.wayIds.contains(42L))
        assertEquals("A Street", track.label)
        assertEquals(1.0, track.measuredShare, 1e-9)
    }

    @Test
    fun `an unposted recorded road is still UNGRADED, never safe`() {        val track = RideTrackGrader.grade(
            ride = ride(),
            trace = trace(edge(speedKph = null)),
            grader = SafetyGrader(),
            fingerprint = "fp",
            nowMs = 5_000L,
        )
        assertEquals(0.0, track.gradedM, 1e-6)
        assertEquals(track.distanceM, track.ungradedM, 1.0)
        assertEquals(1.0, track.ungradedShare, 1e-6)
    }

    @Test
    fun `a ride that could not be traced is stored ungraded, not dropped`() {
        val track = RideTrackGrader.grade(
            ride = ride(),
            trace = null,
            grader = SafetyGrader(),
            fingerprint = "fp",
            nowMs = 5_000L,
        )
        assertEquals("Recorded ride", track.label)
        assertEquals(track.distanceM, track.ungradedM, 1.0)
        assertEquals(0.0, track.gradedM, 0.0)
    }

    @Test
    fun `the codec round-trips the geometry, the names, the grades and the way ids`() {
        val track = RideTrackGrader.grade(
            ride = ride(),
            trace = trace(edge(speedKph = 40)),
            grader = SafetyGrader(),
            fingerprint = "fp",
            nowMs = 5_000L,
        )
        val bulky = RideTrackCodec.decode(RideTrackCodec.encode(track))
        assertEquals(track.points.size, bulky.points.size)
        assertEquals(track.points.first().lat, bulky.points.first().lat, 1e-6)
        assertEquals(track.wayIds, bulky.wayIds)
        assertEquals(track.byGrade[Grade.C] ?: 0.0, bulky.byGrade[Grade.C] ?: 0.0, 1.0)
        assertEquals(track.named.first().name, bulky.named.first().name)
        assertEquals(track.rejectedM, bulky.rejectedM, 1e-9)
    }

    @Test
    fun `metres for every grade reads UNGRADED and REJECTED from their own fields`() {
        // byGrade carries only the GRADED metres; a caller that asks for every grade must go through
        // metres(). Getting this wrong drew an empty bar for a fully ungraded ride on a real phone.
        val ungraded = RideTrackGrader.grade(ride(), trace(edge(speedKph = null)), SafetyGrader(), "fp", nowMs = 1L)
        assertEquals(ungraded.ungradedM, ungraded.metres(Grade.UNGRADED), 1e-9)
        assertTrue("byGrade deliberately omits UNGRADED", ungraded.byGrade[Grade.UNGRADED] == null)
        assertEquals(0.0, ungraded.metres(Grade.C), 1e-9)

        val refused = RideTrackGrader.grade(
            ride(),
            trace(edge(speedKph = 40, surface = "dirt")),
            SafetyGrader(),
            "fp",
            nowMs = 1L,
        )
        assertEquals(Grade.REJECTED, refused.named.first().grade)
        assertTrue("a refused surface is rejected metres, not graded", refused.rejectedM > 0.0)
        assertEquals(refused.rejectedM, refused.metres(Grade.REJECTED), 1e-9)
        assertEquals(0.0, refused.gradedM, 1e-9)
        assertTrue("byGrade deliberately omits REJECTED", refused.byGrade[Grade.REJECTED] == null)
    }

    @Test
    fun `a recording's identity is its shape plus when it started`() {
        val a = RideTrackSignature.of(points, 1_000L)
        assertEquals(a, RideTrackSignature.of(points, 1_000L))
        assertNotEquals("a different start time is a different ride", a, RideTrackSignature.of(points, 2_000L))
        assertNotEquals("a different shape is a different ride", a, RideTrackSignature.of(points.drop(1), 1_000L))
    }

    @Test
    fun `the store keeps the newest ride first`() = runBlocking {
        val store = InMemoryRideTrackStore()
        val older = RideTrackGrader.grade(ride(1_000L), trace(edge(40)), SafetyGrader(), "fp", nowMs = 1L)
        val newer = RideTrackGrader.grade(ride(9_000L), trace(edge(40)), SafetyGrader(), "fp", nowMs = 2L)
        store.save(older)
        store.save(newer)
        assertEquals(2, store.count())
        assertEquals(newer.signature, store.all().first().signature)
    }
}
