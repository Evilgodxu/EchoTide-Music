package com.yichao.evilgodxu.screens.home.component.analysis

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.analysis.trackFormatCategory
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.switchToPlaylistQueue
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 导航行高 30dp，可见 3 行，超出才滚动
private val FormatListRowHeight = 30.dp
private const val FormatListVisibleRows = 3

// 曲库分析：圆环统计格式占比，可按格式定位
@Composable
internal fun LibraryAnalysisSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    analysis: LibraryAnalysisController,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val metadataEnricher = LocalMetadataEnricher.current
    // 全量统计，切过滤来源时范围不变
    val stats = remember(playbackState.libraryTracks) {
        analyzeLibraryFormats(context, playbackState.libraryTracks)
    }
    val currentKey = playbackState.queueFilter?.key

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_analysis_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.library_analysis_formats,
                        stats.size,
                        stats.size,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.home_player_close_playlist),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (stats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.library_analysis_empty),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val total = stats.sumOf { it.count }
                // 圆环 + 图例：图例行点击切到格式歌单
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SegmentedFormatRing(
                            stats = stats,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = pluralStringResource(R.plurals.music_panel_track_count, total, total),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = pluralStringResource(
                                    R.plurals.library_analysis_formats,
                                    stats.size,
                                    stats.size,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        stats.forEachIndexed { index, stat ->
                            FormatStatRow(
                                stat = stat,
                                color = FORMAT_COLOR_PALETTE[index % FORMAT_COLOR_PALETTE.size],
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat, metadataEnricher)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.library_analysis_select_format),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 不超 3 行按内容展示，超出才固定高度滚动
                if (stats.size <= FormatListVisibleRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        stats.forEachIndexed { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat, metadataEnricher)
                                    onDismiss()
                                },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .height(FormatListRowHeight * FormatListVisibleRows),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(stats, key = { _, stat -> stat.key }) { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                isCurrent = currentKey == formatSourceKey(stat.key),
                                onClick = {
                                    switchToFormat(context, playbackState, stat, metadataEnricher)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// 把格式设为队列过滤；全局作用域执行，关闭不取消
private fun switchToFormat(
    context: Context,
    playbackState: MusicPlaybackState,
    stat: FormatStat,
    metadataEnricher: MetadataEnricher,
) {
    playbackState.playbackScope.launch {
        val tracks = playbackState.libraryTracks.filter { trackFormatCategory(context, it) == stat.name }
        switchToPlaylistQueue(
            context = context,
            state = playbackState,
            tracks = tracks,
            source = PlaylistSource(formatSourceKey(stat.key), stat.name),
            metadataEnricher = metadataEnricher,
        )
    }
}

// 格式歌单来源 key：刷新后据此重建歌单
private fun formatSourceKey(key: String): String = "smart:FORMAT:$key"

// 单格式统计；key 为标识，name 为展示名
private data class FormatStat(
    val key: String,
    val name: String,
    val count: Int,
    val percent: Float,
)

// 按扩展名归类，取前 5 种，其余并入「其他」
private fun analyzeLibraryFormats(context: Context, tracks: List<MusicTrack>): List<FormatStat> {
    val counts = linkedMapOf<String, Int>()
    tracks.forEach { track ->
        val key = trackFormatCategory(context, track)
        counts[key] = (counts[key] ?: 0) + 1
    }
    val total = counts.values.sum()
    if (total == 0) return emptyList()
    val sorted = counts.entries.sortedByDescending { it.value }
    val result = sorted.take(5).associate { it.key to it.value }.toMutableMap()
    val restCount = sorted.drop(5).sumOf { it.value }
    val otherName = context.getString(R.string.library_analysis_other)
    if (restCount > 0 && otherName !in result) {
        result[otherName] = restCount
    }
    return result.map { (name, count) ->
        // 占比保留一位小数
        FormatStat(name, name, count, (count * 1000f / total).roundToInt() / 10f)
    }
}

// 前 5 档可区分色相，末档灰作兜底
private val FORMAT_COLOR_PALETTE = listOf(
    Color(0xFF4A6CF7),
    Color(0xFF12B5A5),
    Color(0xFFFFB020),
    Color(0xFFE8636B),
    Color(0xFFA371F7),
    Color(0xFF9AA4B2),
)

// 按占比绘制圆角圆弧，小段收紧间距
@Composable
private fun SegmentedFormatRing(
    stats: List<FormatStat>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.13f
        val ringSize = Size(size.minDimension, size.minDimension)
        var startAngle = -90f
        stats.forEachIndexed { index, stat ->
            // 末段按剩余角度补齐，规避小数累计偏差
            val isLast = index == stats.lastIndex
            val sweep = if (isLast) {
                360f - (startAngle + 90f)
            } else {
                stat.percent / 100f * 360f
            }
            // 段间距随占比收敛：小段避免被间距吞掉
            val gap = minOf(3f, sweep * 0.25f)
            val drawSweep = (sweep - gap).coerceAtLeast(0f)
            if (drawSweep > 0.3f) {
                drawArc(
                    color = FORMAT_COLOR_PALETTE[index % FORMAT_COLOR_PALETTE.size],
                    startAngle = startAngle + gap / 2f,
                    sweepAngle = drawSweep,
                    useCenter = false,
                    topLeft = Offset(0f, 0f),
                    size = ringSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            startAngle += sweep
        }
    }
}

// 图例行：色点 + 名称 + 数量，点击切换
@Composable
private fun FormatStatRow(
    stat: FormatStat,
    color: Color,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(
            text = stat.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(R.plurals.music_panel_track_count, stat.count, stat.count),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

// 导航项：统一配色，两处复用
@Composable
private fun FormatNavRowItem(
    index: Int,
    stat: FormatStat,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    FormatNavRow(
        stat = stat,
        color = FORMAT_COLOR_PALETTE[index % FORMAT_COLOR_PALETTE.size],
        isCurrent = isCurrent,
        onClick = onClick,
    )
}

// 导航行：色点 + 名称 + 占比 + 箭头，点击切换
@Composable
private fun FormatNavRow(
    stat: FormatStat,
    color: Color,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Text(
            text = stat.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatPercent(stat.percent),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Icon(
            imageVector = AppIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// 整数不带小数，否则保留一位
private fun formatPercent(percent: Float): String =
    if (percent == percent.toInt().toFloat()) {
        String.format(Locale.US, "%.0f%%", percent)
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }

// 显隐由首页层持有，跨形态保持
internal class LibraryAnalysisController {
    var visible by mutableStateOf(false)
        private set

    fun open() {
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}
