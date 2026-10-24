package com.biketrailplanner

import com.biketrailplanner.domain.PanelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D53 (docs/UI.md section 3.4): resizing the bottom panel so the rider can see more map. The snapping
 * rule is arithmetic, so it is pinned here rather than eyeballed on a phone.
 */
class PanelSizeTest {

    @Test
    fun `the panel can never be dragged away entirely`() {
        assertEquals(PanelSize.COLLAPSED, PanelSize.clamp(0f), 1e-6f)
        assertEquals(PanelSize.COLLAPSED, PanelSize.clamp(-3f), 1e-6f)
        assertEquals(PanelSize.FULL, PanelSize.clamp(4f), 1e-6f)
        assertEquals(PanelSize.HALF, PanelSize.clamp(PanelSize.HALF), 1e-6f)
    }

    @Test
    fun `a released drag settles on the nearest preset`() {
        assertEquals(PanelSize.COLLAPSED, PanelSize.nearest(0.31f), 1e-6f)
        assertEquals(PanelSize.COLLAPSED, PanelSize.nearest(0.35f), 1e-6f)
        assertEquals(PanelSize.HALF, PanelSize.nearest(0.50f), 1e-6f)
        assertEquals(PanelSize.HALF, PanelSize.nearest(0.70f), 1e-6f)
        assertEquals(PanelSize.FULL, PanelSize.nearest(0.95f), 1e-6f)
    }

    @Test
    fun `a preset is its own nearest preset`() {
        PanelSize.DETENTS.forEach { detent ->
            assertEquals(detent, PanelSize.nearest(detent), 1e-6f)
        }
    }

    @Test
    fun `a tap steps through every preset and comes back around`() {
        var at = PanelSize.COLLAPSED
        val seen = mutableListOf(at)
        repeat(PanelSize.DETENTS.size) {
            at = PanelSize.next(at)
            seen += at
        }
        assertEquals(PanelSize.DETENTS.size, seen.distinct().size)
        assertEquals("a full cycle returns to the start", PanelSize.COLLAPSED, seen.last(), 1e-6f)
    }

    @Test
    fun `a tap from anywhere steps from the preset it actually sits on`() {
        // A rider who drags to an in-between value and lets go lands on the nearest preset, so a tap
        // must advance from that preset rather than from the raw dragged number.
        assertEquals(PanelSize.HALF, PanelSize.next(0.36f), 1e-6f)
        assertEquals(PanelSize.COLLAPSED, PanelSize.next(0.99f), 1e-6f)
    }

    @Test
    fun `a nonsense value falls back to full rather than breaking the layout`() {
        assertEquals(PanelSize.FULL, PanelSize.clamp(Float.NaN), 1e-6f)
        assertEquals(PanelSize.FULL, PanelSize.clamp(Float.POSITIVE_INFINITY), 1e-6f)
        assertTrue(PanelSize.clamp(Float.NEGATIVE_INFINITY) >= PanelSize.COLLAPSED)
    }
}
