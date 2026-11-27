package com.yichao.evilgodxu.screens.home.component.audioinfo

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.metadata.AlbumImageStore
import com.yichao.evilgodxu.data.music.playback.AudioInfoSnapshot
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.OutputLatencyReading
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.report.AudioReportImage
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.player.AudioInfoContent
import com.yichao.evilgodxu.ui.component.player.AudioInfoGroup
import com.yichao.evilgodxu.ui.component.player.audioInfoGroups
import com.yichao.evilgodxu.ui.component.player.rememberAudioInfoSnapshot
import com.yichao.evilgodxu.ui.component.player.rememberOutputLatencyReading
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 到顶后下拉 64dp 累计即收起
private val DISMISS_OVERSCROLL_DP = 64.dp

// 文件名时间避开冒号，图片内时间便于阅读
private val IMAGE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val REPORT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** 音频信息弹窗：交互与视觉同播放列表弹窗 */
@Composable
internal fun AudioInfoSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val savedText = stringResource(R.string.audio_info_image_saved)
    val saveFailedText = stringResource(R.string.audio_info_image_save_failed)
    val imageTitle = stringResource(R.string.audio_info_title)
    val emptyText = stringResource(R.string.audio_info_empty)
    // 渲染与保存成图共用同一份数据
    val info = rememberAudioInfoData(playbackState, enabled = visible)
    val snapshot = info.snapshot
    val latency = info.latency
    val groups = snapshot?.let { audioInfoGroups(it, latency) }.orEmpty()
    // 竖屏半高、横屏铺满
    val sheetHeightFraction = if (rememberWindowLandscape()) 1f else 0.5f

    // 保存成图：复用报告排版，存档到相册
    fun saveImage() {
        if (saving) return
        saving = true
        scope.launch {
            val track = playbackState.currentTrack
                ?.takeIf { it.title.isNotBlank() || it.artist.isNotBlank() }
                ?.let { "${it.title} - ${it.artist}" }
            val success = runCatching {
                saveAudioInfoImage(context, imageTitle, track, groups, emptyText)
            }.getOrDefault(false)
            saving = false
            Toast.makeText(context, if (success) savedText else saveFailedText, Toast.LENGTH_SHORT).show()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val scrollState = rememberScrollState()
            val dismiss = rememberUpdatedState(onDismiss)
            val dismissOverscrollPx = with(LocalDensity.current) {
                DISMISS_OVERSCROLL_DP.toPx()
            }
            // 到顶后继续下拉，累计过阈值即收起
            val dismissNestedScroll = remember(scrollState, dismissOverscrollPx) {
                object : NestedScrollConnection {
                    private var overscrollAccum = 0f
                    private var dismissed = false

                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (dismissed || source != NestedScrollSource.UserInput) return Offset.Zero
                        val dy = available.y
                        if (dy > 0f && scrollState.value == 0) {
                            overscrollAccum += dy
                            if (overscrollAccum > dismissOverscrollPx) {
                                dismissed = true
                                dismiss.value()
                            }
                        } else {
                            overscrollAccum = 0f
                        }
                        return Offset.Zero
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(sheetHeightFraction)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    )
                    // 内边距只给左右与顶部，底部留白会露出色带
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.audio_info_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { saveImage() },
                        enabled = !saving,
                        modifier = Modifier.size(32.dp),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = AppIcons.Save,
                                contentDescription = stringResource(R.string.audio_info_save),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.audio_info_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                AudioInfoContent(
                    snapshot = snapshot,
                    latency = latency,
                    scrollState = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(dismissNestedScroll),
                )
            }
        }
    }
}

/** 展示与成图共用的两份采集结果 */
private data class AudioInfoData(
    val snapshot: AudioInfoSnapshot?,
    val latency: OutputLatencyReading?,
)

/**
 * 仅 enabled 时采集，收起动画沿用上次结果。
 * 必须在组合期读取，否则订阅不到、面板不重绘。
 */
@Composable
private fun rememberAudioInfoData(
    playbackState: MusicPlaybackState,
    enabled: Boolean,
): AudioInfoData {
    var lastShown by remember { mutableStateOf(AudioInfoData(null, null)) }
    val current = if (enabled) {
        AudioInfoData(
            snapshot = rememberAudioInfoSnapshot(playbackState).value,
            latency = rememberOutputLatencyReading(playbackState).value,
        )
    } else {
        lastShown
    }
    SideEffect { lastShown = current }
    return current
}

/** 生成报告同款图片，后台编码后存入相册 */
private suspend fun saveAudioInfoImage(
    context: Context,
    title: String,
    track: String?,
    groups: List<AudioInfoGroup>,
    emptyText: String,
): Boolean {
    val report = buildString {
        appendLine("======== EchoTideMusic $title ========")
        appendLine("生成时间: ${LocalDateTime.now().format(REPORT_TIME_FORMAT)}")
        track?.let { appendLine("曲目: $it") }
        if (groups.isEmpty()) {
            appendLine()
            appendLine(emptyText)
            return@buildString
        }
        groups.forEach { group ->
            appendLine()
            appendLine("---- ${group.title} ----")
            group.rows.forEach { row -> appendLine("${row.label}: ${row.value}") }
        }
    }
    val png = withContext(Dispatchers.Default) { AudioReportImage.renderPng(report) }
    val name = "音频信息-${LocalDateTime.now().format(IMAGE_TIME_FORMAT)}.png"
    return withContext(Dispatchers.IO) {
        AlbumImageStore.write(context, name, "image/png", png)
    }
}
