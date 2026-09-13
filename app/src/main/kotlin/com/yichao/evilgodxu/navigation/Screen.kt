package com.yichao.evilgodxu.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// 首页路由键
@Serializable
data object Home : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object Typography : NavKey

@Serializable
data object Cache : NavKey
