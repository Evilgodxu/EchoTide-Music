package com.yichao.evilgodxu.screens.metadata.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider

// 封面预览的最大边长：居中大图，同时限制其在宽屏上不至于撑满整页
private val COVER_MAX_WIDTH = 280.dp
// 预览占内容区宽度的比例：窄屏下按此收缩，宽屏下由 COVER_MAX_WIDTH 封顶
private const val COVER_WIDTH_FRACTION = 0.66f

/**
 * 内嵌封面编辑区：居中方形大图，点击后在其下方弹出操作菜单（选择图片 / 移除封面）。
 *
 * 预览由内嵌封面字节直接解码：此处要展示的正是「音频文件里当前存的是哪张图」，
 * 与显示端按系统略缩图取图的路径不同，不复用封面加载器。
 *
 * 封面不套分区卡片：它是本页唯一需要看清的内容，卡片背景与内边距只会压缩它的可用面积。
 *
 * 两项操作收进菜单而非并列成按钮：它们都不是高频动作，常驻按钮会在首屏占去一行高度。
 */
@Composable
internal fun MetadataCoverEditor(
    coverBytes: ByteArray?,
    coverPresent: Boolean,
    enabled: Boolean,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    // 解码结果随字节数组引用变化重算：编辑器同一曲目内不会原地改写字节
    val bitmap = remember(coverBytes) {
        coverBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = COVER_MAX_WIDTH)
                .fillMaxWidth(COVER_WIDTH_FRACTION)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .clickable(enabled = enabled) { showMenu = true },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.metadata_cover_title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.metadata_cover_empty),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showMenu) {
            CoverActionMenu(
                // 文件内本就没有内嵌封面时无可移除
                canRemove = coverPresent,
                onPick = {
                    showMenu = false
                    onPickCover()
                },
                onRemove = {
                    showMenu = false
                    onRemoveCover()
                },
                onDismiss = { showMenu = false },
            )
        }
    }
}

/**
 * 封面操作菜单：横向排列的纯文字项，紧贴封面下缘弹出。
 *
 * 与首页各处长按菜单同一套外观与定位 —— 同一应用内的浮层菜单只有一种样式，
 * 图标在此不承载额外信息，去掉后菜单更矮，也不与文字争宽度。
 */
@Composable
private fun CoverActionMenu(
    canRemove: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        onDismissRequest = onDismiss,
        popupPositionProvider = menuEdgePositionProvider(atTop = false),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CoverMenuItem(
                    text = stringResource(R.string.metadata_cover_pick),
                    onClick = onPick,
                )
                CoverMenuItem(
                    text = stringResource(R.string.metadata_cover_remove),
                    enabled = canRemove,
                    onClick = onRemove,
                )
            }
        }
    }
}

// 菜单项：文字居中，置灰项不可点击
@Composable
private fun CoverMenuItem(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = text,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
