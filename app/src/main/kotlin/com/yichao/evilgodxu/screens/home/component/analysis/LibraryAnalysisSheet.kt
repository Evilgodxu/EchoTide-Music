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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.analysis.AiMusicAnalyzer
import com.yichao.evilgodxu.data.music.analysis.analyzeLibraryCombined
import com.yichao.evilgodxu.data.music.analysis.FakeLosslessAnalyzer
import com.yichao.evilgodxu.data.music.analysis.trackFormatCategory
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.switchToPlaylistQueue
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 格式导航行高与可见行数：列表项超出可见行数时固定该高度滚动，避免对话框随格式数量拉伸
private val FormatListRowHeight = 30.dp
private const val FormatListVisibleRows = 3

// 曲库分析对话框：长按首页播放列表按钮弹出，圆环统计格式占比，下方按格式定位歌单
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
    // 全量库统计：切换歌单时曲库范围不变，仅依赖全量库数据
    val stats = remember(playbackState.libraryTracks) {
        analyzeLibraryFormats(context, playbackState.libraryTracks)
    }
    // 对话框打开或曲库变化时触发分析：任务由控制器在首页层后台执行，
    // 关闭对话框不中断，重开时沿用进行中的进度。
    // 首次启动的扫描与封面/歌词补全进行中时让路：频谱解码与封面位图解码同为 CPU 重活，
    // 并发只会同时拖慢首屏补齐与分析本身，故等库就绪（既不扫描也不补全）后再自动开始；
    // 等待期间收起对话框即取消本次触发，不会留下后台任务
    LaunchedEffect(playbackState.libraryTracks) {
        snapshotFlow { playbackState.isScanning || playbackState.isEnrichingMetadata }
            .first { !it }
        analysis.onSheetOpen(playbackState.libraryTracks)
    }
    val currentKey = playbackState.playlistSource?.key

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
                    text = stringResource(R.string.library_analysis_formats, stats.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                // 刷新：清空校验缓存后全量重新分析，规避识别策略更新后旧缓存复用导致音质异常被放行；
                // 分析进行中置灰不可点，防手抖/重复触发
                IconButton(
                    onClick = { analysis.onRefresh(playbackState.libraryTracks) },
                    enabled = !analysis.analyzing,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = stringResource(R.string.library_analysis_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
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
                // 音质异常 / AI 音乐为识别算法的补充类目：仅在校验发现的疑似文件并入定位列表
                val fakeCount = analysis.fakeLosslessCount
                val hasFakeLossless = fakeCount != null && fakeCount > 0
                val aiCount = analysis.aiMusicCount
                val hasAiMusic = aiCount != null && aiCount > 0
                val specialRowCount = (if (hasFakeLossless) 1 else 0) + (if (hasAiMusic) 1 else 0)
                val navStats = buildList {
                    if (hasFakeLossless) {
                        add(
                            FormatStat(
                                key = FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY,
                                name = stringResource(R.string.library_analysis_fake_lossless),
                                count = fakeCount,
                                percent = (fakeCount * 1000f / total).roundToInt() / 10f,
                            ),
                        )
                    }
                    if (hasAiMusic) {
                        add(
                            FormatStat(
                                key = AiMusicAnalyzer.AI_MUSIC_KEY,
                                name = stringResource(R.string.library_analysis_ai_music),
                                count = aiCount,
                                percent = (aiCount * 1000f / total).roundToInt() / 10f,
                            ),
                        )
                    }
                    addAll(stats)
                }
                // 圆环 + 图例：图例行点击切换到对应格式歌单
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
                                text = stringResource(R.string.music_panel_track_count, total),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.library_analysis_formats, stats.size),
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
                    // 校验中在标题右侧提示（含逐曲进度），显隐不改变列表区高度
                    val progress = analysis.checkingProgress
                    when {
                        progress != null -> Text(
                            text = stringResource(
                                R.string.library_analysis_check_progress,
                                progress.first,
                                progress.second,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        analysis.fakeLosslessCount == null -> Text(
                            text = stringResource(R.string.library_analysis_checking),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // 格式列表不超过可见行数时按内容完整展示（不滚动），超出才固定高度滚动，
                // 避免仅有 3 条时仍出现滚动条
                if (navStats.size <= FormatListVisibleRows) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        navStats.forEachIndexed { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                specialRowCount = specialRowCount,
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
                        itemsIndexed(navStats, key = { _, stat -> stat.key }) { index, stat ->
                            FormatNavRowItem(
                                index = index,
                                stat = stat,
                                specialRowCount = specialRowCount,
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

// 切换播放列表为指定格式/识别类目曲目（音质异常、AI 音乐按校验结果过滤，其余按格式分类过滤），
// 复用歌单切换（备份默认列表 + 加载首曲不自动播放 + 补全元数据）。
// 在播放器全局作用域执行：识别类目过滤需读文件（缓存命中即瞬时返回），且弹层关闭不取消切换
private fun switchToFormat(
    context: Context,
    playbackState: MusicPlaybackState,
    stat: FormatStat,
    metadataEnricher: MetadataEnricher,
) {
    playbackState.playbackScope.launch {
        val tracks = playbackState.libraryTracks.filter { track ->
            when (stat.key) {
                FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY -> withContext(Dispatchers.IO) {
                    FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)
                }
                AiMusicAnalyzer.AI_MUSIC_KEY -> withContext(Dispatchers.IO) {
                    AiMusicAnalyzer.isSuspectedAiMusic(context, track)
                }
                else -> trackFormatCategory(context, track) == stat.name
            }
        }
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

// 单个格式的占比统计；key 为稳定标识（格式名或识别类目键），name 为展示名
private data class FormatStat(
    val key: String,
    val name: String,
    val count: Int,
    val percent: Float,
)

// 统计曲库格式占比：按文件扩展名归类，取数量前 5 种，其余合并为「其他」
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

// 分段圆环配色：前 5 档为可区分的色相，末档灰用于「其他」等兜底
private val FORMAT_COLOR_PALETTE = listOf(
    Color(0xFF4A6CF7),
    Color(0xFF12B5A5),
    Color(0xFFFFB020),
    Color(0xFFE8636B),
    Color(0xFFA371F7),
    Color(0xFF9AA4B2),
)

// 分段圆环：按占比绘制带间距与圆角端点的圆弧，小占比段自动收紧间距
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
            // 末段按剩余角度补齐，规避占比保留一位小数造成的累计偏差
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

// 图例行：色点 + 格式名 + 数量，点击切换到对应格式歌单
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
            text = stringResource(R.string.music_panel_track_count, stat.count),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

// 格式导航项：统一配色规则，供普通 Column 与滚动 LazyColumn 两处复用；
// specialRowCount 为前置识别类目数（音质异常/AI 音乐），用于普通格式行色板索引回退
@Composable
private fun FormatNavRowItem(
    index: Int,
    stat: FormatStat,
    specialRowCount: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    FormatNavRow(
        stat = stat,
        color = when (stat.key) {
            FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY -> FORMAT_COLOR_PALETTE[3]
            AiMusicAnalyzer.AI_MUSIC_KEY -> FORMAT_COLOR_PALETTE[4]
            // 普通格式行减去前置识别类目数后映射色板，保证与头部统计区的颜色一致
            else -> FORMAT_COLOR_PALETTE[
                (index - specialRowCount).coerceAtLeast(0) % FORMAT_COLOR_PALETTE.size
            ]
        },
        isCurrent = isCurrent,
        onClick = onClick,
    )
}

// 格式导航行：色点 + 格式名 + 占比 + 进入箭头，占比在箭头左侧，点击切换到该格式歌单
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

// 百分比显示：整数不带小数，否则保留一位小数
private fun formatPercent(percent: Float): String =
    if (percent == percent.toInt().toFloat()) {
        String.format(Locale.US, "%.0f%%", percent)
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }

// 曲库分析会话控制器：状态与分析协程常驻首页层（对话框关闭不销毁），
// 关闭对话框后任务在后台继续执行，标题区据 analyzing 展示分析进度
internal class LibraryAnalysisController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    // 对话框显隐：关闭仅收起展示，不中断分析
    var visible by mutableStateOf(false)
        private set
    // 分析进度：(已校验数, 总数)，null 表示未在分析或已结束
    var checkingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    // 音质异常 / AI 音乐识别结果：null 表示对应识别阶段尚未完成
    var fakeLosslessCount by mutableStateOf<Int?>(null)
        private set
    var aiMusicCount by mutableStateOf<Int?>(null)
        private set
    // 分析进行中：驱动刷新按钮防抖；对话框收起后仍在后台执行
    var analyzing by mutableStateOf(false)
        private set

    // 当前分析任务与最近一次分析的曲库快照（曲库变化时强制重启）
    private var analysisJob: Job? = null
    private var analyzedTracks: List<MusicTrack>? = null

    fun open() {
        visible = true
    }

    fun dismiss() {
        visible = false
    }

    // 对话框打开或曲库变化时触发：分析已完成或曲库范围变化则启动增量分析；
    // 后台任务进行中再次打开沿用当前进度，不重复启动
    fun onSheetOpen(tracks: List<MusicTrack>) {
        if (analyzedTracks !== tracks || !analyzing) {
            startAnalysis(tracks, refreshed = false)
        }
    }

    // 手动刷新：清空校验缓存（旧版本判定结果不可复用）后强制全量重新分析
    fun onRefresh(tracks: List<MusicTrack>) {
        startAnalysis(tracks, refreshed = true)
    }

    private fun startAnalysis(tracks: List<MusicTrack>, refreshed: Boolean) {
        analysisJob?.cancel()
        analyzedTracks = tracks
        analysisJob = scope.launch {
            val job = coroutineContext[Job]
            analyzing = true
            checkingProgress = null
            fakeLosslessCount = null
            aiMusicCount = null
            try {
                if (refreshed) {
                    FakeLosslessAnalyzer.resetCache(context)
                    AiMusicAnalyzer.resetCache(context)
                }
                // 合并单次遍历：每文件只解码一次，同时产出音质异常与 AI 判定；
                // 进度以本批需解码文件数为基数连续递增
                val result = analyzeLibraryCombined(
                    context = context,
                    tracks = tracks,
                    onProgress = { checked, total ->
                        if (total > 0) checkingProgress = checked to total
                    },
                )
                fakeLosslessCount = result.fakeLosslessCount
                aiMusicCount = result.aiMusicCount
            } finally {
                // 仅当仍是当前任务时复位，避免被新一轮任务抢先覆盖状态
                if (analysisJob === job) {
                    analyzing = false
                    checkingProgress = null
                }
            }
        }
    }
}