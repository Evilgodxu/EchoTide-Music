package com.yichao.evilgodxu.screens.home.component.player

import androidx.annotation.StringRes
import com.yichao.evilgodxu.R

// 曲库不可用的原因。三者互斥：没有音频访问就读不到任何曲目，扫描期间结论尚未可知，
// 只有授权就绪且扫描结束仍无曲目才是「暂无本地音乐」。三者提示与用户该做的事各不相同，
// 合成一句会让「未授权」与「空库」同时出现在屏幕上
internal sealed interface LibraryEmptyHint {
    data object PermissionMissing : LibraryEmptyHint
    data object Scanning : LibraryEmptyHint
    data object NoTracks : LibraryEmptyHint
}

// 成因判定：缺授权时不会开始扫描，故授权先于扫描；扫描给出结论前不能判定空库
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
