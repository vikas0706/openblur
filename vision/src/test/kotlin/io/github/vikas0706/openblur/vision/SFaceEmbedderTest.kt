package io.github.vikas0706.openblur.vision

import io.github.vikas0706.openblur.core.Detection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

class SFaceEmbedderTest {
    private fun moved(landmarksPx: FloatArray, scale: Float, shiftX: Float, shiftY: Float): FloatArray {
        val meanX = (0 until 5).map { landmarksPx[2 * it] }.average().toFloat()
        val meanY = (0 until 5).map { landmarksPx[2 * it + 1] }.average().toFloat()
        return FloatArray(10) {
            if (it % 2 == 0) meanX + (landmarksPx[it] - meanX) * scale + shiftX
            else meanY + (landmarksPx[it] - meanY) * scale + shiftY
        }
    }

    @Test
    fun reportsTheSizesOfTheModel() {
        assertEquals(112, embedder.cropSize)
        assertEquals(128, embedder.embeddingSize)
    }

    @Test
    fun alignedCropsPutTheEyesWhereTheModelExpectsThem() {
        val template = ArcFaceAlignment.TEMPLATE
        for ((i, face) in faces.withIndex()) {
            val landmarksPx = inPixels(face.landmarks)
            val corners = cornersFor(landmarksPx)

            // Where the detector's own eye points fall in the crop: off the template only by the fit's residual.
            val ax = corners[2] - corners[0]
            val ay = corners[3] - corners[1]
            val side = hypot(ax, ay)
            for (eye in 0 until 2) {
                val px = landmarksPx[2 * eye] - corners[0]
                val py = landmarksPx[2 * eye + 1] - corners[1]
                val u = 112 * (px * ax + py * ay) / (side * side)
                val v = 112 * (py * ax - px * ay) / (side * side)
                val offset = hypot(u - (template[2 * eye] + 0.5).toFloat(), v - (template[2 * eye + 1] + 0.5).toFloat())
                println("face $i eye $eye lands at (%.1f, %.1f), %.2f px from the template".format(u, v, offset))
                assertTrue("face $i eye $eye is $offset px from the template", offset < 4f)
            }

            // And where the pixels put them: the detector, run on the crop itself, must find the eyes there too.
            val crop = alignedCrop(landmarksPx)
            val cropPicture = Picture(112, 112, IntArray(112 * 112) {
                ((crop[3 * it].toInt() and 0xFF) shl 16) or ((crop[3 * it + 1].toInt() and 0xFF) shl 8) or
                    (crop[3 * it + 2].toInt() and 0xFF)
            })
            val seen = detectors.detectOnce(cropPicture).first()
            assertTrue("face $i is not recognisable as a face in its crop: ${seen.score}", seen.score > 0.6f)
            for (eye in 0 until 2) {
                val offset = hypot(
                    seen.landmarks[2 * eye] * 112 - (template[2 * eye] + 0.5).toFloat(),
                    seen.landmarks[2 * eye + 1] * 112 - (template[2 * eye + 1] + 0.5).toFloat(),
                )
                println("face $i eye $eye redetected in the crop %.2f px from the template".format(offset))
                assertTrue("face $i eye $eye is $offset px off in the crop", offset < 5f)
            }
        }
    }

    @Test
    fun embeddingsHaveUnitLength() {
        for ((i, embedding) in embeddings.withIndex()) {
            assertEquals(128, embedding.size)
            assertEquals("length of embedding $i", 1f, sqrt(dot(embedding, embedding)), 1e-4f)
        }
    }

    @Test
    fun theSameFaceKeepsItsEmbeddingWhenTheLandmarksShiftOrScale() {
        val otherPasses = listOf(640 to 503, 320 to 252).map { (w, h) ->
            ApolloPhoto.matchFaces(detectors.detectOnce(photo.scaledTo(w, h))).map { it!! }
        }
        for ((i, face) in faces.withIndex()) {
            val landmarksPx = inPixels(face.landmarks)
            val variants = mapOf(
                "shifted 4 px right, 3 px up" to moved(landmarksPx, 1f, 4f, -3f),
                "shifted 3 px left, 4 px down" to moved(landmarksPx, 1f, -3f, 4f),
                "6 percent larger" to moved(landmarksPx, 1.06f, 0f, 0f),
                "6 percent smaller, shifted 2 px" to moved(landmarksPx, 0.94f, 2f, 2f),
                "landmarks from the 640 px pass" to inPixels(otherPasses[0][i].landmarks),
                "landmarks from the 320 px pass" to inPixels(otherPasses[1][i].landmarks),
            )
            for ((name, variant) in variants) {
                val cosine = dot(embeddings[i], embedder.embed(alignedCrop(variant)))
                println("face $i, $name: cosine %.3f".format(cosine))
                assertTrue("face $i, $name: cosine $cosine", cosine > 0.7f)
            }
        }
    }

