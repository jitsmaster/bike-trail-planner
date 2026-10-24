package com.biketrailplanner.domain

import java.util.Locale

/**
 * Locale-independent number formatting. The Maps hand-off URL must always use a dot as the
 * decimal separator, so nothing in this app uses the default locale for numbers.
 */
fun fmt(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

fun fmtInt(value: Int): String = String.format(Locale.US, "%d", value)

fun miles2(metres: Double): String = fmt(Geo.metresToMiles(metres), 2)

fun miles1(metres: Double): String = fmt(Geo.metresToMiles(metres), 1)
