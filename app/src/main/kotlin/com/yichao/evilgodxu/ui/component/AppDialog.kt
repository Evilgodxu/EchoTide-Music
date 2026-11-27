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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.theme.AppAlpha
import com.yichao.evilgodxu.theme.AppShape
import com.yichao.evilgodxu.theme.AppSpacing
import com.yichao.evilgodxu.ui.icons.AppIcons

// 内容区最大高度 320dp，超出滚动
private val DIALOG_CONTENT_MAX_HEIGHT = 320.dp
// 列表类对话框高度占屏幕 36%
internal const val DIALOG_LIST_HEIGHT_FRACTION = 0.36f
// 标题栏与图标按钮 32dp，保持紧凑
private val DIALOG_HEADER_HEIGHT = 32.dp
private val DIALOG_HEADER_ICON_SIZE = 32.dp

// 统一对话框：卡片 + 居中标题，按钮均为可选
@Composable
internal fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    // 列表自带滚动时置 false
    scrollable: Boolean = true,
    // 按屏幕高度占比定高
    contentHeightFraction: Float? = null,
    // 标题对齐，带文字按钮时左对齐
    titleAlignment: TextAlign = TextAlign.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    // 用容器尺寸而非屏幕高，兼容多窗口
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val fixedContentHeight = contentHeightFraction?.let { windowHeight * it }
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.Dialog),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppDialogHeader(
                title = title,
                onBack = onBack,
                onClose = onClose,
                trailing = trailing,
                titleAlignment = titleAlignment,
            )
            Spacer(Modifier.height(AppSpacing.Gap))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            fixedContentHeight != null && scrollable ->
                                Modifier.height(fixedContentHeight).verticalScroll(scrollState)
                            fixedContentHeight != null -> Modifier.height(fixedContentHeight)
                            scrollable ->
                                Modifier.heightIn(max = DIALOG_CONTENT_MAX_HEIGHT).verticalScroll(scrollState)
                            else -> Modifier
                        }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
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

// 标题栏：按钮仅在传入时渲染
@Composable
private fun AppDialogHeader(
    title: String,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    titleAlignment: TextAlign,
) {
    // 居中标题两侧对称留白，左对齐按实际按钮占位
    val startReserve = if (onBack != null) DIALOG_HEADER_ICON_SIZE else 0.dp
    val endReserve = if (trailing != null || onClose != null) DIALOG_HEADER_ICON_SIZE else 0.dp
    val centered = titleAlignment == TextAlign.Center
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
            textAlign = titleAlignment,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (centered) maxOf(startReserve, endReserve) else startReserve,
                    end = if (centered) maxOf(startReserve, endReserve) else endReserve,
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

// 紧凑圆形点击区，避免撑高标题区
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

// 选项行：选中只以底色高亮与加粗表示
@Composable
internal fun DialogOption(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    // 加载态：文案透明，原位显示进度圈
    loading: Boolean = false,
    // 紧凑尺寸：纵向留白减半
    compact: Boolean = false,
) {
    // 选项抬升色阶，与卡片拉开层次
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    // 选中高亮叠在抬升底色上
    val selectionTint =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = AppAlpha.Selected) else Color.Transparent
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.Disabled)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(AppShape.Option)
            .background(background)
            .background(selectionTint)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = if (icon != null) 12.dp else 0.dp,
                vertical = if (compact) 4.dp else 14.dp,
            ),
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
        // 文案与进度圈叠放，行高不变
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (icon != null) Alignment.CenterStart else Alignment.Center,
        ) {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = if (icon != null) TextAlign.Start else TextAlign.Center,
                color = if (loading) Color.Transparent else contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
