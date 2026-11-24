package com.yichao.evilgodxu.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// 界面尺度令牌：把跨页面反复出现的形状、间距、透明度与动效时长收敛到一处。
// 新增界面从这些名字取值，不再各写一份字面量；档位与用途的完整说明见 docs/界面规范.md。
// 这里只收拢「有语义、跨页面复用」的值，行内微调（如 4dp 的图标与文字间距）仍就地取值。

// 圆角按容器层级分档，同一层级的容器只用同一档。
// 不复用 MaterialTheme.shapes 的 small/medium/large：它的四档粒度（4/8/12/16）与本项目的
// 层级（20/12/10/8/6）对不上，硬套会让「卡片」与「选项」落进同一个角色，反而丢失层次。
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

// 间距以 4dp 为基准。6 / 10 / 14 只用于行内视觉微调，不用于页面级布局。
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

// 透明度只允许这几档，避免每个文件各调一个「看起来差不多」的值。
object AppAlpha {
    /** 次要文字与图标 */
    const val Secondary = 0.6f

    /** 禁用态 */
    const val Disabled = 0.4f

    /** 选中态叠加在容器底色之上（与 primary 组合） */
    const val Selected = 0.10f

    /** 极轻填充：输入框底、次要按钮底、错误提示底 */
    const val Subtle = 0.08f

    /** 浮层卡片自身不透明度：透出一点底下的层次，同时保住文字对比度 */
    const val FloatingSurface = 0.96f
}

// 动效时长按「反馈 / 小元素 / 中等 / 标准转场」四档取，不写零散毫秒数。
object AppMotion {
    /** 按压、勾选一类即时反馈 */
    const val Micro = 120

    /** 小元素淡入淡出 */
    const val Small = 150

    /** 中等区块的出现与让位 */
    const val Medium = 200

    /** 页面内标准转场（默认档）：整宽位移需要更长时间才不显仓促 */
    const val Standard = 400
}
