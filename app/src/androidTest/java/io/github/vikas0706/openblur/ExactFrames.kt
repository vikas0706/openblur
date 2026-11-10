package io.github.vikas0706.openblur

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Decodes single frames with the platform software decoder, which returns the file's own pixels on every
 * device. Hardware decoders on some phones post-process frames, which would hide or invent differences.
 */
class ExactFrames(private val context: Context, private val uri: Uri) {

    /** Luma plane in coded orientation, row 0 at the top. */
    class Luma(val width: Int, val height: Int, val y: ByteArray, val timeUs: Long, val fullRange: Boolean)

    /** The first frame at or after [timeUs]. */
    fun lumaAt(timeUs: Long): Luma? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val fullRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE) &&
                format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
            val name = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isSoftwareOnly && info.supportedTypes.any { it.equals(mime, true) }
            }?.name ?: return null
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createByCodecName(name).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val index = c.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = c.dequeueOutputBuffer(info, 10_000)
                if (out < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0 && info.presentationTimeUs >= timeUs) {
                    val image = c.getOutputImage(out) ?: return null
                    val plane = image.planes[0]
                    val w = image.cropRect.width()
                    val h = image.cropRect.height()
                    val bytes = ByteArray(w * h)
                    val buffer = plane.buffer
                    for (row in 0 until h) {
                        for (col in 0 until w) {
                            bytes[row * w + col] = buffer.get(
                                (row + image.cropRect.top) * plane.rowStride + (col + image.cropRect.left) * plane.pixelStride,
                            )
                        }
                    }
                    val t = info.presentationTimeUs
                    image.close()
                    c.releaseOutputBuffer(out, false)
                    return Luma(w, h, bytes, t, fullRange)
                }
                c.releaseOutputBuffer(out, false)
                if (eos) return null
            }
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
