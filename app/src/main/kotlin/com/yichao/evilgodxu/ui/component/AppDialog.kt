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
import com.yichao.evilgodxu.ui.icons.AppIcons

// 非列表对话框内容区的最大高度：超出后在卡片内滚动，避免长内容把对话框撑满屏幕
private val DIALOG_CONTENT_MAX_HEIGHT = 320.dp
// 列表类对话框内容区占屏幕高度的比例：列表过长时在该高度内滚动，保证对话框高度上限稳定
internal const val DIALOG_LIST_HEIGHT_FRACTION = 0.36f
// 标题栏高度与图标按钮尺寸：保证带按钮时标题区依然紧凑，不产生额外留白
private val DIALOG_HEADER_HEIGHT = 32.dp
private val DIALOG_HEADER_ICON_SIZE = 32.dp
private val DIALOG_PADDING = 16.dp

// 统一对话框：卡片样式与其余确认框一致（DialogCard + 居中标题 + 内边距 16dp，高度随内容撑开）。
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
    // 传入时内容区按屏幕高度占比取固定高度（列表类对话框用），否则高度由内容撑开
    contentHeightFraction: Float? = null,
    // 标题对齐方式：默认居中，标题区右侧带文字按钮时左对齐更美观
    titleAlignment: TextAlign = TextAlign.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    // 窗口高度取实际容器尺寸而非 Configuration 的屏幕高：多窗口/自由窗口下容器小于屏幕，
    // 按屏幕高比例定高会超出可用空间
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val fixedContentHeight = contentHeightFraction?.let { windowHeight * it }
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DIALOG_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppDialogHeader(
                title = title,
                onBack = onBack,
                onClose = onClose,
                trailing = trailing,
                titleAlignment = titleAlignment,
            )
            Spacer(Modifier.height(8.dp))
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

// 标题栏：标题默认居中；返回、关闭与尾部操作仅在传入时渲染，未传入的按钮不占位
@Composable
private fun AppDialogHeader(
    title: String,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    titleAlignment: TextAlign,
) {
    // 两侧为图标按钮预留的宽度：居中标题必须左右对称留白，单侧留白会把文字整体挤偏；
    // 左对齐标题则按各自实际按钮占位，避免无谓的左侧缩进
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

// 对话框选项行：文本居中，带图标时改为图标 + 左对齐文案。
// 选中态只以背景高亮 + 加粗表示，与切换歌单列表项同色（primary 10% 底），不使用勾选图标
@Composable
internal fun DialogOption(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    // 加载态：文案转为透明并在原位置显示进度圈，行高不变，避免加载指示另起一行把对话框撑高
    loading: Boolean = false,
    // 紧凑尺寸：纵向留白减半，用于确认框内高度受限的附加选项；选中样式与常规尺寸一致
    compact: Boolean = false,
) {
    // 选项底色统一抬升到 surfaceContainerHigh：对话框卡片取 surface，选项若同色会在深色下与卡片融为一片，
    // 抬升一档后浅色更灰、深色更亮，两个主题都能拉开层次（与展开式选择器菜单容器同色阶）
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    // 选中高亮叠在抬升后的底色之上：直接以半透明 primary 覆盖卡片，其混色结果与抬升后的未选中底色几乎一致
    val selectionTint =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
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
        // 文案与加载指示叠在同一层：加载时文案透明化但保留占位，进度圈落在文案原位，行高与常规态一致
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
