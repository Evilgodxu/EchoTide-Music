package com.yichao.evilgodxu.floatingwindow.miniplayer

// 迷你条尺寸：窗口与 Compose 共用，须同步
internal const val MINI_COVER_DP = 32
internal const val MINI_BUTTON_DP = 32
internal const val MINI_PADDING_H_DP = 2
internal const val MINI_BUTTON_COUNT = 5
// 下滑隐藏播放器的垂直滑动阈值
internal const val MINI_SWIPE_VERTICAL_THRESHOLD_DP = 56

// 展开/收起的高度动画时长
internal const val MINI_PLAYLIST_ANIM_MS = 260L

// 列表行内元素尺寸
internal const val MINI_PLAYLIST_ART_DP = 28
internal const val MINI_PLAYLIST_ROW_PADDING_H_DP = 4
internal const val MINI_PLAYLIST_ROW_PADDING_V_DP = 3

// 行高由封面与上下内边距固定
internal const val MINI_PLAYLIST_ROW_HEIGHT_DP = MINI_PLAYLIST_ART_DP + MINI_PLAYLIST_ROW_PADDING_V_DP * 2
// 行间距与上下留白，决定展开高度
internal const val MINI_PLAYLIST_ROW_SPACING_DP = 2
internal const val MINI_PLAYLIST_CONTENT_PADDING_V_DP = 6
// 展开后完整露出的行数
internal const val MINI_PLAYLIST_VISIBLE_ROWS = 5

// 顶边渐隐带宽度
internal const val MINI_PLAYLIST_TOP_FADE_DP = 10