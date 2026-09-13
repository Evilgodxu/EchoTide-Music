package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.data.playlist.SmartPlaylistType
import com.yichao.evilgodxu.R

// 智能歌单名称文案
@Composable
internal fun smartTypeLabel(type: SmartPlaylistType): String = when (type) {
    SmartPlaylistType.RECENT -> stringResource(R.string.playlist_smart_recent)
    SmartPlaylistType.FAVORITE -> stringResource(R.string.playlist_smart_favorite)
    SmartPlaylistType.ALBUM -> stringResource(R.string.playlist_smart_album)
    SmartPlaylistType.ARTIST -> stringResource(R.string.playlist_smart_artist)
}
