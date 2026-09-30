package com.yichao.evilgodxu.screens.metadata.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 封面预览的最大边长：居中大图，同时限制其在宽屏上不至于撑满整页
private val COVER_MAX_WIDTH = 280.dp
// 预览占内容区宽度的比例：窄屏下按此收缩，宽屏下由 COVER_MAX_WIDTH 封顶
private const val COVER_WIDTH_FRACTION = 0.66f

/**
 * 内嵌封面编辑区：居中方形大图，点击后弹出操作菜单（选择图片 / 移除封面）。
 *
 * 预览由内嵌封面字节直接解码：此处要展示的正是「音频文件里当前存的是哪张图」，
 * 与显示端按系统略缩图取图的路径不同，不复用封面加载器。
 *
 * 两项操作收进菜单而非并列成按钮：它们都不是高频动作，常驻按钮会在首屏占去一行高度，
 * 而封面本身才是这一区唯一需要看清的内容。
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
            .padding(vertical = 16.dp),
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
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.metadata_cover_hint),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMenu) {
        CoverActionDialog(
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

// 封面操作菜单：两项操作纵向排列，点击遮罩或返回键收起
@Composable
private fun CoverActionDialog(
    canRemove: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(vertical = 10.dp),
        ) {
            CoverActionItem(
                icon = AppIcons.Image,
                label = stringResource(R.string.metadata_cover_pick),
                enabled = true,
                onClick = onPick,
            )
            CoverActionItem(
                icon = AppIcons.Delete,
                label = stringResource(R.string.metadata_cover_remove),
                enabled = canRemove,
                onClick = onRemove,
            )
        }
    }
}

// 菜单项：图标与文案横排，整行可点；不可用时同步降低图标与文字的不透明度
@Composable
private fun CoverActionItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Text(text = label, color = contentColor, fontSize = 14.sp)
    }
}
