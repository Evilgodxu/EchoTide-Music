package com.yichao.evilgodxu.screens.home.component.player

import androidx.annotation.StringRes
import com.yichao.evilgodxu.R

// 曲库不可用原因，互斥：未授权 / 扫描中 / 空库
internal sealed interface LibraryEmptyHint {
    data object PermissionMissing : LibraryEmptyHint
    data object Scanning : LibraryEmptyHint
    data object NoTracks : LibraryEmptyHint
}

// 先判授权再判扫描，扫描结束才知空库
internal fun libraryEmptyHint(mediaAudioGranted: Boolean, isScanning: Boolean): LibraryEmptyHint =
    when {
        !mediaAudioGranted -> LibraryEmptyHint.PermissionMissing
        isScanning -> LibraryEmptyHint.Scanning
        else -> LibraryEmptyHint.NoTracks
    }

@get:StringRes
internal val LibraryEmptyHint.textRes: Int
    get() = when (this) {
        LibraryEmptyHint.PermissionMissing -> R.string.home_player_permission_missing
        LibraryEmptyHint.Scanning -> R.string.home_player_scanning
        LibraryEmptyHint.NoTracks -> R.string.home_player_empty
    }
