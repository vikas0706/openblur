package io.github.vikas0706.openblur.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.core.MaskTimeline

internal enum class PreviewStatus { PREPARING, READY, FAILED, SOURCE_GONE }

/**
 * The one ExoPlayer of the editor screen, with the blur effect installed. Main thread only.
 * Released when Compose forgets it.
 */
@OptIn(UnstableApi::class)
internal class PreviewPlayer(
    context: Context,
    uri: Uri,
    displayWidth: Int,
    displayHeight: Int,
    private val mask: MaskEffectController,
    private val onFrameShown: (frameTimeUs: Long) -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit,
) : Playback, RememberObserver {
    var status by mutableStateOf(PreviewStatus.PREPARING)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setUsePlatformDiagnostics(false)
        .build()

    private val item = MediaItem.fromUri(uri)
    private val handler = Handler(Looper.getMainLooper())
    private var released = false

    // Effect timestamps equal source timestamps only if no seek reaches the player before its first STATE_READY.
    private var readyOnce = false
    private var pendingSeekUs = C.TIME_UNSET
    private var seekInFlight = false
    private val seekWatchdog = Runnable {
        seekInFlight = false
        flushPendingSeek()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                status = PreviewStatus.READY
                readyOnce = true
                seekInFlight = false
                handler.removeCallbacks(seekWatchdog)
                flushPendingSeek()
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            onPlayingChanged(playing)
        }

        override fun onPlayerError(error: PlaybackException) {
            // The error stopped the player and reset its renderers, so the next prepare starts a new time base.
            readyOnce = false
            seekInFlight = false
            handler.removeCallbacks(seekWatchdog)
            status = if (isSourceError(error)) PreviewStatus.SOURCE_GONE else PreviewStatus.FAILED
        }
    }

    init {
        val effects = ArrayList<Effect>(2)
        val height = previewDownscaleHeight(displayWidth, displayHeight)
        if (height > 0) effects += Presentation.createForHeight(height)
        effects += mask.effect
        mask.setTimeOffsetUs(0L)

        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audio, /* handleAudioFocus = */ true)
        player.setVideoEffects(effects)
        player.addListener(listener)
        // Called on the playback thread; with effects installed the time is the frame's source time.
        player.setVideoFrameMetadataListener(
            VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                handler.post { if (!released) onFrameShown(presentationTimeUs) }
            },
        )
        player.playWhenReady = false
        player.setMediaItem(item)
        player.prepare()
    }

    fun setTimeline(timeline: MaskTimeline?) {
        mask.setTimeline(timeline)
    }

    val positionUs: Long get() = player.currentPosition.coerceAtLeast(0L) * 1_000L

    override fun seekTo(positionUs: Long) {
        if (released) return
        if (!readyOnce || seekInFlight) {
            pendingSeekUs = positionUs
            return
        }
        startSeek(positionUs)
    }

    private fun startSeek(positionUs: Long) {
        seekInFlight = true
        // Rounded down so the frame at exactly this time is the one an exact seek shows.
        player.seekTo(positionUs / 1_000L)
        handler.removeCallbacks(seekWatchdog)
        handler.postDelayed(seekWatchdog, SEEK_WATCHDOG_MS)
    }

    private fun flushPendingSeek() {
        val target = pendingSeekUs
        if (target == C.TIME_UNSET || !readyOnce || released) return
        pendingSeekUs = C.TIME_UNSET
        startSeek(target)
    }

    /** A paused player does not redraw when the masks change; seeking to where it already is does. */
    fun redrawIfPaused(positionUs: Long) {
        if (!released && !player.isPlaying && status == PreviewStatus.READY) seekTo(positionUs)
    }

    override fun play() {
        if (released || status != PreviewStatus.READY) return
        if (player.playbackState == Player.STATE_ENDED) seekTo(0L)
        player.play()
    }

    override fun pause() {
        if (!released) player.pause()
    }

    fun setMuted(muted: Boolean) {
        if (!released) player.volume = if (muted) 0f else 1f
    }

    /** Prepares again after an error, from the default position, then seeks back to [positionUs] once ready. */
    fun retry(positionUs: Long) {
        if (released) return
        status = PreviewStatus.PREPARING
        readyOnce = false
        seekInFlight = false
        handler.removeCallbacks(seekWatchdog)
        pendingSeekUs = if (positionUs > 0L) positionUs else C.TIME_UNSET
        player.playWhenReady = false
        player.setMediaItem(item)
        player.prepare()
    }

    private fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.release()
        mask.setTimeline(null)
    }

    override fun onRemembered() {}

    override fun onForgotten() = release()

    override fun onAbandoned() = release()

    private companion object {
        // If the player never reports the end of a seek, stop waiting so later seeks are not held back forever.
        const val SEEK_WATCHDOG_MS = 500L

        /** Media3 numbers every input and output failure 2000 to 2999: missing file, lost permission, read error. */
        fun isSourceError(error: PlaybackException): Boolean = error.errorCode in 2000..2999
    }
}
