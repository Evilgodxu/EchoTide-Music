package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 对话框内容区最大高度：超出后在卡片内滚动，避免长列表把对话框撑满屏幕；列表类内容据此约束自身高度
internal val DIALOG_CONTENT_MAX_HEIGHT = 360.dp
// 标题栏高度与图标按钮尺寸：保证带按钮时标题区依然紧凑，不产生额外留白
private val DIALOG_HEADER_HEIGHT = 32.dp
private val DIALOG_HEADER_ICON_SIZE = 32.dp
private val DIALOG_PADDING = 16.dp

// 统一对话框：卡片样式与重置黑名单对话框一致（DialogCard + 居中标题 + 内边距 16dp，高度随内容撑开）。
// 返回、关闭、尾部操作与底部按钮均为可选参数，未传入即不渲染，不给不需要的对话框强加按钮。
@Composable
internal fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    // 列表类内容自带滚动时置 false，避免与外层滚动嵌套
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DIALOG_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppDialogHeader(title = title, onBack = onBack, onClose = onClose, trailing = trailing)
            Spacer(Modifier.height(8.dp))
            if (scrollable) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = DIALOG_CONTENT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            } else {
                content()
            }
            if (buttons != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.widthIn(max = 200.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = buttons,
                )
            }
        }
    }
}

// 标题栏：标题居中；返回、关闭与尾部操作仅在传入时渲染，未传入的按钮不占位
@Composable
private fun AppDialogHeader(
    title: String,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DIALOG_HEADER_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (onBack != null) DIALOG_HEADER_ICON_SIZE else 0.dp,
                    end = if (trailing != null || onClose != null) DIALOG_HEADER_ICON_SIZE else 0.dp,
                ),
        )
        if (onBack != null) {
            AppDialogHeaderIcon(
                icon = AppIcons.ArrowBack,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (trailing != null) trailing()
            if (onClose != null) {
                AppDialogHeaderIcon(
                    icon = AppIcons.Close,
                    contentDescription = stringResource(R.string.back),
                    onClick = onClose,
                )
            }
        }
    }
}

// 标题栏图标按钮：紧凑圆形点击区，避免 Material 图标按钮的固定触控高度撑高标题区
@Composable
private fun AppDialogHeaderIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(DIALOG_HEADER_ICON_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

// 对话框选项行：文本居中，带图标时改为图标 + 左对齐文案；选中态与禁用态在此统一着色
@Composable
internal fun DialogOption(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = when {
        selected && isDarkTheme -> MaterialTheme.colorScheme.primaryContainer
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        selected && isDarkTheme -> MaterialTheme.colorScheme.onPrimaryContainer
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (icon != null) 12.dp else 0.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            textAlign = if (icon != null) TextAlign.Start else TextAlign.Center,
            color = contentColor,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
