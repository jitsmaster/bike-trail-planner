package com.biketrailplanner

import com.biketrailplanner.data.RideShortcut
import com.biketrailplanner.domain.Grade
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideRoad
import com.biketrailplanner.domain.RideTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * D68: the rider asked to keep a recorded ride as a **named** icon on the phone's home screen -
 * *"save it as a google map navigation desktop icon, but allow me to give it a proper name"*.
 *
 * The pinning itself is the platform's: a launcher dialog has the final say and no JVM test can stand
 * in for it. What is testable is everything the rider types and reads - the suggested name, the
 * clean-up of what they typed, and the id that decides whether re-saving updates an icon or stacks a
 * second one beside it.
 */
class RideShortcutTest {

    private fun track(startedAtMs: Long, roads: List<RideRoad> = emptyList()) = RideTrack(
        signature = "sig-abc",
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 3_600_000L,
        distanceM = 2_597.0,
        movingMs = 3_400_000L,
        maxSpeedMps = 6.1,
        gaps = 0,
        rejectedFixes = 0,
        points = listOf(LatLon(47.2406, -122.2620), LatLon(47.2358, -122.2670)),
        wayIds = emptySet(),
        roads = roads,
        named = emptyList(),
        gradedM = 0.0,
        ungradedM = 2_597.0,
        rejectedM = 0.0,
        breakdownTotalM = 2_597.0,
        byGrade = mapOf(Grade.UNGRADED to 2_597.0),
        safetyStale = false,
        dataFingerprint = "fp",
        label = "Recorded ride",
        createdAt = startedAtMs + 3_600_000L,
    )

    /** A fixed instant, so the suggested name does not depend on when the test runs. */
    private fun at(year: Int, month: Int, day: Int): Long =
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 9, 0, 0)
        }.timeInMillis

    private fun road(name: String, startM: Double = 0.0) =
        RideRoad(wayId = 1L, name = name, startM = startM, lengthM = 100.0)

    @Test
    fun `the suggested name says when the ride was and where it went`() {
        val ride = track(at(2026, 10, 26), listOf(road("(unnamed)"), road("18th Street East", 76.0)))
        assertEquals("Ride Oct 26, 18th Street East", RideShortcut.defaultName(ride))
    }

    @Test
    fun `an unnamed first road is not offered as a name`() {
        val ride = track(at(2026, 10, 26), listOf(road("(unnamed)")))
        assertEquals("Ride Oct 26", RideShortcut.defaultName(ride))
    }

    @Test
    fun `a ride with no roads at all still gets a name with the date in it`() {
        val ride = track(at(2026, 1, 3))
        assertEquals("Ride Jan 3", RideShortcut.defaultName(ride))
    }

    @Test
    fun `what the rider types is tidied, never empty, and never longer than a launcher shows`() {
        assertEquals("Milton loop", RideShortcut.label("   Milton   loop  "))
        assertEquals("Ride in Google Maps", RideShortcut.label("   "))
        val long = "a".repeat(200)
        assertEquals(RideShortcut.MAX_LABEL, RideShortcut.label(long).length)
        assertTrue("a cut name must not end in a space", !RideShortcut.label("$long  ").endsWith(" "))
    }

    @Test
    fun `one ride means one icon - saving it again replaces the icon instead of adding a second`() {
        assertEquals(RideShortcut.idFor("sig-abc"), RideShortcut.idFor("sig-abc"))
        assertTrue("two different rides get different icons", RideShortcut.idFor("a") != RideShortcut.idFor("b"))
    }
}
