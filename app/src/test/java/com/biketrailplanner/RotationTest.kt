package com.biketrailplanner

import com.biketrailplanner.domain.AccessPoint
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RotationCandidate
import com.biketrailplanner.domain.Rotator
import com.biketrailplanner.domain.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D6: rotate with memory, never the same signature twice in a row, show two other options. */
class RotationTest {

    private val a = RotationCandidate("sig-a", "Sumner Link Trail")
    private val b = RotationCandidate("sig-b", "Riverwalk Trail")
    private val c = RotationCandidate("sig-c", "Interurban Trail Milton")

    @Test
    fun `never-ridden candidates come first and history orders the rest`() {
        val rotator = Rotator(mapOf("sig-b" to 1_000L, "sig-c" to 5_000L), lastRiddenSignature = "sig-c")
        val ordered = rotator.order(listOf(a, b, c)).map { it.signature }
        assertEquals(listOf("sig-a", "sig-b", "sig-c"), ordered)
    }

    @Test
    fun `the last ridden signature is never proposed first`() {
        val rotator = Rotator(mapOf("sig-a" to 9_000L, "sig-b" to 1_000L, "sig-c" to 2_000L), lastRiddenSignature = "sig-a")
        val ordered = rotator.order(listOf(a, b, c)).map { it.signature }
        assertEquals("sig-b", ordered.first())
        assertEquals("sig-a", ordered.last())
    }

    @Test
    fun `the last ridden signature is still offered as another option`() {
        val rotator = Rotator(mapOf("sig-a" to 9_000L, "sig-b" to 1_000L), lastRiddenSignature = "sig-a")
        val ordered = rotator.order(listOf(a, b)).map { it.signature }
        assertEquals(2, ordered.size)
        assertTrue(ordered.contains("sig-a"))
    }

    @Test
    fun `a single candidate is returned even if it repeats the last ride`() {
        val rotator = Rotator(mapOf("sig-a" to 9_000L), lastRiddenSignature = "sig-a")
        assertEquals(listOf("sig-a"), rotator.order(listOf(a)).map { it.signature })
    }

    // ---------------------------------------------------------------- D45: quietness leads

    @Test
    fun `the quieter ride is offered even though the arterial was ridden longest ago`() {
        // The rider: "We are riding bike on these road. Smaller roads normally means less traffic and
        // safer." Before D45 the pool arrived quietest-first and the rotator re-sorted it by recency, so
        // this exact case handed over the arterial.
        val arterial = RotationCandidate("sig-arterial", "Sumner Link Trail", preference = 0.42)
        val quiet = RotationCandidate("sig-quiet", "Sumner Link Trail", preference = 0.68)
        val rotator = Rotator(mapOf("sig-arterial" to 1_000L), lastRiddenSignature = "sig-other")
        assertEquals("sig-quiet", rotator.order(listOf(arterial, quiet)).first().signature)
    }

    @Test
    fun `the planner's rating is the key and recency only breaks ties`() {
        val older = RotationCandidate("sig-older", "A", preference = 0.5)
        val newer = RotationCandidate("sig-newer", "B", preference = 0.5)
        val rotator = Rotator(mapOf("sig-older" to 1_000L, "sig-newer" to 9_000L), lastRiddenSignature = null)
        assertEquals("sig-older", rotator.order(listOf(newer, older)).first().signature)

        val best = RotationCandidate("sig-best", "C", preference = 0.9)
        assertEquals(
            "a higher rating beats being ridden longer ago",
            "sig-best",
            rotator.order(listOf(older, newer, best)).first().signature,
        )
    }

    @Test
    fun `never repeating the last ride outranks quietness`() {
        // Variety is the D6 promise and it survives: if the quietest ride is the one just ridden, the
        // next quietest is offered instead - not the arterial, and not the same ride again.
        val quiet = RotationCandidate("sig-quiet", "A", preference = 0.9)
        val second = RotationCandidate("sig-second", "B", preference = 0.7)
        val arterial = RotationCandidate("sig-arterial", "C", preference = 0.2)
        val rotator = Rotator(mapOf("sig-arterial" to 1L), lastRiddenSignature = "sig-quiet")
        val ordered = rotator.order(listOf(quiet, second, arterial)).map { it.signature }
        assertEquals(listOf("sig-second", "sig-arterial", "sig-quiet"), ordered)
    }

