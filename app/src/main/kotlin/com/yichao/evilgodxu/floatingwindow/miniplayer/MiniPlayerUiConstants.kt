package com.yichao.evilgodxu.floatingwindow.miniplayer

// 迷你条紧凑布局常量（窗口宽度与 Compose 布局共用，调整尺寸时需同步）
internal const val MINI_COVER_DP = 32
internal const val MINI_BUTTON_DP = 32
internal const val MINI_PADDING_H_DP = 2
internal const val MINI_BUTTON_COUNT = 5
// 下滑隐藏播放器的垂直滑动阈值
internal const val MINI_SWIPE_VERTICAL_THRESHOLD_DP = 56

// 展开播放列表时窗口高度占屏幕最窄边的比例：横竖屏取同一物理边长，
// 展开后的列表在两种朝向下大小相当，不会随朝向忽高忽低
internal const val MINI_PLAYLIST_HEIGHT_FRACTION = 0.9f
// 展开/收起的高度动画时长
internal const val MINI_PLAYLIST_ANIM_MS = 260L

// 展开后的播放列表行：封面、歌名与歌手在窄列内的尺寸
internal const val MINI_PLAYLIST_ART_DP = 28
internal const val MINI_PLAYLIST_ROW_PADDING_H_DP = 4
internal const val MINI_PLAYLIST_ROW_PADDING_V_DP = 3