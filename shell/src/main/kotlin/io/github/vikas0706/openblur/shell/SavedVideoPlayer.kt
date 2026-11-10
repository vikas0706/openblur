package io.github.vikas0706.openblur.shell

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.LabeledSlider
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import kotlinx.coroutines.delay

/**
 * Plays the exported file as it was written to disk, with no effects, so what the user sees here is
 * what anyone they send it to will see. [modifier] bounds the picture; the controls sit below it.
 */
@Composable
internal fun SavedVideoPlayer(uri: Uri, aspect: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var ended by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }

    val player = remember(uri) {
        try {
            ExoPlayer.Builder(context.applicationContext).build()
        } catch (_: RuntimeException) {
            null
        }
    }

    DisposableEffect(player) {
        if (player == null) {
            failed = true
            return@DisposableEffect onDispose { }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val duration = player.duration
                if (duration != C.TIME_UNSET) durationMs = duration
                ended = playbackState == Player.STATE_ENDED
                positionMs = player.currentPosition
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, isPlaying) {
        while (isPlaying && player != null) {
            positionMs = player.currentPosition
            delay(POSITION_POLL_MS)
        }
    }

    // Audio from a screen the user has left would be a surprise.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player?.pause() }

    val description = stringResource(R.string.result_player_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            VideoBackdrop(aspect, Modifier.semantics { contentDescription = description }) {
                if (player != null) PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            }
        }
        if (failed || player == null) {
            Notice(text = stringResource(R.string.result_player_error), tone = NoticeTone.Error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAction(
                    icon = if (isPlaying) OpenBlurIcons.Pause else OpenBlurIcons.Play,
                    contentDescription = stringResource(if (isPlaying) R.string.result_pause else R.string.result_play),
                    onClick = {
                        if (isPlaying) {
                            player.pause()
                        } else {
                            if (ended) player.seekTo(0L)
                            player.play()
                        }
                    },
                )
                LabeledSlider(
                    label = stringResource(R.string.result_position),
                    value = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        val target = (fraction * durationMs).toLong()
                        positionMs = target
                        player.seekTo(target)
                    },
                    valueText = stringResource(
                        R.string.result_position_value,
                        formatTimeUs(positionMs * 1000L),
                        formatTimeUs(durationMs * 1000L),
                    ),
                    modifier = Modifier.weight(1f),
                    enabled = durationMs > 0L,
                )
            }
        }
    }
}

private const val POSITION_POLL_MS = 100L
