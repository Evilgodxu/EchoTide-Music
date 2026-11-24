package com.yichao.evilgodxu.navigation

import androidx.compose.runtime.compositionLocalOf
import java.io.Serializable

// 播放页是否为最上层。播放页常驻底层，被覆盖层压住时页内的返回处理须全部让出，
// 否则它们的 BackHandler 注册得比根容器晚，会先一步吃掉返回键
val LocalIsTopLayer = compositionLocalOf { true }

// 应用内的页面身份。播放页是常驻底层，其余三个是叠在它上面的覆盖层：
// 只有同一时刻的一个覆盖层，关闭即退场，所以不需要栈，也没有嵌套。
// 实现 Serializable 仅为让覆盖层在进程被回收后仍能随 rememberSaveable 还原
sealed interface AppDestination : Serializable

data object Home : AppDestination

data object Settings : AppDestination

// 频谱分析页：只带曲目标识，曲目信息由页面自行解析。
// 同一曲目重复进入应复用同一层级，故以曲目标识参与相等性
data class Spectrum(val trackId: Long) : AppDestination

// 元数据编辑页：同样只带曲目标识，标签内容由页面从音频文件读取
data class Metadata(val trackId: Long) : AppDestination
