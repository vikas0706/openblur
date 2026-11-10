package io.github.vikas0706.openblur.analysis

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.core.TransferKind
import java.io.File

/**
 * Encodes a short H.264 clip on the device. Every frame is four grey quadrants of different brightness, so a
 * test can tell which way up and which way round a frame reached it.
 */
internal object TestVideo {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val FRAMES = 30
    const val FRAME_US = 33_333L

    /** Luma of the encoded picture's top-left, top-right, bottom-left and bottom-right quadrants. */
    val LUMA = intArrayOf(235, 170, 90, 16)

    fun info(rotationDegrees: Int = 0, frames: Int = FRAMES) = SourceVideoInfo(
        mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
        codedWidth = WIDTH,
        codedHeight = HEIGHT,
        rotationDegrees = rotationDegrees,
        frameRate = 30f,
        bitrate = 0,
        durationUs = frames * FRAME_US,
        frameCount = frames,
        keyframeIntervalSeconds = 1f,
        transfer = TransferKind.SDR,
        bitDepth = 8,
        isDolbyVision = false,
        audioMimeType = null,
        audioTrackCount = 0,
        hasLocation = false,
        fileSizeBytes = 0L,
    )

    fun write(file: File, rotationDegrees: Int = 0, frames: Int = FRAMES) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        file.delete()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            var track = -1
            var queued = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                check(SystemClock.elapsedRealtime() < deadline) { "The test video encoder timed out" }
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10_000L)
                    if (index >= 0) {
                        if (queued == frames) {
                            encoder.queueInputBuffer(index, 0, 0, queued * FRAME_US, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            fill(checkNotNull(encoder.getInputImage(index)) { "Encoder has no writable input image" })
                            encoder.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, queued * FRAME_US, 0)
                            queued++
                        }
                    }
                }
                val index = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                } else if (index >= 0) {
                    val data = checkNotNull(encoder.getOutputBuffer(index))
                    // Codec config travels in the track format, which the muxer already has.
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && bufferInfo.size > 0) {
                        check(track >= 0) { "Encoder produced a sample before its format" }
                        data.position(bufferInfo.offset)
                        data.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(track, data, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            muxer.stop()
        } finally {
            encoder.release()
            muxer.release()
        }
    }

    private fun fill(image: Image) {
        val luma = image.planes[0]
        for (y in 0 until HEIGHT) {
            val row = y * luma.rowStride
            for (x in 0 until WIDTH) {
                val quadrant = (if (y < HEIGHT / 2) 0 else 2) + (if (x < WIDTH / 2) 0 else 1)
                luma.buffer.put(row + x * luma.pixelStride, LUMA[quadrant].toByte())
            }
        }
        // Neutral chroma, so the picture is grey whatever colour matrix the decoder assumes.
        for (plane in 1..2) {
            val chroma = image.planes[plane]
            for (y in 0 until HEIGHT / 2) {
                val row = y * chroma.rowStride
                for (x in 0 until WIDTH / 2) {
                    chroma.buffer.put(row + x * chroma.pixelStride, 128.toByte())
                }
            }
        }
    }
}
