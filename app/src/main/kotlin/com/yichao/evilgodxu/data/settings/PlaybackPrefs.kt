package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// 播放设置的唯一事实源；界面与引擎共用这组键

private val miniPlayerEnabledKey = booleanPreferencesKey("mini_player_enabled")
private val wordByWordRenderingKey = booleanPreferencesKey("word_by_word_rendering")
private val swipeToChangeTrackKey = booleanPreferencesKey("swipe_to_change_track")
private val backgroundFlowEnabledKey = booleanPreferencesKey("background_flow_enabled")
// 键名沿用旧值，改名会让用户开关复位
private val usbDirectOutputModeKey = booleanPreferencesKey("usb_exclusive_mode")

fun Context.miniPlayerEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[miniPlayerEnabledKey] ?: false }

// 默认开启，关闭后退化为整行高亮
fun Context.wordByWordRenderingFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[wordByWordRenderingKey] ?: true }

// 默认开启，关闭后滑动不切歌
fun Context.swipeToChangeTrackFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[swipeToChangeTrackKey] ?: true }

// 默认关闭，开启后背景缓慢流动
fun Context.backgroundFlowEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[backgroundFlowEnabledKey] ?: false }

// 默认关闭，开启后申请源格式专用输出流
fun Context.usbDirectOutputModeFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[usbDirectOutputModeKey] ?: false }

// 默认关闭，开启后接管 USB 接口写出
private val usbExclusiveOutputModeKey = booleanPreferencesKey("usb_raw_exclusive_mode")

fun Context.usbExclusiveOutputModeFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[usbExclusiveOutputModeKey] ?: false }

// 音量 0..160，每级 0.5 dB，空即未写
private val exclusiveVolumeStepKey = intPreferencesKey("usb_exclusive_volume_step")

// 默认关闭，开启后按 ReplayGain 增益
private val volumeGainEnabledKey = booleanPreferencesKey("volume_gain_enabled")

fun Context.volumeGainEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[volumeGainEnabledKey] ?: false }

fun Context.exclusiveVolumeStepFlow(): Flow<Int?> =
    settingsDataStore.data.map { it[exclusiveVolumeStepKey] }

suspend fun Context.saveExclusiveVolumeStep(step: Int) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[exclusiveVolumeStepKey] = step }
}

/** 两路互斥的 USB 输出，按机制命名 */
internal enum class UsbOutputKind {
    /** 平台路径：申请源格式专用输出流 */
    FORMAT_LOCKED,

    /** 接管 USB 接口自行写出 */
    EXCLUSIVE,
    ;

    fun other(): UsbOutputKind = if (this == FORMAT_LOCKED) EXCLUSIVE else FORMAT_LOCKED
}

/**
 * kind 设为 enabled 后两个开关的取值
 * 仅开启时互斥，关闭不牵动另一路
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

/** 播放设置快照；默认值为键缺失时的取值 */
data class PlaybackSettings(
    val miniPlayerEnabled: Boolean = false,
    val wordByWordRendering: Boolean = true,
    val swipeToChangeTrack: Boolean = true,
    val backgroundFlow: Boolean = false,
    val usbDirectOutput: Boolean = false,
    val usbExclusiveOutput: Boolean = false,
    val volumeGain: Boolean = false,
)

// 默认值规则集中在此，界面与仓库共用
internal fun playbackSettingsOf(
    miniPlayerEnabled: Boolean?,
    wordByWordRendering: Boolean?,
    swipeToChangeTrack: Boolean?,
    backgroundFlow: Boolean?,
    usbDirectOutput: Boolean?,
    usbExclusiveOutput: Boolean?,
    volumeGain: Boolean?,
): PlaybackSettings = PlaybackSettings(
    miniPlayerEnabled = miniPlayerEnabled ?: false,
    wordByWordRendering = wordByWordRendering ?: true,
    swipeToChangeTrack = swipeToChangeTrack ?: true,
    backgroundFlow = backgroundFlow ?: false,
    usbDirectOutput = usbDirectOutput ?: false,
    usbExclusiveOutput = usbExclusiveOutput ?: false,
    volumeGain = volumeGain ?: false,
)

internal fun Preferences.toPlaybackSettings(): PlaybackSettings = playbackSettingsOf(
    miniPlayerEnabled = this[miniPlayerEnabledKey],
    wordByWordRendering = this[wordByWordRenderingKey],
    swipeToChangeTrack = this[swipeToChangeTrackKey],
    backgroundFlow = this[backgroundFlowEnabledKey],
    usbDirectOutput = this[usbDirectOutputModeKey],
    usbExclusiveOutput = this[usbExclusiveOutputModeKey],
    volumeGain = this[volumeGainEnabledKey],
)

// 整组同次落盘，未改项写回原值
internal fun MutablePreferences.applyPlaybackSettings(transform: (PlaybackSettings) -> PlaybackSettings) {
    val next = transform(toPlaybackSettings())
    this[miniPlayerEnabledKey] = next.miniPlayerEnabled
    this[wordByWordRenderingKey] = next.wordByWordRendering
    this[swipeToChangeTrackKey] = next.swipeToChangeTrack
    this[backgroundFlowEnabledKey] = next.backgroundFlow
    this[usbDirectOutputModeKey] = next.usbDirectOutput
    this[usbExclusiveOutputModeKey] = next.usbExclusiveOutput
    this[volumeGainEnabledKey] = next.volumeGain
}


/** 两个键同次编辑，互斥为原子；避免中间态误触发接管 */
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
