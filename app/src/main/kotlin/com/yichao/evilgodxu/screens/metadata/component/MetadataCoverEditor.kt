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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption
import com.yichao.evilgodxu.ui.icons.AppIcons

// 封面卡片高度：与参考实现一致，给背景大图与前景信息行留出同一档高度
private val COVER_CARD_HEIGHT = 208.dp
// 封面缩略图边长：卡片内的方形展示尺寸
private val COVER_THUMB_SIZE = 148.dp

// 角标底色：压在图上，固定深色以保证在任意封面上都可见
private val BADGE_BACKGROUND = Color.Black.copy(alpha = 0.6f)
// 角标文字：固定白色，与深色角标底色配套
private val BADGE_TEXT = Color.White

/**
 * 内嵌封面编辑区：整幅封面卡片（压暗背景 + 封面缩略图 + 标题/艺术家），点击后弹出操作对话框。
 *
 * 预览由内嵌封面字节直接解码：此处要展示的正是「音频文件里当前存的是哪张图」，
 * 与显示端按系统略缩图取图的路径不同，不复用封面加载器。
 *
 * 信息与角标布局对齐参考实现：缩略图上标出「封面」「尺寸」与操作提示，改动后角标变成撤销入口，
 * 使「当前存的是哪张图」「是否改过」「能对它做什么」在同一屏内可读。
 *
 * 操作入口用对话框而非贴边浮层：选项有六项且文案较长，浮层在窄屏上会被迫横向挤压或折行
 */
@Composable
internal fun MetadataCoverEditor(
    title: String,
    artist: String,
    coverBytes: ByteArray?,
    coverDirty: Boolean,
    enabled: Boolean,
    exporting: Boolean,
    onPickCover: () -> Unit,
    onSaveCover: () -> Unit,
    onPickSameAlbum: () -> Unit,
    onOnlineRefresh: () -> Unit,
    onRemoveCover: () -> Unit,
    onRevertCover: () -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    // 解码结果与像素尺寸随字节数组引用变化重算：编辑器同一曲目内不会原地改写字节
    val bitmap = remember(coverBytes) {
        coverBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    val sizeText = remember(coverBytes) { coverPixelSize(coverBytes) }
    val titleText = title.ifBlank { stringResource(R.string.metadata_field_empty) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .height(COVER_CARD_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (bitmap != null) {
            // 背景大图压暗后叠一层向下渐深的蒙层：前景文字与角标始终压在足够暗的底上
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                            ),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverThumbnail(
                bitmap = bitmap,
                coverDirty = coverDirty,
                sizeText = sizeText,
                enabled = enabled,
                onClick = { showActions = true },
                onRevertClick = onRevertCover,
            )
            Column(
                modifier = Modifier.weight(1f).padding(start = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = titleText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Text(
                        text = artist,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        CoverActionDialog(
            visible = showActions,
            hasCover = coverBytes != null,
            coverDirty = coverDirty,
            exporting = exporting,
            onPick = { showActions = false; onPickCover() },
            onSave = { showActions = false; onSaveCover() },
            onSameAlbum = { showActions = false; onPickSameAlbum() },
            onOnlineRefresh = { showActions = false; onOnlineRefresh() },
            onRemove = { showActions = false; onRemoveCover() },
            onRevert = { showActions = false; onRevertCover() },
            onDismiss = { showActions = false },
        )
    }
}

/**
 * 封面操作对话框：本页封面可做的六项操作，按使用频度与破坏性排序。
 *
 * 置灰规则按「当前是否做得了」定：
 *  - 保存与移除需要有封面；
 *  - 撤销需要有尚未回退的改动 —— 它回退的是进入本页时的封面，改动一旦落盘也照样能回退。
 */
@Composable
private fun CoverActionDialog(
    visible: Boolean,
    hasCover: Boolean,
    coverDirty: Boolean,
    exporting: Boolean,
    onPick: () -> Unit,
    onSave: () -> Unit,
    onSameAlbum: () -> Unit,
    onOnlineRefresh: () -> Unit,
    onRemove: () -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.metadata_cover_actions),
    ) {
        DialogOption(
            icon = AppIcons.Refresh,
            label = stringResource(R.string.metadata_cover_online),
            enabled = !exporting,
            onClick = onOnlineRefresh,
        )
        DialogOption(
            icon = AppIcons.Image,
            label = stringResource(R.string.metadata_cover_pick),
            enabled = !exporting,
            onClick = onPick,
        )
        DialogOption(
            icon = AppIcons.Album,
            label = stringResource(R.string.metadata_cover_same_album),
            enabled = !exporting,
            onClick = onSameAlbum,
        )
        DialogOption(
            icon = AppIcons.Share,
            label = stringResource(R.string.metadata_cover_save),
            enabled = hasCover && !exporting,
            loading = exporting,
            onClick = onSave,
        )
        DialogOption(
            icon = AppIcons.Delete,
            label = stringResource(R.string.metadata_cover_remove),
            enabled = hasCover && !exporting,
            onClick = onRemove,
        )
        DialogOption(
            icon = AppIcons.History,
            label = stringResource(R.string.metadata_cover_revert),
            enabled = coverDirty && !exporting,
            onClick = onRevert,
        )
    }
}

// 封面缩略图：方形封面 + 角标（左上「封面」、左下尺寸、右下「点击更换」、右上「撤销更改」）
@Composable
private fun CoverThumbnail(
    bitmap: ImageBitmap?,
    coverDirty: Boolean,
    sizeText: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    onRevertClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(COVER_THUMB_SIZE)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick),
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
        CoverBadge(
            text = stringResource(R.string.metadata_cover_label),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        )
        sizeText?.let {
            CoverBadge(
                text = it,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }
        CoverBadge(
            text = stringResource(R.string.metadata_cover_edit_hint),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        )
        if (coverDirty) {
            // 改动后角标即撤销入口：点它回到进入本页时的那张封面
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = onRevertClick,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.metadata_cover_revert),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// 图上角标：深色底 + 白字，字号取最小值以尽量不遮挡封面
@Composable
private fun CoverBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(BADGE_BACKGROUND, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = BADGE_TEXT, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// 封面像素尺寸：只读图片头，不解码整图
private fun coverPixelSize(bytes: ByteArray?): String? {
    if (bytes == null || bytes.isEmpty()) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return "${options.outWidth}×${options.outHeight}"
}

// 同专辑封面选择项：方形缩略图，点击即选中该封面
@Composable
internal fun SameAlbumCoverItem(
    bytes: ByteArray,
    onClick: () -> Unit,
) {
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    }
    Box(
        modifier = Modifier
            .width(84.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
