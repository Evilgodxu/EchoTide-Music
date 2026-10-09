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
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.playModeMenuOrder
import com.yichao.evilgodxu.ui.component.PlayModeOptionButton
import com.yichao.evilgodxu.ui.component.PlayModeToggleButton
import com.yichao.evilgodxu.ui.component.selectPlayMode
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

@Composable
internal fun ControlBar(
    playbackState: MusicPlaybackState,
    // 播放模式选择展开态：由音乐面板持有，展开时四个控制按钮让位于四种播放模式
    modeSelectorExpanded: Boolean,
    onModeSelectorExpandedChange: (Boolean) -> Unit,
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
        PlayModeToggleButton(
            playMode = playbackState.playMode,
            expanded = modeSelectorExpanded,
            onExpandedChange = onModeSelectorExpandedChange,
            size = 32.dp,
            iconSize = 21.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (modeSelectorExpanded) {
            // 展开期间四个控制按钮让位给四种播放模式：选中即切换模式并收起，原按钮随后复位
            playModeMenuOrder.forEach { mode ->
                PlayModeOptionButton(
                    mode = mode,
                    selected = mode == playbackState.playMode,
                    onClick = {
                        selectPlayMode(playbackState, mode)
                        onModeSelectorExpandedChange(false)
                    },
                    size = 32.dp,
                    iconSize = 21.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
        ControlIconButton(
            icon = AppIcons.SkipPrevious,
            contentDescription = stringResource(R.string.music_panel_previous_track),
            onClick = {
                val prev = playbackState.previousIndex()
                if (prev >= 0) {
                    scope.launch {
                        playTrackAt(context, playbackState, prev, switchKind = TrackSwitchKind.Previous)
                    }
                }
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
                if (next >= 0) {
                    scope.launch {
                        playTrackAt(context, playbackState, next, switchKind = TrackSwitchKind.Next)
                    }
                }
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
