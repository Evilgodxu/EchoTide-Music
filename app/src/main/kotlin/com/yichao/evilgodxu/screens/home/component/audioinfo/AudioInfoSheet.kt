package com.yichao.evilgodxu.screens.home.component.audioinfo

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
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
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.player.AudioInfoContent
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 音频信息下拉收起的累计距离阈值：内容已到顶部后继续下拉超过该距离即收起
private val DISMISS_OVERSCROLL_DP = 64.dp

// 保存图片四周留白：内容行自带圆角底色，留白让成品边缘不贴死
private val IMAGE_PADDING_DP = 16.dp

private val IMAGE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

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
    // 内容截图图层：由内容区在绘制时录入全部信息行，保存时直接取用
    val captureLayer = rememberGraphicsLayer()
    var saving by remember { mutableStateOf(false) }
    val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
    val paddingPx = with(LocalDensity.current) { IMAGE_PADDING_DP.roundToPx() }
    val savedText = stringResource(R.string.audio_info_image_saved)
    val saveFailedText = stringResource(R.string.audio_info_image_save_failed)
    // 竖屏面板半高，横屏铺满：与播放列表弹窗一致
    val sheetHeightFraction = if (rememberWindowLandscape()) 1f else 0.5f

    // 保存整块信息内容：图层已由内容区录入，此处只负责合成底色与写相册；录屏与取图都在主线程
    fun saveImage() {
        if (saving) return
        saving = true
        scope.launch {
            val success = runCatching {
                saveAudioInfoImage(context, captureLayer, surfaceArgb, paddingPx)
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
                    playbackState = playbackState,
                    scrollState = scrollState,
                    captureLayer = captureLayer,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(dismissNestedScroll),
                )
            }
        }
    }
}

/**
 * 把内容图层的整幅像素合成到不透明底色上并写入相册。
 *
 * 图层自带透明底，直接编码会在部分查看器里显示为透明或黑底，故先按面板底色铺底再按留白偏移贴入；
 * 像素读取须在图层仍处于活动状态的主线程完成，合成与编码放到后台。
 */
private suspend fun saveAudioInfoImage(
    context: Context,
    layer: GraphicsLayer,
    backgroundArgb: Int,
    paddingPx: Int,
): Boolean {
    val width = layer.size.width
    val height = layer.size.height
    if (width <= 0 || height <= 0) return false

    val pixels = IntArray(width * height)
    layer.toImageBitmap().readPixels(pixels, 0, 0, width, height, 0, width)
    val png = withContext(Dispatchers.Default) {
        val outputWidth = width + paddingPx * 2
        val outputHeight = height + paddingPx * 2
        // 先整体铺底色，再逐行把内容像素混合到留白偏移处：图层里的圆角与文字边缘带半透明像素，
        // 直接覆盖会把底色打回透明，必须按 alpha 合成
        val output = IntArray(outputWidth * outputHeight) { backgroundArgb }
        for (row in 0 until height) {
            val sourceStart = row * width
            val destinationStart = (row + paddingPx) * outputWidth + paddingPx
            for (column in 0 until width) {
                output[destinationStart + column] =
                    blendOver(pixels[sourceStart + column], backgroundArgb)
            }
        }
        val bitmap = Bitmap.createBitmap(output, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        try {
            ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
    val name = "音频信息-${LocalDateTime.now().format(IMAGE_TIME_FORMAT)}.png"
    return withContext(Dispatchers.IO) {
        AlbumImageStore.write(context, name, "image/png", png)
    }
}

// 源像素按 alpha 叠到不透明底色上，结果统一为不透明像素；readPixels 取到的是非预乘 ARGB
private fun blendOver(source: Int, background: Int): Int {
    val alpha = source ushr 24
    if (alpha == 0xFF) return source
    if (alpha == 0) return background
    val inverse = 0xFF - alpha
    val red = (((source shr 16) and 0xFF) * alpha + ((background shr 16) and 0xFF) * inverse) / 0xFF
    val green = (((source shr 8) and 0xFF) * alpha + ((background shr 8) and 0xFF) * inverse) / 0xFF
    val blue = ((source and 0xFF) * alpha + (background and 0xFF) * inverse) / 0xFF
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
