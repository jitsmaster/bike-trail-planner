package com.biketrailplanner

import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.PlanResult
import com.biketrailplanner.ui.RiderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/LOCATION-CONTROL.md: the rider must always be told where the ride is planned from, the
 * saved home must not be mistaken for it, and "Use my location" must never be invisible. All of
 * that is decided by pure functions in [RiderText.PlanningOrigin], so it is tested here rather than
 * hoped for on the phone.
 */
class PlanningOriginTest {

    private val savedHome = LatLon(47.240371, -122.262019)
    private val savedHomeLabel = "12424 18th St E, Edgewood, WA 98372"
    private val elsewhere = LatLon(47.1975, -122.2900)

    private fun result(refusal: String? = null) = PlanResult(
        targetMiles = 10.0,
        home = savedHome,
        chosen = null,
        options = emptyList(),
        bestAttempt = null,
        attempts = emptyList(),
        warnings = emptyList(),
        dataGaps = emptyList(),
        baseUrlUsed = null,
        usedFallback = false,
        safeRouteFound = false,
        refusal = refusal,
    )

    // --- what the planning origin is called ------------------------------------------------

    @Test
    fun `at the saved home the origin is named by the saved label`() {
        assertTrue(RiderText.PlanningOrigin.atSavedHome(LatLon(47.2404, -122.2621), savedHome))
        assertEquals(
            savedHomeLabel,
            RiderText.PlanningOrigin.label(5f, atSavedHome = true, savedHomeLabel = savedHomeLabel),
        )
    }

    @Test
    fun `away from the saved home the origin is named as the current position with its accuracy`() {
        assertFalse(RiderText.PlanningOrigin.atSavedHome(elsewhere, savedHome))
        assertEquals(
            "your current position (\u00b15 m)",
            RiderText.PlanningOrigin.label(5f, atSavedHome = false, savedHomeLabel = savedHomeLabel),
        )
    }

    @Test
    fun `the at-home distance is a tolerance, not an exact match`() {
        // ~111 m north of the saved home is still "at home" (GPS jitter), ~1 km is not.
        assertTrue(RiderText.PlanningOrigin.atSavedHome(LatLon(savedHome.lat + 0.001, savedHome.lon), savedHome))
        assertFalse(RiderText.PlanningOrigin.atSavedHome(LatLon(savedHome.lat + 0.01, savedHome.lon), savedHome))
    }

    // --- what a tap on "Use my location" does ----------------------------------------------

    @Test
    fun `a tap with a good fix and no plan only confirms`() {
        assertEquals(
            RiderText.PlanningOrigin.TapAction.CONFIRM_ONLY,
            RiderText.PlanningOrigin.tapAction(originGood = true, hasPlan = false, planning = false),
        )
    }

    @Test
    fun `a tap with a good fix and a plan on screen replans`() {
        assertEquals(
            RiderText.PlanningOrigin.TapAction.REPLAN,
            RiderText.PlanningOrigin.tapAction(originGood = true, hasPlan = true, planning = false),
        )
    }

    @Test
    fun `a tap never starts a second plan while one is running`() {
        assertEquals(
            RiderText.PlanningOrigin.TapAction.ALREADY_PLANNING,
            RiderText.PlanningOrigin.tapAction(originGood = true, hasPlan = true, planning = true),
        )
        assertEquals(
            RiderText.PlanningOrigin.TapAction.ALREADY_PLANNING,
            RiderText.PlanningOrigin.tapAction(originGood = true, hasPlan = false, planning = true),
        )
    }

    @Test
    fun `a tap with a fix that is not usable never replans`() {
        assertEquals(
            RiderText.PlanningOrigin.TapAction.NOT_USABLE,
            RiderText.PlanningOrigin.tapAction(originGood = false, hasPlan = true, planning = false),
        )
    }

    // --- what the rider reads -------------------------------------------------------------

    @Test
    fun `the tap is answered before the GPS has replied`() {
        assertTrue(RiderText.PlanningOrigin.CHECKING, RiderText.PlanningOrigin.CHECKING.startsWith("Checking your position"))
    }

    @Test
    fun `confirming says where the ride will be planned from`() {
        val text = RiderText.PlanningOrigin.confirmation(
            "your current position (\u00b15 m)",
            RiderText.PlanningOrigin.TapAction.CONFIRM_ONLY,
        )!!
        assertTrue(text, text.contains("your current position (\u00b15 m)"))
        assertTrue(text, text.contains("Tap Plan ride to go."))
    }

    @Test
    fun `a replanning tap says that the ride is being planned again`() {
        val text = RiderText.PlanningOrigin.confirmation(
            savedHomeLabel,
            RiderText.PlanningOrigin.TapAction.REPLAN,
        )!!
        assertTrue(text, text.contains("Planning again from $savedHomeLabel"))
    }

    @Test
    fun `a tap while planning says so instead of queueing a second plan`() {
        val text = RiderText.PlanningOrigin.confirmation(
            "your current position (\u00b15 m)",
            RiderText.PlanningOrigin.TapAction.ALREADY_PLANNING,
        )!!
        assertTrue(text, text.contains("already under way"))
    }

