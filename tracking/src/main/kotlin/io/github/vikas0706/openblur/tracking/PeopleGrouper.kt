package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.Person
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which person each track belongs to (ids from 1, indexed like the track list) and the people themselves. */
internal class Grouping(val personOfTrack: IntArray, val people: List<Person>)

/**
 * Groups tracks into people by merging the two most similar groups until none is similar enough.
 * Two tracks that were detected at the same moment are different people, and so are any groups holding them.
 */
internal object PeopleGrouper {
    /** [tracks] must be in id order: the track at index i has id i + 1 and no later track starts before it. */
    fun group(tracks: List<BuiltTrack>, vectorSize: Int): Grouping {
        val n = tracks.size
        val rootOf = IntArray(n) { it }
        val memberOf = IntArray(n) { -1 }
        var clusters: Clusters? = null
        val members = groupable(tracks, vectorSize)
        if (members.size >= 2) {
            for (a in members.indices) memberOf[members[a]] = a
            clusters = cluster(tracks, members, vectorSize, rootOf)
        }

        val personOf = IntArray(n)
        val personOfRoot = IntArray(n)
        var count = 0
        for (i in 0 until n) {
            val root = rootOf[i]
            if (personOfRoot[root] == 0) personOfRoot[root] = ++count
            personOf[i] = personOfRoot[root]
        }

        val trackCount = IntArray(count + 1)
        for (i in 0 until n) trackCount[personOf[i]]++
        val trackIds = Array(count + 1) { IntArray(trackCount[it]) }
        val filled = IntArray(count + 1)
        val firstSeen = LongArray(count + 1) { Long.MAX_VALUE }
        val visible = LongArray(count + 1)
        val bestHandle = IntArray(count + 1) { -1 }
        val bestRating = FloatArray(count + 1) { -1f }
        for (i in 0 until n) {
            val track = tracks[i]
            val person = personOf[i]
            trackIds[person][filled[person]++] = i + 1
            val startUs = track.timesUs[0]
            val endUs = track.timesUs[track.timesUs.size - 1]
            firstSeen[person] = min(firstSeen[person], startUs)
            visible[person] += endUs - startUs
            if (track.thumbHandle >= 0) {
                val rating = track.thumbQuality * agreement(clusters, memberOf[i], memberOf[rootOf[i]], vectorSize)
                if (rating > bestRating[person]) {
                    bestRating[person] = rating
                    bestHandle[person] = track.thumbHandle
                }
            }
        }
        val people = ArrayList<Person>(count)
        for (person in 1..count) {
            people.add(Person(person, trackIds[person], bestHandle[person], firstSeen[person], visible[person]))
        }
        return Grouping(personOf, people)
    }

    /** Every track becomes its own person. */
    fun ungrouped(tracks: List<BuiltTrack>): Grouping = group(tracks, 0)

    private fun groupable(tracks: List<BuiltTrack>, vectorSize: Int): IntArray {
        if (vectorSize <= 0) return IntArray(0)
        var count = 0
        for (track in tracks) if (track.centroid?.size == vectorSize) count++
        var members = IntArray(count)
        var at = 0
        for (i in tracks.indices) if (tracks[i].centroid?.size == vectorSize) members[at++] = i
        if (count > Tuning.MAX_GROUPED_TRACKS) {
            members = members
                .sortedByDescending { tracks[it].detectedEndUs - tracks[it].detectedStartUs }
                .take(Tuning.MAX_GROUPED_TRACKS)
                .sorted()
                .toIntArray()
        }
        return members
    }

    /**
     * Half weight for the picture itself and half for how well its track matches the person, so an odd
     * track that was merged in does not supply the person's picture.
     */
    private fun agreement(clusters: Clusters?, member: Int, rootMember: Int, size: Int): Float {
        if (clusters == null || member < 0 || rootMember < 0) return 1f
        return 0.5f + 0.5f * max(0f, dot(clusters.own, member * size, clusters.merged, rootMember * size, size))
    }

    /** One row per grouped track: its [own] centroid, and at a group's root row the [merged] centroid of the group. */
    private class Clusters(val own: FloatArray, val merged: FloatArray)

