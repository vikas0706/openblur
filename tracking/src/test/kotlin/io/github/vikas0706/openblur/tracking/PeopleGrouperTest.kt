package io.github.vikas0706.openblur.tracking

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PeopleGrouperTest {
    private var nextHandle = 0

    /** A track detected from [startUs] to [endUs] whose face points at [degrees] in a two-dimensional embedding. */
    private fun track(startUs: Long, endUs: Long, degrees: Double?, quality: Float = 0.5f, samples: Float = 5f): BuiltTrack {
        val centroid = degrees?.let {
            val radians = Math.toRadians(it)
            floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
        }
        return BuiltTrack(
            serial = nextHandle,
            timesUs = longArrayOf(startUs - 10, startUs, endUs, endUs + 10),
            boxes = FloatArray(16) { 0.5f },
            eyes = FloatArray(16) { Float.NaN },
            kinds = ByteArray(4),
            thumbHandle = nextHandle++,
            thumbQuality = quality,
            meanScore = 0.9f,
            detectedStartUs = startUs,
            detectedEndUs = endUs,
            centroid = centroid,
            centroidWeight = samples,
        )
    }

    private fun group(vararg tracks: BuiltTrack) = PeopleGrouper.group(tracks.toList(), 2)

    @Test
    fun mergesAtHalfCosineAndNotBelow() {
        // 60 degrees apart is cosine 0.5, which still merges.
        val merged = group(track(0, 100, 0.0), track(200, 300, 60.0))
        assertArrayEquals(intArrayOf(1, 1), merged.personOfTrack)
        val apart = group(track(0, 100, 0.0), track(200, 300, 61.0))
        assertArrayEquals(intArrayOf(1, 2), apart.personOfTrack)
    }

    @Test
    fun tracksDetectedAtTheSameMomentAreNeverMerged() {
        val overlapping = group(track(0, 100, 0.0), track(100, 300, 1.0))
        assertArrayEquals(intArrayOf(1, 2), overlapping.personOfTrack)
        // Pre-roll and post-roll (the outer sample times) may overlap; only detections count.
        val touching = group(track(0, 100, 0.0), track(101, 300, 1.0))
        assertArrayEquals(intArrayOf(1, 1), touching.personOfTrack)
    }

    @Test
    fun aGroupInheritsTheConflictsOfItsMembers() {
        // A and B are on screen together, and so are C and D. All four look alike,
        // so each joins its closest match and the two groups can never become one.
        val a = track(0, 100, 0.0)
        val b = track(50, 150, 20.0)
        val c = track(200, 300, 5.0)
        val d = track(250, 350, 18.0)
        val result = group(a, b, c, d)
        assertArrayEquals(intArrayOf(1, 2, 1, 2), result.personOfTrack)
        assertArrayEquals(intArrayOf(1, 3), result.people[0].trackIds)
        assertArrayEquals(intArrayOf(2, 4), result.people[1].trackIds)
    }

    @Test
    fun mostSimilarPairMergesFirst() {
        // B is within reach of both A and C, which are too far apart for all three to be one person
        // once B has pulled its group's center toward the nearer neighbor.
        val result = group(track(0, 100, 0.0), track(200, 300, 50.0), track(400, 500, 95.0))
        assertEquals(result.personOfTrack[1], result.personOfTrack[2])
        assertNotEquals(result.personOfTrack[0], result.personOfTrack[1])
    }

    @Test
    fun tracksWithoutACentroidStayAlone() {
        val result = group(track(0, 100, 0.0), track(200, 300, null), track(400, 500, 2.0), track(600, 700, null))
        assertArrayEquals(intArrayOf(1, 2, 1, 3), result.personOfTrack)
        assertEquals(3, result.people.size)
    }

    @Test
    fun peopleAreNumberedByFirstAppearanceAndCarryTheirTotals() {
        val result = group(track(1000, 2000, 90.0), track(3000, 4000, 0.0), track(5000, 7000, 91.0))
        assertArrayEquals(intArrayOf(1, 2, 1), result.personOfTrack)
        val first = result.people[0]
        assertEquals(1, first.id)
        assertArrayEquals(intArrayOf(1, 3), first.trackIds)
        assertEquals(990L, first.firstSeenUs)
        assertEquals(1020L + 2020L, first.visibleUs)
        assertEquals(2, result.people[1].id)
        assertEquals(2990L, result.people[1].firstSeenUs)
    }

    @Test
    fun personPictureComesFromTheBestTrackThatAgreesWithThePerson() {
        // Three tracks of one person; the middle one has the best picture.
        val plain = group(track(0, 100, 0.0, quality = 0.4f), track(200, 300, 2.0, quality = 0.9f), track(400, 500, 1.0, quality = 0.5f))
        assertEquals(1, plain.people.size)
        assertEquals(nextHandle - 2, plain.people[0].thumbnailHandle)

        // The sharpest picture belongs to a track that sits at the edge of the group: a closer one wins.
        val first = track(0, 100, 0.0, quality = 0.60f, samples = 50f)
        val second = track(200, 300, 3.0, quality = 0.60f, samples = 50f)
        val odd = track(400, 500, 55.0, quality = 0.70f, samples = 1f)
        val result = group(first, second, odd)
        assertEquals(1, result.people.size)
        assertEquals(second.thumbHandle, result.people[0].thumbnailHandle)
    }

    @Test
    fun ungroupedGivesEveryTrackItsOwnPerson() {
        val result = PeopleGrouper.ungrouped(listOf(track(0, 100, 0.0), track(200, 300, 0.0)))
        assertArrayEquals(intArrayOf(1, 2), result.personOfTrack)
        assertEquals(listOf(0, 1), result.people.map { it.thumbnailHandle - (nextHandle - 2) })
    }

    @Test
    fun onlyTheLongestTracksAreGroupedWhenThereAreTooMany() {
        val tracks = ArrayList<BuiltTrack>()
        val extra = 40
        // The first tracks are the shortest; they are the ones left out.
        for (i in 0 until Tuning.MAX_GROUPED_TRACKS + extra) {
            val start = i * 10_000L
            tracks += track(start, start + 100 + i, 0.0)
        }
        val result = PeopleGrouper.group(tracks, 2)
        assertEquals(extra + 1, result.people.size)
        for (i in 0 until extra) assertEquals(i + 1, result.personOfTrack[i])
        for (i in extra until tracks.size) assertEquals(extra + 1, result.personOfTrack[i])
    }
}
