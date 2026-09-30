package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicQuality

// 复用音质选择对话框：在线搜索与导入歌单等场景供用户自行决定音质，点击选项后由调用方回调处理
@Composable
internal fun QualitySelectDialog(
    title: String,
    onSelect: (MusicQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(onDismiss = onDismiss, title = title) {
        // 仅列出用户可选档位；Hi-Res 由无损档在解析时自动优先
        MusicQuality.entries.filter { it.userSelectable }.forEach { quality ->
            DialogOption(
                label = stringResource(qualityLabelRes(quality)),
                onClick = { onSelect(quality) },
            )
        }
    }
}

// 音质档位的展示文案：Hi-Res 与无损合并为「无损」，与解析时无损档自动优先的约定一致
internal fun qualityLabelRes(quality: MusicQuality): Int = when (quality) {
    MusicQuality.HI_RES,
    MusicQuality.LOSSLESS -> R.string.music_quality_lossless
    MusicQuality.HIGH -> R.string.music_quality_high
    MusicQuality.STANDARD -> R.string.music_quality_standard
}
