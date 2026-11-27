package com.yichao.evilgodxu.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 界面尺度令牌：形状、间距、透明度与动效统一取值
// 圆角按容器层级分档
object AppShape {
    /** 卡片与对话框容器 */
    val Container = RoundedCornerShape(20.dp)

    /** 浮层内的可点选项行 */
    val Option = RoundedCornerShape(12.dp)

    /** 页面内联的输入框、标签、紧凑按钮 */
    val Control = RoundedCornerShape(10.dp)

    /** 封面、缩略图等媒体块 */
    val Media = RoundedCornerShape(8.dp)

    /** 极小标记，如迷你播放器里的小封面 */
    val Micro = RoundedCornerShape(6.dp)
}

// 间距以 4dp 为基准；6/10/14 仅行内微调
object AppSpacing {
    /** 页面左右内边距 */
    val PageHorizontal = 16.dp

    /** 分区标题的上方留白 */
    val SectionTop = 20.dp

    /** 分区标题与卡片之间的间距 */
    val SectionBottom = 10.dp

    /** 卡片内列表行的左右内边距 */
    val RowHorizontal = 16.dp

    /** 卡片内列表行的上下内边距 */
    val RowVertical = 12.dp

    /** 对话框内边距 */
    val Dialog = 16.dp

    /** 相邻元素的标准间隔 */
    val Gap = 8.dp
}

// 透明度只允许这几档
object AppAlpha {
    /** 次要文字与图标 */
    const val Secondary = 0.6f

    /** 禁用态 */
    const val Disabled = 0.4f

    /** 选中态叠加底色（配 primary） */
    const val Selected = 0.10f

    /** 极轻填充：输入框、次要按钮、错误提示 */
    const val Subtle = 0.08f

    /** 浮层卡片不透明度，保对比度 */
    const val FloatingSurface = 0.96f
}

// 动效时长按四档取值
object AppMotion {
    /** 按压、勾选一类即时反馈 */
    const val Micro = 120

    /** 小元素淡入淡出 */
    const val Small = 150

    /** 中等区块的出现与让位 */
    const val Medium = 200

    /** 标准转场：整宽位移需更长时间 */
    const val Standard = 350
}
