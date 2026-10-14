package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val miniPlayerEnabledKey = booleanPreferencesKey("mini_player_enabled")
private val wordByWordRenderingKey = booleanPreferencesKey("word_by_word_rendering")
private val swipeToChangeTrackKey = booleanPreferencesKey("swipe_to_change_track")
private val backgroundFlowEnabledKey = booleanPreferencesKey("background_flow_enabled")
// 键名沿用 usb_exclusive_mode：已落盘的持久化键，改名会让既有用户的该开关复位
private val usbDirectOutputModeKey = booleanPreferencesKey("usb_exclusive_mode")

// 迷你模式默认关闭
fun Context.miniPlayerEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[miniPlayerEnabledKey] ?: false }

suspend fun Context.saveMiniPlayerEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[miniPlayerEnabledKey] = enabled }
}

// 逐字渲染默认开启：关闭后歌词退化为整行高亮
fun Context.wordByWordRenderingFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[wordByWordRenderingKey] ?: true }

suspend fun Context.saveWordByWordRendering(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[wordByWordRenderingKey] = enabled }
}

// 滑动切歌默认开启：关闭后首页上下滑动不再切换歌曲
fun Context.swipeToChangeTrackFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[swipeToChangeTrackKey] ?: true }

suspend fun Context.saveSwipeToChangeTrack(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[swipeToChangeTrackKey] = enabled }
}

// 背景流动默认关闭：关闭时首页背景只渲染一帧静态的封面衍生背景，开启后按固定默认值缓慢流动
fun Context.backgroundFlowEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[backgroundFlowEnabledKey] ?: false }

suspend fun Context.saveBackgroundFlowEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[backgroundFlowEnabledKey] = enabled }
}

// USB 直出默认关闭：开启后按设备声明的条目为播放申请止步于源格式的专用输出流，由播放服务读取生效
fun Context.usbDirectOutputModeFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[usbDirectOutputModeKey] ?: false }

// USB 独占默认关闭：开启后接管 USB 音频接口自行写出，期间该解码器从系统音频中消失
private val usbExclusiveOutputModeKey = booleanPreferencesKey("usb_raw_exclusive_mode")

fun Context.usbExclusiveOutputModeFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[usbExclusiveOutputModeKey] ?: false }

/** 两路互斥的 USB 输出。命名对应各自的机制，而非开关在界面上的位置。 */
internal enum class UsbOutputKind {
    /** 平台音频路径：申请按源格式打开的专用输出流 */
    FORMAT_LOCKED,

    /** 接管 USB 接口自行写出 */
    EXCLUSIVE,
    ;

    fun other(): UsbOutputKind = if (this == FORMAT_LOCKED) EXCLUSIVE else FORMAT_LOCKED
}

/**
 * [kind] 被设为 [enabled] 后，两个开关各自应写入的取值。
 *
 * 互斥只落在"开启"一侧：两路同时开启在设备层面不成立——USB 音频接口同一时刻只能有一个持有者，
 * 而独占的成立条件正是内核驱动被解绑，平台路径随之不存在。故开启一路必然关闭另一路。
 *
 * 关闭一侧不牵动另一路：关掉一个本就关着的开关不应波及另一路，否则用户关掉 A 会顺带关掉正在用的 B。
 */
internal fun usbOutputSwitchesAfter(
    kind: UsbOutputKind,
    enabled: Boolean,
): Map<UsbOutputKind, Boolean> =
    if (enabled) mapOf(kind to true, kind.other() to false) else mapOf(kind to false)

suspend fun Context.saveUsbDirectOutputMode(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { applyUsbOutputSwitch(it, UsbOutputKind.FORMAT_LOCKED, enabled) }
}

suspend fun Context.saveUsbExclusiveOutputMode(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { applyUsbOutputSwitch(it, UsbOutputKind.EXCLUSIVE, enabled) }
}

/**
 * 两个键在同一次编辑里落盘，互斥因此是原子的。
 *
 * 拆成两次编辑会留下"两路同时为开"的中间态，而播放服务与设置页各自独立订阅这两个流，
 * 中间态一旦被读到，服务会先去接管接口又立刻回退——独占的接管代价（解绑内核驱动）不该被这样白付一次。
 */
private fun applyUsbOutputSwitch(
    preferences: MutablePreferences,
    kind: UsbOutputKind,
    enabled: Boolean,
) {
    usbOutputSwitchesAfter(kind, enabled).forEach { (target, value) ->
        when (target) {
            UsbOutputKind.FORMAT_LOCKED -> preferences[usbDirectOutputModeKey] = value
            UsbOutputKind.EXCLUSIVE -> preferences[usbExclusiveOutputModeKey] = value
        }
    }
}