    @Test
    fun `a stale fix is explained in rider language`() {
        val text = RiderText.PlanningOrigin.notUsable(ageSeconds = 75, accuracyM = 5f)
        assertTrue(text, text.contains("75 s old"))
        assertTrue(text, text.contains("nothing was planned again"))
    }

    @Test
    fun `a vague fix is explained in rider language`() {
        val text = RiderText.PlanningOrigin.notUsable(ageSeconds = 3, accuracyM = 120f)
        assertTrue(text, text.contains("120 m"))
        assertTrue(text, text.contains("not usable yet"))
    }

    @Test
    fun `no fix at all is explained in rider language`() {
        val text = RiderText.PlanningOrigin.notUsable(ageSeconds = null, accuracyM = null)
        assertTrue(text, text.contains("no position has arrived yet"))
    }

    @Test
    fun `there is nothing to confirm when the fix is not usable`() {
        assertNull(
            RiderText.PlanningOrigin.confirmation(
                "your current position (\u00b15 m)",
                RiderText.PlanningOrigin.TapAction.NOT_USABLE,
            ),
        )
    }

    @Test
    fun `the planning sentence names the place, not just the accuracy`() {
        assertEquals(
            "Planning from $savedHomeLabel.",
            RiderText.planningFromSentence(savedHomeLabel),
        )
        assertNull(RiderText.planningFromSentence(null))
    }

    // --- the saved home, and the data-coverage sentence ------------------------------------

    @Test
    fun `the bundled label loses the word Home before it is shown as the saved home`() {
        assertEquals(
            "12424 18th St E, Edgewood, WA 98372",
            RiderText.PlanningOrigin.savedHomeValue("Home \u2014 12424 18th St E, Edgewood, WA 98372"),
        )
        assertEquals("Somewhere else", RiderText.PlanningOrigin.savedHomeValue("Somewhere else"))
    }

    @Test
    fun `saving a spot next to the bundled home keeps the bundled label`() {
        assertEquals(
            savedHomeLabel,
            RiderText.PlanningOrigin.savedHomeLabel(nearBundledArea = true, bundledLabel = savedHomeLabel, today = "16 Sep 2026"),
        )
    }

    @Test
    fun `saving a spot far from the bundled home is labelled by date, never by a coordinate`() {
        val label = RiderText.PlanningOrigin.savedHomeLabel(
            nearBundledArea = false,
            bundledLabel = savedHomeLabel,
            today = "16 Sep 2026",
        )
        assertEquals("Spot you saved on 16 Sep 2026", label)
        assertFalse(label, label.contains("47."))
    }

    @Test
    fun `the no-trails sentence separates the data area from where the ride starts`() {
        val text = RiderText.noTrailsSentence(
            result = result(refusal = "No trails in the app cover this area"),
            savedHomeLabel = savedHomeLabel,
            planningOriginLabel = "your current position (\u00b15 m)",
            distanceFromSavedHomeM = 19_000.0,
        )
        assertTrue(text, text.contains("cover the area around your saved home, $savedHomeLabel"))
        assertTrue(text, text.contains("This ride starts from your current position (\u00b15 m)"))
        assertTrue(text, text.contains("11.8 mi away from there"))
        assertFalse("the saved home must not be called where the rider is", text.contains("where you are"))
    }

    @Test
    fun `the no-trails sentence still works when the position is unknown`() {
        val text = RiderText.noTrailsSentence(result = result(), savedHomeLabel = savedHomeLabel)
        assertTrue(text, text.contains(savedHomeLabel))
        assertFalse(text, text.contains("starts from"))
    }

    // --- guard: none of this leaks internal vocabulary (docs/UI.md section 1) ---------------

    @Test
    fun `no new rider text leaks internal vocabulary`() {
        val banned = listOf("(D2)", "(D9)", "(D12)", "(D19)", "(D22)", "(D24)", "SPEC", "vpd", "STALE",
            "tertiary", "service_other", "corridor exhausted", "graded ", "ungraded ", "not checked yet")
        val all = listOf(
            RiderText.PlanningOrigin.planningFromSentence(savedHomeLabel),
            RiderText.PlanningOrigin.confirmation(savedHomeLabel, RiderText.PlanningOrigin.TapAction.REPLAN)!!,
            RiderText.PlanningOrigin.confirmation(savedHomeLabel, RiderText.PlanningOrigin.TapAction.CONFIRM_ONLY)!!,
            RiderText.PlanningOrigin.confirmation(savedHomeLabel, RiderText.PlanningOrigin.TapAction.ALREADY_PLANNING)!!,
            RiderText.PlanningOrigin.notUsable(75, 5f),
            RiderText.PlanningOrigin.notUsable(3, 120f),
            RiderText.PlanningOrigin.notUsable(null, null),
            RiderText.PlanningOrigin.SAVED_HOME_EXPLANATION,
            RiderText.PlanningOrigin.savedHomeMessage(savedHomeLabel),
            RiderText.PlanningOrigin.FORGOTTEN_MESSAGE,
            RiderText.PlanningOrigin.CHECKING,
            RiderText.noTrailsSentence(result(), savedHomeLabel, "your current position (\u00b15 m)", 19_000.0),
        )
        all.forEach { text ->
            banned.forEach { bad ->
                assertFalse("internal vocabulary $bad in: $text", text.contains(bad))
            }
        }
    }
}
