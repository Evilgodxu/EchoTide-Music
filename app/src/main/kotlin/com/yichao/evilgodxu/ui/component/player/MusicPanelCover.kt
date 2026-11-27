package com.yichao.evilgodxu.ui.component.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.CoverTransition
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider
import com.yichao.evilgodxu.ui.component.rememberSystemThumbnail
import com.yichao.evilgodxu.LocalPlaybackState

// 索引取系统缩略图，否则内嵌封面
@Composable
private fun SystemCoverArt(
    track: MusicTrack?,
    modifier: Modifier,
    thumbnailSize: Int,
    placeholderIconSize: Dp,
    animated: Boolean,
) {
    val playbackState = LocalPlaybackState.current
    val thumb = rememberSystemThumbnail(track, thumbnailSize)
    // 滚入视口时按需补全元数据
    LaunchedEffect(track?.id) {
        track?.let { playbackState.requestMetadata(it) }
    }
    CoverTransition(
        bitmap = thumb,
        contentDescription = track?.title,
        placeholderColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
        placeholderIconSize = placeholderIconSize,
        animated = animated,
    )
}

// 迷你光碟与刷新预览共用取图，档位按显示尺寸
@Composable
internal fun AlbumArt(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    thumbnailSize: Int,
    animated: Boolean = false,
) {
    SystemCoverArt(
        track,
        modifier,
        thumbnailSize = thumbnailSize,
        placeholderIconSize = 24.dp,
        animated = animated,
    )
}

@Composable
internal fun CoverContextMenu(
    visible: Boolean,
    onOnlineCover: () -> Unit,
    onLocalCover: () -> Unit,
    onSaveCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
            popupPositionProvider = menuEdgePositionProvider(atTop = false),
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                Row(horizontalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onOnlineCover) {
                        Text(
                            text = stringResource(R.string.music_panel_online_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onLocalCover) {
                        Text(
                            text = stringResource(R.string.music_panel_local_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onSaveCover) {
                        Text(
                            text = stringResource(R.string.music_panel_save_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniContextMenu(
    visible: Boolean,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
    // 单目标直接搜索，多目标下钻候选
    searchTargets: List<String> = emptyList(),
    onSearchTarget: (String) -> Unit = {},
) {
    // 每次唤起从一级菜单开始
    var showSearchTargets by remember(visible) { mutableStateOf(false) }

    if (visible) {
        Popup(
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            onDismissRequest = onDismiss,
            popupPositionProvider = menuEdgePositionProvider(atTop = true),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (showSearchTargets) {
                    // 二级菜单：横向排列搜索目标
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        searchTargets.forEachIndexed { index, target ->
                            if (index > 0) Spacer(modifier = Modifier.width(2.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                onClick = { onSearchTarget(target) }
                            ) {
                                Text(
                                    text = target,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onCopy
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_copy),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onRename
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_rename),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                        if (searchTargets.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Transparent,
                                onClick = {
                                    if (searchTargets.size > 1) {
                                        showSearchTargets = true
                                    } else {
                                        onSearchTarget(searchTargets.first())
                                    }
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.music_panel_menu_search),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
