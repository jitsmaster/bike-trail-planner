package com.biketrailplanner

import com.biketrailplanner.domain.Geo
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.MapShape
import com.biketrailplanner.domain.MapShapeChoice
import com.biketrailplanner.domain.RideTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D59: which shape the shared basemap draws.
 *
 * The one rule that matters: a ride the rider tapped wins over the plan, so tapping a row in the list
 * actually changes the map behind it. Before this the list had no map at all.
 */
class MapShapeTest {

    private val points = listOf(LatLon(47.0, -122.0), LatLon(47.01, -122.0))

    private fun ride(signature: String, points: List<LatLon> = this.points) =
        Fixtures.rideTrack(signature = signature, points = points)

    @Test
    fun `nothing selected means nothing to draw`() {
        assertNull(MapShapeChoice.of(null, null))
    }

    @Test
    fun `a recorded ride is drawn as itself, not as the plan`() {
        val chosen = MapShapeChoice.of(ride("a"), null)
        assertTrue(chosen is MapShape.Recorded)
        assertEquals("a", (chosen as MapShape.Recorded).ride.signature)
    }

    @Test
    fun `a selected ride outranks the plan on screen`() {
        // Tapping a ride in the list must change the map behind the list.
        val chosen = MapShapeChoice.of(ride("ride-1"), Fixtures.plannedRoute())
        assertTrue("the ride must win", chosen is MapShape.Recorded)
        assertEquals("ride-1", (chosen as MapShape.Recorded).ride.signature)
    }

    @Test
    fun `a ride with no drawable geometry is still the selected ride`() {
        // A one-fix ride cannot be drawn, but it is still the ride the rider tapped. Falling back to
        // the plan would silently show a DIFFERENT ride.
        val chosen = MapShapeChoice.of(ride("empty", points = listOf(points.first())), Fixtures.plannedRoute())
        assertTrue(chosen is MapShape.Recorded)
        assertEquals("empty", (chosen as MapShape.Recorded).ride.signature)
    }

    @Test
    fun `with no ride selected the plan is drawn`() {
        val route = Fixtures.plannedRoute()
        val chosen = MapShapeChoice.of(null, route)
        assertTrue(chosen is MapShape.Planned)
        assertEquals(route, (chosen as MapShape.Planned).route)
    }

    // ------------------------------------------------------------------ D65: the live ride

    @Test
    fun `a recording in progress outranks both a selected ride and the plan`() {
        // While the rider is recording, the ride they are ON is the thing to show. Drawing last week's
        // ride instead would be the D59 complaint again.
        val live = listOf(LatLon(47.0, -122.0), LatLon(47.005, -122.0))
        val chosen = MapShapeChoice.of(ride("old"), Fixtures.plannedRoute(), live)
        assertTrue(chosen is MapShape.Live)
        assertEquals(live, (chosen as MapShape.Live).points)
    }

    @Test
    fun `one live fix is not a line, so the ordinary rules still apply`() {
        // A single fix has no geometry to draw; blanking the map for it would be worse than showing
        // the shape the rider was already looking at.
        val chosen = MapShapeChoice.of(ride("old"), null, listOf(LatLon(47.0, -122.0)))
        assertTrue(chosen is MapShape.Recorded)
        assertNull("and no live list at all draws nothing", MapShapeChoice.of(null, null, emptyList()))
    }
}
