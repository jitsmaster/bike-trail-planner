package com.biketrailplanner

import com.biketrailplanner.data.RideRawArchive
import com.biketrailplanner.domain.LatLon
import com.biketrailplanner.domain.RideFix
import com.biketrailplanner.domain.RideJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D66: the guarantee the rider asked for before a real trip - *"make sure the recording at least logs
 * all places I have been to"*.
 *
 * The app's own ride is filtered (poor fixes dropped, jitter skipped, unmatched stretches ungraded),
 * and the journal that holds the unfiltered fixes is deleted the moment the ride is saved. These tests
 * pin the archive that exists so the raw record does not depend on any of that.
 */
class RideRawArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fix(lat: Double, at: Long, accuracy: Double = 6.0) =
        RideFix(point = LatLon(lat, -122.2619), atEpochMs = at, accuracyM = accuracy, speedMps = 4.0)

    private val fixes = listOf(
        fix(47.2400, 1_792_449_000_000L),
        fix(47.2410, 1_792_449_002_000L),
        // a fix the recorder would have thrown away: 200 m accuracy drags a track across a block
        fix(47.2420, 1_792_449_004_000L, accuracy = 200.0),
    )

    @Test
    fun `every fix is archived, including the ones a ride would have dropped`() {
        val archive = RideRawArchive(tmp.newFolder("ride-raw"))
        val file = archive.archive(fixes, fixes.first().atEpochMs)
        assertNotNull("a recording with fixes must leave a file behind", file)

        val decoded = RideJournal.decodeAll(file!!.readLines())
        assertEquals("the archive is the raw record, so the 200 m fix stays", 3, decoded.size)
        assertEquals(fixes, decoded)
    }

    @Test
    fun `the header is skipped by the journal parser, so the file is also a replayable journal`() {
        val archive = RideRawArchive(tmp.newFolder("ride-raw"))
        val file = archive.archive(fixes, fixes.first().atEpochMs)!!
        val first = file.readLines().first()
        assertTrue("the header must name what the file is", first.startsWith("# ride "))
        assertNull("a header line must never decode as a fix", RideJournal.decode(first))
    }

    @Test
    fun `two rides do not overwrite each other`() {
        val archive = RideRawArchive(tmp.newFolder("ride-raw"))
        val a = archive.archive(fixes, 1_792_449_000_000L)!!
        val b = archive.archive(fixes, 1_792_452_600_000L)!!
        assertTrue("each ride gets its own file", a.absolutePath != b.absolutePath)
        assertEquals(2, archive.files().size)
    }

    @Test
    fun `an empty recording writes nothing at all`() {
        val archive = RideRawArchive(tmp.newFolder("ride-raw"))
        assertNull(archive.archive(emptyList(), 1_792_449_000_000L))
        assertTrue(archive.files().isEmpty())
    }

    @Test
    fun `the archive is bounded, and it drops the oldest ride not the newest`() {
        val archive = RideRawArchive(tmp.newFolder("ride-raw"))
        val start = 1_792_449_000_000L
        repeat(RideRawArchive.MAX_FILES + 3) { i ->
            archive.archive(fixes, start + i * 3_600_000L)
        }
        val newest = start + (RideRawArchive.MAX_FILES + 2) * 3_600_000L
        val kept = archive.files()
        assertEquals(RideRawArchive.MAX_FILES, kept.size)
        assertEquals(
            "the newest ride must survive the prune",
            RideRawArchive.fileName(newest),
            kept.first().name,
        )
        assertTrue("the oldest must be the one deleted", kept.none { it.name == RideRawArchive.fileName(start) })
    }
}