    /** Writes into [rootOf] the track index that stands for each member's group. */
    private fun cluster(tracks: List<BuiltTrack>, members: IntArray, size: Int, rootOf: IntArray): Clusters {
        val m = members.size
        val unit = FloatArray(m * size)
        val own = FloatArray(m * size)
        val sum = FloatArray(m * size)
        for (a in 0 until m) {
            val track = tracks[members[a]]
            val centroid = track.centroid!!
            val weight = max(1f, track.centroidWeight)
            for (k in 0 until size) {
                unit[a * size + k] = centroid[k]
                own[a * size + k] = centroid[k]
                sum[a * size + k] = centroid[k] * weight
            }
        }
        val similarity = FloatArray(m * m)
        for (a in 0 until m) {
            val ta = tracks[members[a]]
            similarity[a * m + a] = BLOCKED
            for (b in a + 1 until m) {
                val tb = tracks[members[b]]
                val together = ta.detectedStartUs <= tb.detectedEndUs && tb.detectedStartUs <= ta.detectedEndUs
                val s = if (together) BLOCKED else dot(unit, a * size, unit, b * size, size)
                similarity[a * m + b] = s
                similarity[b * m + a] = s
            }
        }
        val alive = BooleanArray(m) { true }
        val parent = IntArray(m) { it }
        val nearest = IntArray(m)
        val nearestValue = FloatArray(m)
        // A stale entry holds an upper bound instead of the exact best match; it is rescanned only when
        // it comes up as the best pair, so one large group changing does not force a rescan of every row.
        val stale = BooleanArray(m)
        for (a in 0 until m) rescan(a, m, similarity, alive, nearest, nearestValue)

        while (true) {
            var best = -1
            var bestValue = Float.NEGATIVE_INFINITY
            for (a in 0 until m) {
                if (alive[a] && nearestValue[a] > bestValue) {
                    bestValue = nearestValue[a]
                    best = a
                }
            }
            if (best < 0 || bestValue < Tuning.MERGE_COSINE) break
            if (stale[best]) {
                rescan(best, m, similarity, alive, nearest, nearestValue)
                stale[best] = false
                continue
            }
            val a = min(best, nearest[best])
            val b = max(best, nearest[best])
            var squared = 0.0
            for (k in 0 until size) {
                val v = sum[a * size + k] + sum[b * size + k]
                sum[a * size + k] = v
                squared += v.toDouble() * v
            }
            if (squared > 1e-12) {
                val scale = (1.0 / sqrt(squared)).toFloat()
                for (k in 0 until size) unit[a * size + k] = sum[a * size + k] * scale
            }
            alive[b] = false
            parent[b] = a
            for (k in 0 until m) {
                if (!alive[k] || k == a) continue
                val blocked = similarity[a * m + k] == BLOCKED || similarity[b * m + k] == BLOCKED
                val s = if (blocked) BLOCKED else dot(unit, a * size, unit, k * size, size)
                similarity[a * m + k] = s
                similarity[k * m + a] = s
                if (s >= nearestValue[k]) {
                    nearest[k] = a
                    nearestValue[k] = s
                    stale[k] = false
                } else if (nearest[k] == a || nearest[k] == b) {
                    stale[k] = true
                }
            }
            rescan(a, m, similarity, alive, nearest, nearestValue)
            stale[a] = false
        }

        for (a in 0 until m) {
            var root = a
            while (parent[root] != root) root = parent[root]
            rootOf[members[a]] = members[root]
        }
        return Clusters(own, unit)
    }

    private fun rescan(a: Int, m: Int, similarity: FloatArray, alive: BooleanArray, nearest: IntArray, nearestValue: FloatArray) {
        var best = -1
        var bestValue = Float.NEGATIVE_INFINITY
        val row = a * m
        for (k in 0 until m) {
            if (k == a || !alive[k]) continue
            if (similarity[row + k] > bestValue) {
                bestValue = similarity[row + k]
                best = k
            }
        }
        nearest[a] = best
        nearestValue[a] = bestValue
    }

    private fun dot(x: FloatArray, xAt: Int, y: FloatArray, yAt: Int, size: Int): Float {
        var sum = 0f
        for (k in 0 until size) sum += x[xAt + k] * y[yAt + k]
        return sum
    }

    private const val BLOCKED = -2f
}
