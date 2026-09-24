package com.yichao.evilgodxu.screens.spectrum.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.spectrum.SpectrumAnalysis

// 结论名称与取值之间的间距
private val LABEL_GAP = 6.dp

// 曲目分析结论区：音质异常与 AI 合成两路判定横向并排，各占一半宽度。
// 判据与缓存复用曲库分析的同一条路径，两处结论必然一致
@Composable
internal fun SpectrumAnalysisPanel(
    analysis: SpectrumAnalysis,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        VerdictItem(
            label = stringResource(R.string.spectrum_analysis_fake_lossless),
            flaggedLabel = stringResource(R.string.spectrum_analysis_fake_detected),
            verdict = analysis.fakeLossless,
            checking = analysis.checking,
            modifier = Modifier.weight(1f),
        )
        VerdictItem(
            label = stringResource(R.string.spectrum_analysis_ai_music),
            flaggedLabel = stringResource(R.string.spectrum_analysis_ai_detected),
            verdict = analysis.aiMusic,
            checking = analysis.checking,
            modifier = Modifier.weight(1f),
        )
    }
}

// 单路结论：名称后紧跟取值。命中取告警色并加重字重，
// 未检出与不适用保持次要色，使两种非告警态一眼可分
@Composable
private fun VerdictItem(
    label: String,
    flaggedLabel: String,
    verdict: Boolean?,
    checking: Boolean,
    modifier: Modifier = Modifier,
) {
    val flagged = verdict == true
    val value = when {
        checking -> stringResource(R.string.spectrum_analysis_checking)
        verdict == null -> stringResource(R.string.spectrum_analysis_not_applicable)
        flagged -> flaggedLabel
        else -> stringResource(R.string.spectrum_analysis_clear)
    }
    val valueColor = when {
        flagged -> MaterialTheme.colorScheme.error
        checking || verdict == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(end = LABEL_GAP),
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = if (flagged) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
