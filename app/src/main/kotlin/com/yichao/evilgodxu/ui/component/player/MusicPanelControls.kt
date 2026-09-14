package com.yichao.evilgodxu.ui.component.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.playback.applyPlaybackMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

@Composable
internal fun ControlBar(
    playbackState: MusicPlaybackState,
    onPlaylistClick: () -> Unit,
    onLyricsRefreshClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        val modeIcon = when (playbackState.playMode) {
            PlayMode.RepeatAll -> AppIcons.Repeat
            PlayMode.RepeatOne -> AppIcons.RepeatOne
            PlayMode.Shuffle -> AppIcons.Shuffle
        }
        ControlIconButton(
            icon = modeIcon,
            contentDescription = stringResource(R.string.music_panel_play_mode),
            onClick = {
                playbackState.setPlayMode(when (playbackState.playMode) {
                    PlayMode.RepeatAll -> PlayMode.RepeatOne
                    PlayMode.RepeatOne -> PlayMode.Shuffle
                    PlayMode.Shuffle -> PlayMode.RepeatAll
                })
                playbackState.mediaController?.let { controller ->
                    applyPlaybackMode(controller, playbackState.playMode)
                }
                playbackState.persistState()
            },
            size = 32.dp,
            iconSize = 21.dp
        )

        ControlIconButton(
            icon = AppIcons.SkipPrevious,
            contentDescription = stringResource(R.string.music_panel_previous_track),
            onClick = {
                val prev = playbackState.previousIndex()
                if (prev >= 0) scope.launch { playTrackAt(context, playbackState, prev) }
            },
            enabled = playbackState.playlist.isNotEmpty(),
            size = 32.dp,
            iconSize = 21.dp
        )

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
            shadowElevation = 0.dp,
            onClick = {
                togglePlayPause(playbackState)
            }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (playbackState.isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = stringResource(
                        if (playbackState.isPlaying) R.string.music_panel_pause else R.string.music_panel_play
                    ),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        ControlIconButton(
            icon = AppIcons.SkipNext,
            contentDescription = stringResource(R.string.music_panel_next_track),
            onClick = {
                val next = playbackState.nextIndex()
                if (next >= 0) scope.launch { playTrackAt(context, playbackState, next) }
            },
            enabled = playbackState.playlist.isNotEmpty(),
            size = 32.dp,
            iconSize = 21.dp
        )

        ControlIconButton(
            icon = AppIcons.QueueMusic,
            contentDescription = stringResource(R.string.music_panel_playlist),
            onClick = onPlaylistClick,
            size = 32.dp,
            iconSize = 21.dp
        )
        }

        if (playbackState.isLyricsVisible) {
            Surface(
                shape = CircleShape,
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-110).dp)
                    .size(32.dp),
                onClick = onLyricsRefreshClick
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.music_panel_lyrics_short),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
internal fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}
