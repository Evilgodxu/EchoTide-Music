package com.yichao.evilgodxu.screens.home.component.bar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 标题栏：定时/收藏/横屏/设置；悬浮时不吃系统栏
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeTopBar(
    playbackState: MusicPlaybackState,
    isLiked: Boolean,
    favoriteEnabled: Boolean,
    onShowTimer: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleLandscape: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // 居中标题自定义内容，如横屏两行曲名
    centerContent: (@Composable () -> Unit)? = null,
    // 收起动画期间置 false，避免误触
    interactive: Boolean = true,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            if (centerContent != null) {
                centerContent()
            }
        },
        windowInsets = windowInsets,
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = onShowTimer, enabled = interactive) {
                        Icon(
                            imageVector = AppIcons.Timer,
                            contentDescription = stringResource(R.string.music_panel_timer_title),
                            tint = Color.White,
                        )
                    }
                    // 剩余时间叠在按钮下方，不占布局空间
                    if (playbackState.timerRemaining > 0) {
                        Text(
                            text = "${playbackState.timerRemaining}m",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 14.dp)
                                .clickable(enabled = interactive) { playbackState.stopTimer() }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onToggleFavorite,
                    enabled = interactive && favoriteEnabled,
                ) {
                    Icon(
                        imageVector = if (isLiked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                        contentDescription = stringResource(R.string.music_panel_favorite),
                        tint = Color.White,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleLandscape, enabled = interactive) {
                Icon(
                    imageVector = AppIcons.ScreenRotation,
                    contentDescription = stringResource(R.string.home_landscape_mode),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onOpenSettings, enabled = interactive) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = Color.White,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
        ),
    )
}
