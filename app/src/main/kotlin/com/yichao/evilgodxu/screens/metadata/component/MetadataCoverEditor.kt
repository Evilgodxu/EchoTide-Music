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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 封面预览边长
private val COVER_PREVIEW_SIZE = 96.dp

/**
 * 内嵌封面编辑区：预览 + 「选择图片」/「移除封面」。
 *
 * 预览由内嵌封面字节直接解码：此处要展示的正是「音频文件里当前存的是哪张图」，
 * 与显示端按系统略缩图取图的路径不同，不复用封面加载器。
 */
@Composable
internal fun MetadataCoverEditor(
    coverBytes: ByteArray?,
    coverPresent: Boolean,
    enabled: Boolean,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
) {
    // 解码结果随字节数组引用变化重算：编辑器同一曲目内不会原地改写字节
    val bitmap = remember(coverBytes) {
        coverBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(COVER_PREVIEW_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .clickable(enabled = enabled, onClick = onPickCover),
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
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetadataCoverAction(
                icon = AppIcons.Image,
                text = stringResource(R.string.metadata_cover_pick),
                enabled = enabled,
                onClick = onPickCover,
            )
            MetadataCoverAction(
                icon = AppIcons.Delete,
                text = stringResource(R.string.metadata_cover_remove),
                // 文件内本就没有内嵌封面时无可移除
                enabled = enabled && coverPresent,
                onClick = onRemoveCover,
            )
        }
    }
}

// 封面操作按钮：图标 + 文案的描边小按钮，与表单字段同列排列
@Composable
private fun MetadataCoverAction(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.08f else 0.04f),
        onClick = { if (enabled) onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
            Text(text = text, fontSize = 13.sp, color = contentColor)
        }
    }
}
