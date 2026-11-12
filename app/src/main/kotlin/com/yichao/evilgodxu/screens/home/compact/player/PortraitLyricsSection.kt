package com.yichao.evilgodxu.screens.home.compact.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.homePortraitLyricLayoutFlow
import com.yichao.evilgodxu.ui.component.player.LyricsPanel
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.delay

// 歌词微调单次步长（毫秒）
private const val LyricFineTuneStepMs = 100L

// 竖屏歌词区：按用户排版偏好决定可见行数，点击切换微调按钮，长按交给上层打开歌词菜单。
// 高度随字号、行数与行间距自适应，外层据此在封面与控制栏之间均分剩余空间
@Composable
internal fun PortraitLyricsSection(
    playbackState: MusicPlaybackState,
    onVerticalFling: ((next: Boolean) -> Unit)?,
    // 歌词纵向拖拽接管手势的出口：宿主据此收回触摸瞬间顺带唤出的标题栏
    onVerticalDragClaim: (() -> Unit)?,
    onLongPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
    // 歌词菜单由上层持有状态，但必须挂在此区域内部：菜单 Popup 以调用处布局节点为锚点，
    // 移出本区域会按整屏定位而失去「贴着歌词区底部向内展开」的位置
    menuContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val homePortraitLayout by context.homePortraitLyricLayoutFlow()
        .collectAsStateWithLifecycle(initialValue = LyricLayoutDefaults.HOME_PORTRAIT_LAYOUT)

    // 歌词区高度随可见行数、字号与行间距自适应：每行占行高与上下 2dp 内边距，行间留出设置的行间距，
    // 末尾补足歌词面板顶部 4dp 内边距，使外层高度与内部歌词窗口一致。
    // 行高与行间距都必须与 LyricsPanel 同口径（行高沿用 LocalTextStyle 的 24sp 行高），否则外层偏矮裁剪歌词
    val textMeasurer = rememberTextMeasurer()
    val lyricTextStyle = LocalTextStyle.current
    val lyricLineHeight = with(LocalDensity.current) {
        textMeasurer.measure(
            AnnotatedString("歌词"),
            lyricTextStyle.merge(TextStyle(fontSize = homePortraitLayout.fontSizeSp.sp)),
        ).size.height.toDp()
    }
    val lyricsAreaHeight = (lyricLineHeight + 4.dp) * homePortraitLayout.visibleLines +
        homePortraitLayout.lineSpacingDp.dp * (homePortraitLayout.visibleLines - 1) + 4.dp

    // 歌词微调按钮显示状态：点击歌词区切换
    var lyricTuneVisible by remember { mutableStateOf(false) }
    // 微调操作计数：每次调整自增以重置自动隐藏计时
    var tuneVersion by remember { mutableIntStateOf(0) }
    // 浮动提示：显示当前微调的毫秒数
    var tuneHintText by remember { mutableStateOf("") }
    var tuneHintVersion by remember { mutableIntStateOf(0) }
    // 显示后 2 秒无操作自动隐藏
    LaunchedEffect(lyricTuneVisible, tuneVersion) {
        if (lyricTuneVisible) {
            delay(2000)
            lyricTuneVisible = false
        }
    }
    // 浮动提示暂显后自动消失
    LaunchedEffect(tuneHintVersion) {
        if (tuneHintText.isNotEmpty()) {
            delay(800)
            tuneHintText = ""
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 封面与歌词间距（下限）
        Spacer(Modifier.height(8.dp))
        // 歌词：高度随设置的可见行数自适应（默认 5 行），点击歌词区切换微调按钮显隐
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(lyricsAreaHeight),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (playbackState.currentTrack != null) {
                    LyricsPanel(
                        playbackState = playbackState,
                        onClick = { lyricTuneVisible = !lyricTuneVisible },
                        onLongClick = {
                            if (playbackState.currentTrack != null) {
                                // 长按瞬间定格播放位置，作为歌词编辑的目标行依据。
                                // 取整曲绝对时间（见 lyricPositionMs）：歌词行的时间戳是绝对时间，
                                // 心动模式下 controller 回报的是片段内进度，直接用会定位到错误的行
                                onLongPress(playbackState.lyricPositionMs)
                            }
                        },
                        fontSize = homePortraitLayout.fontSizeSp.sp,
                        visibleLines = homePortraitLayout.visibleLines,
                        lineSpacing = homePortraitLayout.lineSpacingDp.dp,
                        alignment = homePortraitLayout.alignment,
                        contentColor = Color.White,
                        onVerticalFling = onVerticalFling,
                        onVerticalDragClaim = onVerticalDragClaim,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.home_player_empty),
                        fontSize = 14.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            // 歌词微调：左-延后歌词，右+提前歌词，每次微调一个步长
            if (lyricTuneVisible && playbackState.currentTrack?.lyricLines?.isNotEmpty() == true) {
                IconButton(
                    onClick = {
                        playbackState.adjustLyricsOffset(LyricFineTuneStepMs)
                        tuneVersion++
                        tuneHintText = "+${LyricFineTuneStepMs}ms"
                        tuneHintVersion++
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .size(34.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape),
                ) {
                    Icon(
                        imageVector = AppIcons.Remove,
                        contentDescription = stringResource(R.string.home_player_lyric_delay),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = {
                        playbackState.adjustLyricsOffset(-LyricFineTuneStepMs)
                        tuneVersion++
                        tuneHintText = "-${LyricFineTuneStepMs}ms"
                        tuneHintVersion++
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(34.dp)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape),
                ) {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = stringResource(R.string.home_player_lyric_advance),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 浮动提示：居中显示微调的毫秒数
            if (tuneHintText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = tuneHintText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
            menuContent()
        }
        // 歌词与歌曲信息间距（下限）
        Spacer(Modifier.height(8.dp))
    }
}