    @Test
    fun `two rides rated the same resolve the same way every time`() {
        val x = RotationCandidate("sig-x", "A", preference = 0.4)
        val y = RotationCandidate("sig-y", "B", preference = 0.4)
        val rotator = Rotator(emptyMap(), null)
        assertEquals(rotator.order(listOf(x, y)).map { it.signature }, rotator.order(listOf(y, x)).map { it.signature })
    }

    // ---------------------------------------------------------------- D22 / hardening

    private val ap = AccessPoint(
        trailName = "Test Trail", osmRelationId = 1L, osmNodeId = 42L,
        point = LatLon(47.25, -122.25), onRoad = null, highway = null, sharesTrailNode = true,
        straightLineMFromHome = 1000.0, probeRouteKmFromHome = null,
    )
    private val turnaround = LatLon(47.26, -122.24)

    private fun sig(origin: LatLon, turn: LatLon = turnaround, vertex: Int? = null) =
        Signature.of(listOf("Test Trail"), origin, ap, turn, vertex)

    @Test
    fun `the signature depends on where the ride started`() {
        // The origin is quantised to ~110 m (3 decimal places). Points inside one cell are the same
        // ride; points three cells apart are not. (As with any grid, a jitter that straddles a cell
        // boundary does produce a new signature - the cell is far wider than GPS noise, so that is
        // rare, and the cost of it happening is only that a ride is offered again.)
        val anchor = LatLon(47.240100, -122.262019)
        val jitter = LatLon(47.240280, -122.262019)   // ~20 m north, same 110 m cell
        val farAway = LatLon(47.242800, -122.262019)  // ~300 m north, different cell
        assertEquals("20 m of GPS jitter must not invent a new ride", sig(anchor), sig(jitter))
        assertTrue("300 m apart must be different rides", sig(anchor) != sig(farAway))
    }

    @Test
    fun `the turnaround is rounded coarsely and prefers a stable trail vertex`() {
        val origin = LatLon(47.240371, -122.262019)
        val jitter20m = LatLon(turnaround.lat + 0.00018, turnaround.lon)
        val moved200m = LatLon(turnaround.lat + 0.0018, turnaround.lon)
        assertEquals("20 m of turnaround jitter must not change the ride", sig(origin), sig(origin, jitter20m))
        assertTrue("a 200 m change is a different turnaround", sig(origin) != sig(origin, moved200m))
        // with a traindex the signature is immune to any coordinate drift at all
        assertEquals(sig(origin, turnaround, 17), sig(origin, moved200m, 17))
        assertTrue(sig(origin, turnaround, 17) != sig(origin, turnaround, 18))
    }

    @Test
    fun `four consecutive rides through the rotation never repeat back to back`() {
        val candidates = listOf(a, b)
        val history = HashMap<String, Long>()
        var last: String? = null
        var picked = mutableListOf<String>()
        var now = 1_000L
        repeat(4) {
            val order = Rotator(history, last).order(candidates)
            val choice = order.first().signature
            picked.add(choice)
            history[choice] = now
            last = choice
            now += 1_000
        }
        picked.zipWithNext().forEach { (x, y) ->
            assertTrue("consecutive plans must differ: $picked", x != y)
        }
    }

    @Test
    fun `the least recently ridden is chosen and the most recent is not`() {
        val rotator = Rotator(
            mapOf("sig-a" to 3_000L, "sig-b" to 1_000L, "sig-c" to 2_000L),
            lastRiddenSignature = "sig-a",
        )
        val ordered = rotator.order(listOf(a, b, c)).map { it.signature }
        assertEquals("sig-b", ordered.first())
        assertTrue("sig-a must not come first", ordered.first() != "sig-a")
    }

    @Test
    fun `a single candidate is reported as a repeat of the last ride`() {
        val rotator = Rotator(mapOf("sig-a" to 9_000L), lastRiddenSignature = "sig-a")
        val ordered = rotator.order(listOf(a))
        assertEquals(listOf("sig-a"), ordered.map { it.signature })
        assertTrue(rotator.isRepeatOfLast(ordered.first().signature))
    }

    @Test
    fun `an empty candidate list stays empty`() {
        assertEquals(emptyList<String>(), Rotator().order(emptyList()).map { it.signature })
    }
}
