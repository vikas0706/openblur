package io.github.vikas0706.openblur.export

import android.app.Activity
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/** Gives the test process a visible activity, which Android requires before a foreground service may start. */
class TestHostActivity : Activity()

/** Writes small MP4 files with the device's own encoders so the tests need no bundled media. */
internal object TestVideoFactory {
    class Spec(
        val width: Int = 320,
        val height: Int = 240,
        val frames: Int = 60,
        val frameRate: Int = 30,
        val withAudio: Boolean = true,
        val orientationHint: Int = 0,
        val latitude: Float? = null,
        val longitude: Float? = null,
    ) {
        val durationUs: Long get() = frames * 1_000_000L / frameRate
    }

    private class Sample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private class Track(val format: MediaFormat, val samples: List<Sample>)

    fun create(file: File, spec: Spec = Spec()) {
        val video = encodeVideo(spec)
        val audio = if (spec.withAudio) encodeAudio(spec.durationUs) else null
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            if (spec.orientationHint != 0) muxer.setOrientationHint(spec.orientationHint)
            if (spec.latitude != null && spec.longitude != null) muxer.setLocation(spec.latitude, spec.longitude)
            val videoIndex = muxer.addTrack(video.format)
            val audioIndex = audio?.let { muxer.addTrack(it.format) }
            muxer.start()
            for (sample in video.samples) muxer.writeSampleData(videoIndex, sample.data, sample.info)
            if (audio != null && audioIndex != null) {
                for (sample in audio.samples) muxer.writeSampleData(audioIndex, sample.data, sample.info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    private fun encodeVideo(spec: Spec): Track {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 600_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var frame = 0
        return encode(codec, format) { index ->
            if (frame >= spec.frames) {
                codec.queueInputBuffer(index, 0, 0, spec.durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                true
            } else {
                val image = checkNotNull(codec.getInputImage(index)) { "The encoder gave no input image" }
                paint(image, frame)
                val bytes = spec.width * spec.height * 3 / 2
                codec.queueInputBuffer(index, 0, bytes, frame * 1_000_000L / spec.frameRate, 0)
                frame++
                false
            }
        }
    }

    /** A diagonal gradient that moves every frame, so the encoder has motion to work on. */
    private fun paint(image: Image, frame: Int) {
        for ((planeIndex, plane) in image.planes.withIndex()) {
            val width = if (planeIndex == 0) image.width else image.width / 2
            val height = if (planeIndex == 0) image.height else image.height / 2
            val buffer = plane.buffer
            for (y in 0 until height) {
                val row = y * plane.rowStride
                for (x in 0 until width) {
                    val value = if (planeIndex == 0) (x + y + frame * 4) and 0xFF else 128
                    buffer.put(row + x * plane.pixelStride, value.toByte())
                }
            }
        }
    }

    private fun encodeAudio(durationUs: Long): Track {
        val sampleRate = 44_100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val totalSamples = durationUs * sampleRate / 1_000_000L
        var written = 0L
        return encode(codec, format) { index ->
            val timeUs = written * 1_000_000L / sampleRate
            if (written >= totalSamples) {
                codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                true
            } else {
                val buffer = checkNotNull(codec.getInputBuffer(index))
                buffer.clear()
                val count = minOf((buffer.capacity() / 2).toLong(), totalSamples - written).toInt()
                for (i in 0 until count) {
                    val tone = sin(2.0 * PI * 440.0 * (written + i) / sampleRate) * 8_000
                    buffer.putShort(tone.toInt().toShort())
                }
                codec.queueInputBuffer(index, 0, count * 2, timeUs, 0)
                written += count
                false
            }
        }
    }

    /** Runs [codec] to the end. [feed] fills one input buffer and returns true once it has queued the end of stream. */
    private fun encode(codec: MediaCodec, format: MediaFormat, feed: (index: Int) -> Boolean): Track {
        val samples = ArrayList<Sample>()
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) inputDone = feed(inputIndex)
                }
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = codec.outputFormat
                } else if (outputIndex >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                        output.position(info.offset).limit(info.offset + info.size)
                        val copy = ByteBuffer.allocate(info.size).put(output)
                        copy.flip()
                        val copyInfo = MediaCodec.BufferInfo()
                        copyInfo.set(0, info.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        samples.add(Sample(copy, copyInfo))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            codec.release()
        }
        return Track(checkNotNull(outputFormat) { "The encoder never reported its output format" }, samples)
    }

    private const val TIMEOUT_US = 10_000L
}
