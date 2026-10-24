package com.biketrailplanner.domain

/**
 * Street-name matching for the traffic and speed tables.
 *
 * The producer's keys are abbreviated ("Jovita Blvd E") while OSM spells the name out
 * ("Jovita Boulevard East"), so a plain lowercase comparison would silently miss the row.
 * This expands the common US street abbreviations before comparing.
 */
object StreetNames {

    private val words = mapOf(
        "blvd" to "boulevard", "ave" to "avenue", "av" to "avenue", "st" to "street", "rd" to "road",
        "dr" to "drive", "hwy" to "highway", "ln" to "lane", "ct" to "court", "pl" to "place",
        "pkwy" to "parkway", "cir" to "circle", "ter" to "terrace", "trl" to "trail", "sq" to "square",
        "e" to "east", "w" to "west", "n" to "north", "s" to "south", "ne" to "northeast",
        "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
    )

    fun normalise(name: String): String = name.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { words[it] ?: it }
        .trim()

    /** True when two street names refer to the same road. */
    fun sameRoad(a: String, b: String): Boolean = normalise(a) == normalise(b)
}
