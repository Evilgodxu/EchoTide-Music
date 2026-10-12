package com.yichao.evilgodxu.floatingwindow.miniplayer

// 迷你条紧凑布局常量（窗口宽度与 Compose 布局共用，调整尺寸时需同步）
internal const val MINI_COVER_DP = 32
internal const val MINI_BUTTON_DP = 32
internal const val MINI_PADDING_H_DP = 2
internal const val MINI_BUTTON_COUNT = 5
// 下滑隐藏播放器的垂直滑动阈值
internal const val MINI_SWIPE_VERTICAL_THRESHOLD_DP = 56

// 展开/收起的高度动画时长
internal const val MINI_PLAYLIST_ANIM_MS = 260L

// 展开后的播放列表行：封面、歌名与歌手在窄列内的尺寸
internal const val MINI_PLAYLIST_ART_DP = 28
internal const val MINI_PLAYLIST_ROW_PADDING_H_DP = 4
internal const val MINI_PLAYLIST_ROW_PADDING_V_DP = 3

// 行高由封面与上下内边距固定：文本在此高度内垂直居中，行高不随文本行数浮动
internal const val MINI_PLAYLIST_ROW_HEIGHT_DP = MINI_PLAYLIST_ART_DP + MINI_PLAYLIST_ROW_PADDING_V_DP * 2
// 行间距与列表上下留白，与行的固定高度一起决定窗口展开高度
internal const val MINI_PLAYLIST_ROW_SPACING_DP = 2
internal const val MINI_PLAYLIST_CONTENT_PADDING_V_DP = 6
// 展开后恰好完整露出的曲目行数；窗口高度按整行数推算，不随屏幕最短边变化
internal const val MINI_PLAYLIST_VISIBLE_ROWS = 5

// 列表顶边渐隐带宽度：列表视口紧贴控制栏，行在顶边前按此长度淡出，滚动到顶部的行自控制栏下方渐隐没入
internal const val MINI_PLAYLIST_TOP_FADE_DP = 10