    @Test
    fun differentPeopleAreFarApart() {
        println("cosine similarity between the three astronauts (int8 model):")
        printSimilarities(embeddings)
        for (i in 0 until 3) {
            for (j in i + 1 until 3) {
                val cosine = dot(embeddings[i], embeddings[j])
                assertTrue("faces $i and $j: cosine $cosine", cosine < 0.4f)
            }
        }
    }

    @Test
    fun theQuantizedModelAgreesWithFullPrecision() {
        val reference = TestModels.sfaceReferenceFile
        assumeTrue("the full-precision SFace file is not on this machine", reference != null)
        SFaceEmbedder(reference!!.readBytes()).use { full ->
            val fullEmbeddings = faces.map { full.embed(alignedCrop(inPixels(it.landmarks))) }
            println("cosine similarity between the three astronauts (full-precision model):")
            printSimilarities(fullEmbeddings)
            for (i in 0 until 3) {
                val cosine = dot(embeddings[i], fullEmbeddings[i])
                println("face $i: int8 against full precision, cosine %.4f".format(cosine))
                assertTrue("face $i: int8 against full precision, cosine $cosine", cosine > 0.9f)
            }
        }
    }

    @Test
    fun embedsTheSameFromAnyThread() {
        val crops = faces.map { alignedCrop(inPixels(it.landmarks)) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((0 until 12).map { n -> Callable { n % 3 to embedder.embed(crops[n % 3]) } })
            for (result in results) {
                val (face, embedding) = result.get()
                assertArrayEquals("face $face embedded on another thread", embeddings[face], embedding, 0f)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun readsOnlyTheCropFromALongerArray() {
        val crop = alignedCrop(inPixels(faces[0].landmarks))
        val longer = crop.copyOf(crop.size + 100).also { it.fill(0x55, crop.size, it.size) }

        assertArrayEquals(embeddings[0], embedder.embed(longer), 0f)
        assertThrows(IllegalArgumentException::class.java) { embedder.embed(ByteArray(crop.size - 1)) }
    }

    @Test
    fun closingTwiceIsHarmlessAndAClosedEmbedderRefusesWork() {
        val own = SFaceEmbedder(TestModels.sface)
        val crop = alignedCrop(inPixels(faces[0].landmarks))
        assertArrayEquals(embeddings[0], own.embed(crop), 0f)

        own.close()
        own.close()

        assertThrows(IllegalStateException::class.java) { own.embed(crop) }
        val landmarksPx = inPixels(faces[0].landmarks)
        assertThrows(IllegalStateException::class.java) { own.alignedCropCorners(landmarksPx, FloatArray(6)) }
    }

    @Test
    fun rejectsWhatIsNotTheEmbeddingModel() {
        assertThrows(IllegalArgumentException::class.java) { SFaceEmbedder(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { SFaceEmbedder(TestModels.sface, threads = 0) }
        assertThrows(ModelException::class.java) { SFaceEmbedder(ByteArray(64) { it.toByte() }) }
        assertThrows(ModelException::class.java) { SFaceEmbedder(TestModels.yunet) }
    }

    companion object {
        private val photo: Picture get() = ApolloPhoto.picture
        private val detectors by lazy { OrtFaceDetectorFactory(TestModels.yunet) }
        private val embedder by lazy { SFaceEmbedder(TestModels.sface) }

        /** The three astronauts, left to right, as the detector sees them in a 1280 px wide pass. */
        private val faces: List<Detection> by lazy {
            ApolloPhoto.matchFaces(detectors.detectOnce(photo.scaledTo(1280, 1006))).map { it!! }
        }

        private val embeddings: List<FloatArray> by lazy {
            faces.map { embedder.embed(alignedCrop(inPixels(it.landmarks))) }
        }

        private fun inPixels(landmarks: FloatArray): FloatArray =
            FloatArray(10) { landmarks[it] * if (it % 2 == 0) photo.width else photo.height }

        private fun cornersFor(landmarksPx: FloatArray): FloatArray =
            FloatArray(6).also { embedder.alignedCropCorners(landmarksPx, it) }

        /** The 112x112 crop the embedder asks for, taken from the full-size photo the way the analyzer takes it. */
        private fun alignedCrop(landmarksPx: FloatArray): ByteArray {
            val corners = cornersFor(landmarksPx)
            val normalized = FloatArray(6) { corners[it] / if (it % 2 == 0) photo.width else photo.height }
            return ByteArray(112 * 112 * 3).also { BilinearCropper(photo).crop(normalized, 112, 112, it) }
        }

        private fun printSimilarities(vectors: List<FloatArray>) {
            for (a in vectors) {
                println("  " + vectors.joinToString("  ") { b -> "%6.3f".format(dot(a, b)) })
            }
        }

        @BeforeClass
        @JvmStatic
        fun loadOnnxRuntime() = TestModels.requireOnnxRuntime()
    }
}
