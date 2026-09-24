package com.yichao.evilgodxu.screens.spectrum.component

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.spectrum.SpectrumAnalysis

// 结论名称与取值之间的间距
private val LABEL_GAP = 6.dp

// 曲目分析结论区：音质异常与 AI 合成两路判定横向并排、各占一半宽度，取值居中于本路所占宽度。
// 结论取本页全曲解码的完整分析，并与曲库分析共用同一份判定缓存：该结论一旦产出即锁定本曲，
// 曲库分析的分段快速采样不再改写，故两处口径一致
@Composable
internal fun SpectrumAnalysisPanel(
    analysis: SpectrumAnalysis,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(modifier = modifier.fillMaxWidth()) {
        VerdictItem(
            display = fakeLosslessDisplay(context, analysis),
            modifier = Modifier.weight(1f),
        )
        VerdictItem(
            display = aiMusicDisplay(context, analysis),
            modifier = Modifier.weight(1f),
        )
    }
}

// 单路结论的展示文案与呈现态：界面与导出图取同一份口径，避免两处各写一遍取值映射
internal class VerdictDisplay(
    val label: String,
    val value: String,
    // 命中告警：加重字重并用告警色
    val flagged: Boolean,
    // 校验中或不适用：取值以次要色呈现，与「未检出」一眼可分
    val muted: Boolean,
)

// 音质异常一路的展示文案
internal fun fakeLosslessDisplay(context: Context, analysis: SpectrumAnalysis): VerdictDisplay =
    verdictDisplay(
        context = context,
        labelRes = R.string.spectrum_analysis_fake_lossless,
        flaggedRes = R.string.spectrum_analysis_fake_detected,
        verdict = analysis.fakeLossless,
        checking = analysis.checking,
    )

// AI 合成一路的展示文案
internal fun aiMusicDisplay(context: Context, analysis: SpectrumAnalysis): VerdictDisplay =
    verdictDisplay(
        context = context,
        labelRes = R.string.spectrum_analysis_ai_music,
        flaggedRes = R.string.spectrum_analysis_ai_detected,
        verdict = analysis.aiMusic,
        checking = analysis.checking,
    )

// 结论取名与取值：null 表示该维度不适用，校验中优先提示
private fun verdictDisplay(
    context: Context,
    labelRes: Int,
    flaggedRes: Int,
    verdict: Boolean?,
    checking: Boolean,
): VerdictDisplay {
    val flagged = verdict == true
    val value = when {
        checking -> context.getString(R.string.spectrum_analysis_checking)
        verdict == null -> context.getString(R.string.spectrum_analysis_not_applicable)
        flagged -> context.getString(flaggedRes)
        else -> context.getString(R.string.spectrum_analysis_clear)
    }
    return VerdictDisplay(
        label = context.getString(labelRes),
        value = value,
        flagged = flagged,
        muted = checking || verdict == null,
    )
}

// 单路结论：名称后紧跟取值，整体居中于本路所占宽度
@Composable
private fun VerdictItem(
    display: VerdictDisplay,
    modifier: Modifier = Modifier,
) {
    val valueColor = when {
        display.flagged -> MaterialTheme.colorScheme.error
        display.muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = display.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(end = LABEL_GAP),
        )
        Text(
            text = display.value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = if (display.flagged) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
