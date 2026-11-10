package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.ThumbnailStore
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Follows faces from one detector run to the next within a shot: two association passes in the manner of
 * ByteTrack, a constant-velocity filter per face, and a look back for the weak detections before a face was confirmed.
 */
internal class FaceTracker(
    private val thumbnails: ThumbnailStore,
    private val embedding: EmbeddingState,
) {
    val closed = ArrayList<ClosedTrack>()

    /** Number of cuts seen so far; tracks carry it so the end of the scan knows which shot bounds them. */
    var shot = 0
        private set

    private var live = arrayOfNulls<LiveTrack>(16)
    private var liveCount = 0
    private var nextSerial = 0
    private var previousRun = -1
    private var previousPtsUs = 0L

    private val matcher = GatedMatcher()
    private val leftovers = LeftoverRing()
    private var gain = FloatArray(0)
    private var passTracks = IntArray(0)
    private var passDetections = IntArray(0)
    private var matchOf = IntArray(0)
    private var detectionUsed = BooleanArray(0)
    private val chain = IntArray(LeftoverRing.RUNS)
    private val chainRun = IntArray(LeftoverRing.RUNS)

    private val thumbScratch = ByteArray(Tuning.THUMBNAIL_SIZE * Tuning.THUMBNAIL_SIZE * 3)
    private val cropPool = BytePool(embedding.cropBytes)
    private val sharpness = FloatArray(Tuning.MAX_SAMPLES_PER_TRACK)
    private var checkBuffer: ByteArray? = null
    private var thumbnailFailures = 0

    val cropBuffers: BytePool get() = cropPool

    fun processRun(run: Int, ptsUs: Long, batch: DetectionBatch, crops: CropSource) {
        expire(run, ptsUs)
        predict(ptsUs)
        val n = batch.count
        if (detectionUsed.size < n) {
            detectionUsed = BooleanArray(n)
            passDetections = IntArray(n)
        }
        detectionUsed.fill(false, 0, n)
        if (passTracks.size < liveCount) {
            passTracks = IntArray(live.size)
            matchOf = IntArray(live.size)
        }

        var tracks = 0
        for (i in 0 until liveCount) {
            live[i]!!.matched = false
            passTracks[tracks++] = i
        }
        var detections = 0
        for (d in 0 until n) if (batch.score[d] >= Tuning.STRONG_SCORE) passDetections[detections++] = d
        associate(tracks, detections, run, ptsUs, batch, crops, strong = true)

        tracks = 0
        for (i in 0 until liveCount) if (!live[i]!!.matched && !live[i]!!.retired) passTracks[tracks++] = i
        detections = 0
        for (d in 0 until n) if (batch.score[d] < Tuning.STRONG_SCORE) passDetections[detections++] = d
        associate(tracks, detections, run, ptsUs, batch, crops, strong = false)
        closeRetired()

        for (d in 0 until n) {
            if (!detectionUsed[d] && batch.score[d] >= Tuning.NEW_TRACK_SCORE) {
                detectionUsed[d] = true
                start(run, ptsUs, batch, d, crops)
            }
        }
        leftovers.begin(run, ptsUs)
        for (d in 0 until n) if (!detectionUsed[d]) leftovers.add(batch, d)
        previousRun = run
        previousPtsUs = ptsUs
    }

    /** Ends the current shot: nothing is carried across a cut. */
    fun cut() {
        closeAll(trim = true)
        leftovers.clear()
        shot++
    }

    /**
     * [trim] copies each track's samples into arrays of exact size. Worth it while the scan goes on;
     * at the end the arrays are consumed at once and the copy would only cost time.
     */
    fun closeAll(trim: Boolean) {
        for (i in 0 until liveCount) {
            close(live[i]!!, trim)
            live[i] = null
        }
        liveCount = 0
    }

    /** Tracks that will be in the result if the scan ended now. */
    fun faceCount(): Int {
        var count = closed.size
        for (i in 0 until liveCount) if (keeps(live[i]!!)) count++
        return count
    }

    /** For a scan that is abandoned: gives back every thumbnail still held. */
    fun releaseAllThumbnails() {
        for (i in 0 until liveCount) {
            val track = live[i]!!
            release(track.thumbHandle)
            release(track.heldThumb)
            track.thumbHandle = -1
            track.heldThumb = -1
        }
        for (i in 0 until closed.size) {
            val track = closed[i]
            release(track.thumbHandle)
            track.thumbHandle = -1
        }
    }

    fun release(handle: Int) {
        if (handle < 0) return
        try {
            thumbnails.release(handle)
        } catch (e: RuntimeException) {
            // A thumbnail that cannot be freed must not end the scan; the store's owner cleans up its folder.
        }
    }

    private fun expire(run: Int, ptsUs: Long) {
        var kept = 0
        for (i in 0 until liveCount) {
            val track = live[i]!!
            if (ptsUs - track.lastPtsUs > Tuning.MAX_GAP_US && run - track.lastRun > Tuning.MIN_BRIDGED_RUNS) {
                close(track, trim = true)
            } else {
                live[kept++] = track
            }
        }
        for (i in kept until liveCount) live[i] = null
        liveCount = kept
    }

    private fun predict(ptsUs: Long) {
        if (liveCount == 0) return
        val seconds = (ptsUs - previousPtsUs) * 1e-6
        val damping = exp(-seconds / MISSED_RATE_DECAY_SECONDS)
        for (i in 0 until liveCount) {
            val track = live[i]!!
            track.predict(seconds, track.lastRun != previousRun, damping)
        }
    }

    private fun associate(
        tracks: Int,
        detections: Int,
        run: Int,
        ptsUs: Long,
        batch: DetectionBatch,
        crops: CropSource,
        strong: Boolean,
    ) {
        if (tracks == 0 || detections == 0) return
        if (gain.size < tracks * detections) gain = FloatArray(tracks * detections)
        val box = batch.box
        for (a in 0 until tracks) {
            val track = live[passTracks[a]]!!
            val at = a * detections
            for (b in 0 until detections) {
                val d = passDetections[b]
                val overlap = overlap(track, box, d * 4)
                val g = if (strong) overlap * batch.score[d] - Tuning.STRONG_MATCH_FLOOR else overlap - Tuning.WEAK_MATCH_FLOOR
                gain[at + b] = if (g > 0f) g else 0f
            }
        }
        matcher.match(gain, tracks, detections, matchOf)
        if (strong && embedding.active) refuseStrangers(tracks, detections, run, ptsUs, batch, crops)
        for (a in 0 until tracks) {
            val b = matchOf[a]
            if (b < 0) continue
            val d = passDetections[b]
            val track = live[passTracks[a]]!!
            detectionUsed[d] = true
            track.matched = true
            track.correct(box[d * 4], box[d * 4 + 1], box[d * 4 + 2], box[d * 4 + 3], if (strong) 1.0 else WEAK_NOISE_SCALE)
            track.append(run, ptsUs, box, d * 4, if (batch.hasLandmarks[d]) batch.landmarks else null, d * 10, batch.score[d])
            if (strong) picture(track, batch, d, ptsUs, crops)
        }
    }

    /**
     * Overlap cannot tell a face that reappears from a different one that turns up in its place, so matches are
     * also compared by embedding: doubtful ones at once, every track now and then. A stranger gets its own track.
     */
    private fun refuseStrangers(tracks: Int, detections: Int, run: Int, ptsUs: Long, batch: DetectionBatch, crops: CropSource) {
        val box = batch.box
        var doubtBudget = Tuning.MAX_DOUBT_CHECKS_PER_RUN
        var routineBudget = Tuning.MAX_ROUTINE_CHECKS_PER_RUN
        while (true) {
            var refused = false
            var overdue = -1
            var overdueSince = Long.MAX_VALUE
            for (a in 0 until tracks) {
                val b = matchOf[a]
                if (b < 0) continue
                val track = live[passTracks[a]]!!
                val d = passDetections[b]
                if (track.checkedRun == run && track.checkedDetection == d) continue
                // Only a detection that can start its own track may be refused, or the face would go uncovered.
                if (track.sampleCount == 0 || batch.score[d] < Tuning.NEW_TRACK_SCORE) continue
                if (!batch.embeddable(d) || batch.quality[d] < Tuning.MIN_CHECK_QUALITY) continue
                val doubtful = overlap(track, box, d * 4) < Tuning.DOUBT_OVERLAP || ptsUs - track.lastPtsUs > Tuning.DOUBT_GAP_US
                if (doubtful || track.strikes > 0) {
                    if (doubtBudget == 0) continue
                    doubtBudget--
                    if (verify(track, a, b, detections, doubtful, run, ptsUs, crops)) refused = true
                    if (!embedding.active) return
                } else if (ptsUs - track.confirmedPtsUs >= Tuning.IDENTITY_CHECK_INTERVAL_US && track.confirmedPtsUs < overdueSince) {
                    overdue = a
                    overdueSince = track.confirmedPtsUs
                }
            }
            // The routine check goes to the track that has waited longest, so a crowd does not starve anyone.
            if (overdue >= 0 && routineBudget > 0) {
                routineBudget--
                verify(live[passTracks[overdue]]!!, overdue, matchOf[overdue], detections, false, run, ptsUs, crops)
                if (!embedding.active) return
            }
            if (!refused) return
            matcher.match(gain, tracks, detections, matchOf)
        }
    }

    /** Compares the pair of track [a] and detection [b] of the current pass. Returns true when it struck the match out. */
    private fun verify(track: LiveTrack, a: Int, b: Int, detections: Int, doubtful: Boolean, run: Int, ptsUs: Long, crops: CropSource): Boolean {
        val d = passDetections[b]
        track.checkedRun = run
        track.checkedDetection = d
        val similarity = similarityTo(track, d, crops)
        // NaN when it cannot be told, which leaves the match in place.
        if (similarity.isNaN()) return false
        if (similarity >= Tuning.REJECT_COSINE) {
            track.strikes = 0
            track.confirmedPtsUs = ptsUs
            release(track.heldThumb)
            track.heldThumb = -1
            return false
        }
        if (doubtful && track.strikes == 0) {
            gain[a * detections + b] = 0f
            return true
        }
        // A routine check can fail on one bad crop, so the first failure only schedules another check.
        // The second means the track has been following someone else and has lost its own face.
        if (++track.strikes < 2) return false
        track.retired = true
        gain.fill(0f, a * detections, (a + 1) * detections)
        return true
    }

    private fun closeRetired() {
        var kept = 0
        for (i in 0 until liveCount) {
            val track = live[i]!!
            if (track.retired) {
                forgetUnconfirmed(track)
                close(track, trim = true)
            } else {
                live[kept++] = track
            }
        }
        for (i in kept until liveCount) live[i] = null
        liveCount = kept
    }

    /** Pictures taken since the last passed check may show the other person, so they do not speak for this track. */
    private fun forgetUnconfirmed(track: LiveTrack) {
        if (track.heldThumb >= 0) {
            release(track.thumbHandle)
            track.thumbHandle = track.heldThumb
            track.thumbQuality = track.heldThumbQuality
            track.heldThumb = -1
        }
        var kept = 0
        for (i in 0 until track.sampleCount) {
            val crop = track.sampleCrops[i]!!
            track.sampleCrops[i] = null
            if (track.sampleTimeUs[i] <= track.confirmedPtsUs) {
                track.sampleCrops[kept] = crop
                track.sampleQuality[kept] = track.sampleQuality[i]
                track.sampleTimeUs[kept] = track.sampleTimeUs[i]
                kept++
            } else {
                cropPool.give(crop)
            }
        }
        track.sampleCount = kept
    }

    private fun similarityTo(track: LiveTrack, d: Int, crops: CropSource): Float {
        val embedder = embedding.embedder ?: return Float.NaN
        val reference = referenceOf(track, embedder) ?: return Float.NaN
        val buffer = checkBuffer ?: ByteArray(embedding.cropBytes).also { checkBuffer = it }
        if (!crops.aligned(d, buffer)) return Float.NaN
        val candidate = unitEmbedding(embedder, buffer) ?: return Float.NaN
        var dot = 0f
        for (k in candidate.indices) dot += candidate[k] * reference[k]
        return dot
    }

    /**
     * The embedding a track is checked against. It starts from the earliest good crop and moves to a clearly
     * better one only if that was taken before the last passed check, so a stranger can never become the reference.
     */
    private fun referenceOf(track: LiveTrack, embedder: FaceEmbedder): FloatArray? {
        val current = track.reference
        var pick = -1
        if (current == null) {
            for (i in 0 until track.sampleCount) {
                if (track.sampleQuality[i] < Tuning.MIN_CHECK_QUALITY) continue
                if (pick < 0 || track.sampleTimeUs[i] < track.sampleTimeUs[pick]) pick = i
            }
        } else {
            var floor = track.referenceQuality * REFERENCE_REFRESH_GAIN
            for (i in 0 until track.sampleCount) {
                if (track.sampleTimeUs[i] <= track.confirmedPtsUs && track.sampleQuality[i] > floor) {
                    pick = i
                    floor = track.sampleQuality[i]
                }
            }
        }
        if (pick < 0) return current
        val fresh = unitEmbedding(embedder, track.sampleCrops[pick]!!) ?: return current
        track.reference = fresh
        track.referenceQuality = track.sampleQuality[pick]
        return fresh
    }

    /** The embedding of [crop] scaled to unit length, or null when the embedder fails or returns nothing usable. */
    private fun unitEmbedding(embedder: FaceEmbedder, crop: ByteArray): FloatArray? {
        val vector = try {
            embedder.embed(crop)
        } catch (e: RuntimeException) {
            // Grouping is optional; a broken embedder leaves the remaining tracks ungrouped instead of failing the scan.
            embedding.fail()
            return null
        }
        if (vector.size != embedding.vectorSize) {
            embedding.fail()
            return null
        }
        var squared = 0.0
        for (k in vector.indices) squared += vector[k].toDouble() * vector[k]
        if (!(squared > 1e-12 && squared < Double.MAX_VALUE)) return null
        val scale = (1.0 / sqrt(squared)).toFloat()
        for (k in vector.indices) vector[k] *= scale
        return vector
    }

    private fun start(run: Int, ptsUs: Long, batch: DetectionBatch, d: Int, crops: CropSource) {
        val track = LiveTrack(nextSerial++, shot)
        val box = batch.box
        val links = lookBack(run, ptsUs, box, d * 4)
        if (links == 0) {
            track.startFilter(box[d * 4], box[d * 4 + 1], box[d * 4 + 2], box[d * 4 + 3])
        } else {
            // The chain was collected newest first; replaying it oldest first also gives the filter a velocity.
            var lastPtsUs = 0L
            for (k in links - 1 downTo 0) {
                val entry = chain[k]
                val at = entry * 4
                val b = leftovers.box
                val linkRun = chainRun[k]
                val linkPtsUs = leftovers.ptsUs(linkRun)
                if (k == links - 1) {
                    track.startFilter(b[at], b[at + 1], b[at + 2], b[at + 3])
                } else {
                    track.predict((linkPtsUs - lastPtsUs) * 1e-6, false, 1.0)
                    track.correct(b[at], b[at + 1], b[at + 2], b[at + 3], WEAK_NOISE_SCALE)
                }
                track.append(linkRun, linkPtsUs, b, at, if (leftovers.hasEyes[entry]) leftovers.eyes else null, at, leftovers.score[entry])
                lastPtsUs = linkPtsUs
            }
            track.predict((ptsUs - lastPtsUs) * 1e-6, false, 1.0)
            track.correct(box[d * 4], box[d * 4 + 1], box[d * 4 + 2], box[d * 4 + 3], 1.0)
        }
        track.append(run, ptsUs, box, d * 4, if (batch.hasLandmarks[d]) batch.landmarks else null, d * 10, batch.score[d])
        track.matched = true
        track.confirmedPtsUs = ptsUs
        if (liveCount == live.size) live = live.copyOf(liveCount * 2)
        live[liveCount++] = track
        picture(track, batch, d, ptsUs, crops)
    }

    /**
     * Walks back from a new track's first detection through the unclaimed detections of earlier runs and
     * claims the ones that sit where the face was. Returns how many, listed newest first in [chain] and [chainRun].
     */
    private fun lookBack(run: Int, ptsUs: Long, box: FloatArray, boxAt: Int): Int {
        var cx = box[boxAt]
        var cy = box[boxAt + 1]
        var w = box[boxAt + 2]
        var h = box[boxAt + 3]
        var anchorRun = run
        var anchorPtsUs = ptsUs
        var links = 0
        var r = run - 1
        while (leftovers.holds(r) && run - r < LeftoverRing.RUNS) {
            val runPtsUs = leftovers.ptsUs(r)
            if (anchorPtsUs - runPtsUs > Tuning.MAX_GAP_US && anchorRun - r > Tuning.MIN_BRIDGED_RUNS) break
            val first = leftovers.firstEntry(r)
            var best = -1
            var bestOverlap = Tuning.WEAK_MATCH_FLOOR
            val b = leftovers.box
            for (entry in first until first + leftovers.entryCount(r)) {
                if (leftovers.isTaken(entry)) continue
                val overlap = widenedOverlap(cx, cy, w, h, b[entry * 4], b[entry * 4 + 1], b[entry * 4 + 2], b[entry * 4 + 3])
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = entry
                }
            }
            if (best >= 0) {
                leftovers.take(best)
                chain[links] = best
                chainRun[links] = r
                links++
                cx = b[best * 4]
                cy = b[best * 4 + 1]
                w = b[best * 4 + 2]
                h = b[best * 4 + 3]
                anchorRun = r
                anchorPtsUs = runPtsUs
            }
            r--
        }
        return links
    }

    /** Takes a thumbnail or an embedding crop from a strong detection when it improves on what the track has. */
    private fun picture(track: LiveTrack, batch: DetectionBatch, d: Int, ptsUs: Long, crops: CropSource) {
        val quality = batch.quality[d]
        if (thumbnailFailures < MAX_THUMBNAIL_FAILURES &&
            (track.thumbHandle < 0 || quality > track.thumbQuality * Tuning.THUMBNAIL_GAIN) &&
            crops.thumbnail(d, thumbScratch)
        ) {
            val handle = try {
                thumbnails.put(Tuning.THUMBNAIL_SIZE, Tuning.THUMBNAIL_SIZE, thumbScratch)
            } catch (e: RuntimeException) {
                -1
            }
            if (handle < 0) {
                // A store that keeps failing (a full disk, say) costs a crop per face per frame, so stop asking.
                thumbnailFailures++
            } else {
                thumbnailFailures = 0
                if (embedding.active && track.heldThumb < 0 && track.thumbHandle >= 0) {
                    track.heldThumb = track.thumbHandle
                    track.heldThumbQuality = track.thumbQuality
                } else {
                    release(track.thumbHandle)
                }
                track.thumbHandle = handle
                track.thumbQuality = quality
            }
        }
        if (embedding.active && batch.embeddable(d)) offerSample(track, d, quality, ptsUs, crops)
    }

    private fun offerSample(track: LiveTrack, d: Int, quality: Float, ptsUs: Long, crops: CropSource) {
        val count = track.sampleCount
        var slot = -1
        // Kept samples are at least the spacing apart, so at most one of them is too close to this one.
        for (i in 0 until count) {
            if (ptsUs - track.sampleTimeUs[i] < Tuning.SAMPLE_SPACING_US) {
                slot = i
                break
            }
        }
        if (slot < 0 && count == Tuning.MAX_SAMPLES_PER_TRACK) {
            slot = 0
            for (i in 1 until count) if (track.sampleQuality[i] < track.sampleQuality[slot]) slot = i
        }
        if (slot >= 0 && quality <= track.sampleQuality[slot] * Tuning.SAMPLE_GAIN) return
        val replacing = slot >= 0
        if (!replacing) slot = count
        val buffer = track.sampleCrops[slot] ?: cropPool.take()
        if (crops.aligned(d, buffer)) {
            track.sampleCrops[slot] = buffer
            track.sampleQuality[slot] = quality
            track.sampleTimeUs[slot] = ptsUs
            if (!replacing) track.sampleCount = count + 1
        } else if (!replacing) {
            cropPool.give(buffer)
        }
    }

    private fun keeps(track: LiveTrack): Boolean = track.size >= 2 || track.maxScore >= Tuning.SINGLE_RUN_SCORE

    private fun close(track: LiveTrack, trim: Boolean) {
        release(track.heldThumb)
        track.heldThumb = -1
        if (!keeps(track)) {
            release(track.thumbHandle)
            track.thumbHandle = -1
            recycleCrops(track)
            return
        }
        val embedder = embedding.embedder
        val used = track.sampleCount
        val centroid = if (embedder != null && embedding.active && used > 0) centroidOf(track, embedder) else null
        recycleCrops(track)
        val n = track.size
        closed.add(
            ClosedTrack(
                serial = track.serial,
                shot = track.shot,
                size = n,
                runs = if (trim) track.runs.copyOf(n) else track.runs,
                boxes = if (trim) track.boxes.copyOf(n * 4) else track.boxes,
                eyes = if (trim) track.eyes.copyOf(n * 4) else track.eyes,
                meanScore = (track.scoreSum / n).toFloat(),
                thumbHandle = track.thumbHandle,
                thumbQuality = track.thumbQuality,
                centroid = centroid,
                centroidWeight = used.toFloat(),
            ),
        )
        track.thumbHandle = -1
    }

    private fun recycleCrops(track: LiveTrack) {
        for (i in 0 until track.sampleCount) {
            track.sampleCrops[i]?.let { cropPool.give(it) }
            track.sampleCrops[i] = null
        }
        track.sampleCount = 0
    }

    /** Embeds the kept crops and returns their unit mean, weighted toward the sharp, frontal ones. */
    private fun centroidOf(track: LiveTrack, embedder: FaceEmbedder): FloatArray? {
        val count = track.sampleCount
        val size = embedding.vectorSize
        var sharpest = 0f
        for (i in 0 until count) {
            sharpness[i] = laplacianVariance(track.sampleCrops[i]!!, embedding.cropSize)
            sharpest = max(sharpest, sharpness[i])
        }
        val sum = FloatArray(size)
        var used = 0
        for (i in 0 until count) {
            val vector = unitEmbedding(embedder, track.sampleCrops[i]!!)
            if (vector == null) {
                if (!embedding.active) return null
                continue
            }
            val relative = if (sharpest > 0f) max(MIN_RELATIVE_SHARPNESS, sharpness[i] / sharpest) else 1f
            val weight = track.sampleQuality[i] * relative
            for (k in 0 until size) sum[k] += vector[k] * weight
            used++
        }
        if (used == 0) return null
        var squared = 0.0
        for (k in 0 until size) squared += sum[k].toDouble() * sum[k]
        if (!(squared > 1e-12)) return null
        val scale = (1.0 / sqrt(squared)).toFloat()
        for (k in 0 until size) sum[k] *= scale
        return sum
    }

    /** Variance of a 4-neighbor Laplacian over the green channel: higher for a sharper crop. */
    private fun laplacianVariance(rgb: ByteArray, side: Int): Float {
        if (side < 3) return 0f
        var sum = 0.0
        var squares = 0.0
        val stride = side * 3
        for (y in 1 until side - 1) {
            var at = y * stride + 3 + 1
            for (x in 1 until side - 1) {
                val value = 4 * (rgb[at].toInt() and 0xFF) -
                    (rgb[at - 3].toInt() and 0xFF) - (rgb[at + 3].toInt() and 0xFF) -
                    (rgb[at - stride].toInt() and 0xFF) - (rgb[at + stride].toInt() and 0xFF)
                sum += value
                squares += value * value
                at += 3
            }
        }
        val n = (side - 2).toDouble() * (side - 2)
        val mean = sum / n
        return (squares / n - mean * mean).toFloat()
    }

    /**
     * How well a detection fits a track: against where the filter expects the face, or against where it was
     * last seen, whichever fits better. A face that stops or turns back leaves the prediction behind.
     */
    private fun overlap(track: LiveTrack, box: FloatArray, at: Int): Float {
        val state = track.state
        val predicted = widenedOverlap(
            state[0].toFloat(), state[1].toFloat(), state[2].toFloat(), state[3].toFloat(),
            box[at], box[at + 1], box[at + 2], box[at + 3],
        )
        val last = (track.size - 1) * 4
        val seen = track.boxes
        val stayed = widenedOverlap(seen[last], seen[last + 1], seen[last + 2], seen[last + 3], box[at], box[at + 1], box[at + 2], box[at + 3])
        return max(predicted, stayed)
    }

    private fun widenedOverlap(
        acx: Float, acy: Float, aw: Float, ah: Float,
        bcx: Float, bcy: Float, bw: Float, bh: Float,
    ): Float {
        val ahw = aw * HALF_WIDENED
        val bhw = bw * HALF_WIDENED
        val ix = min(acx + ahw, bcx + bhw) - max(acx - ahw, bcx - bhw)
        if (ix <= 0f) return 0f
        val ahh = ah * HALF_WIDENED
        val bhh = bh * HALF_WIDENED
        val iy = min(acy + ahh, bcy + bhh) - max(acy - ahh, bcy - bhh)
        if (iy <= 0f) return 0f
        val intersection = ix * iy
        val union = 4f * (ahw * ahh + bhw * bhh) - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private companion object {
        const val HALF_WIDENED = Tuning.MATCH_EXPANSION * 0.5f

        /** Weak detections place the box less exactly, so the filter trusts them less. */
        const val WEAK_NOISE_SCALE = 4.0
        const val MISSED_RATE_DECAY_SECONDS = 0.5
        const val MIN_RELATIVE_SHARPNESS = 0.2f

        /** The reference embedding is taken again once a kept crop is this much better than the one it came from. */
        const val REFERENCE_REFRESH_GAIN = 1.5f
        const val MAX_THUMBNAIL_FAILURES = 3
    }
}
