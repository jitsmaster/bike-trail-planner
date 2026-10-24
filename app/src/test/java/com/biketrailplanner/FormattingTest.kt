package com.biketrailplanner

import com.biketrailplanner.domain.Formatting
import com.biketrailplanner.domain.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `miles formatting is locale independent and terminates`() {
        assertEquals("1.00 mi", Formatting.miles(1609.344))
        assertEquals("0.5 mi", Formatting.miles1(804.672))
        assertEquals("100%", Formatting.percent(1.0))
        assertEquals("18%", Formatting.percent(0.18))
        assertEquals("2.82 km", Formatting.metres(2824.0))
        assertEquals("800 m", Formatting.metres(800.0))
    }

    @Test
    fun `the top level helpers agree with the object helpers`() {
        assertEquals(Formatting.miles(1609.344), com.biketrailplanner.domain.fmt(Geo.metresToMiles(1609.344), 2) + " mi")
    }
}
