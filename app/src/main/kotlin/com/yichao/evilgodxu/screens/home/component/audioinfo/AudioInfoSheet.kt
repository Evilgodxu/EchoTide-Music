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

// 音频信息下拉收起的累计距离阈值：内容已到顶部后继续下拉超过该距离即收起
private val DISMISS_OVERSCROLL_DP = 64.dp

// 文件名时间戳与图片内的生成时间分两种写法：前者避开冒号，后者便于阅读
private val IMAGE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val REPORT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 首页竖屏的音频信息弹窗：交互与视觉沿用播放列表弹窗
 * （遮罩点击、顶部圆角面板自底部滑入、关闭按钮、内容到顶后继续下拉收起）。
 */
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
    // 快照与延迟只在面板可见时采集（内容区同样只在可见时组合），渲染与保存成图共用同一份数据
    var snapshot by remember { mutableStateOf<AudioInfoSnapshot?>(null) }
    var latency by remember { mutableStateOf<OutputLatencyReading?>(null) }
    if (visible) {
        val collected by rememberAudioInfoSnapshot(playbackState)
        val sampledLatency by rememberOutputLatencyReading(playbackState)
        SideEffect {
            snapshot = collected
            latency = sampledLatency
        }
    }
    val groups = snapshot?.let { audioInfoGroups(it, latency) }.orEmpty()
    // 竖屏面板半高，横屏铺满：与播放列表弹窗一致
    val sheetHeightFraction = if (rememberWindowLandscape()) 1f else 0.5f

    // 保存成图：复用音频报告的排版，把当前全部分组写成图片后存档到相册
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
            // 内容已到顶部时继续下拉：累计位移超过阈值即收起面板
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
                    // 内边距只给左右与顶部：面板底色铺到屏幕底缘，底部再留内边距就会在末行下方
                    // 空出一条与底色同色、不随内容滚动的色带
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

/**
 * 把当前音频信息写成与设备音频报告同款的排版图片并存档到相册。
 *
 * 文本按报告的层级语法组织（标题、分区、字段），复用 [AudioReportImage] 的渲染管线，
 * 因此字体、表格边框、留白与报告完全一致；排版与编码放后台，落盘走相册原语。
 */
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
