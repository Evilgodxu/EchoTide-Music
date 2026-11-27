package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 应用自持音量接到会话：独占期间音量键归应用
 * 按音量类型切远端/本地；档位同自持音量
 */
@OptIn(UnstableApi::class)
internal class ExclusiveVolumePlayer(
    player: Player,
    private val volume: ExclusiveVolume,
    /** 档位变化出口，供落盘；读盘恢复不经此处 */
    private val onStepChanged: (Int) -> Unit = {},
) : ForwardingSimpleBasePlayer(player) {

    private var remote = false

    /** 接管进出：接管期间报远端，归还报本地；须主线程 */
    fun setRemote(value: Boolean) {
        if (remote == value) return
        remote = value
        invalidateState()
    }

    /** 读盘恢复档位，不触发落盘回调 */
    fun restoreStep(step: Int) {
        if (volume.setStep(step)) invalidateState()
    }

    /** 界面音量手势入口，与音量键同路；须主线程 */
    fun setStepFromUi(step: Int) {
        applyStep(step)
    }

    override fun getState(): SimpleBasePlayer.State {
        val base = super.getState()
        val builder = base.buildUpon().setIsDeviceMuted(volume.isMuted)
        if (!remote) return builder.build()
        return builder
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(base.availableCommands)
                    .addAll(DEVICE_VOLUME_COMMANDS)
                    .build(),
            )
            // 上限即档位上限，一格 0.5dB
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(MIN_DEVICE_VOLUME)
                    .setMaxVolume(EXCLUSIVE_VOLUME_STEPS)
                    .build(),
            )
            .setDeviceVolume(volume.currentStep)
            .build()
    }

    // 就地施加，不转发给被包装播放器（会落到平台媒体流）
    override fun handleSetDeviceVolume(volume: Int, flags: Int): ListenableFuture<*> = applyStep(volume)

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        applyStep(this.volume.currentStep + 1)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        applyStep(this.volume.currentStep - 1)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (this.volume.setMuted(muted)) invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun applyStep(value: Int): ListenableFuture<*> {
        if (this.volume.setStep(value)) {
            onStepChanged(this.volume.currentStep)
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MIN_DEVICE_VOLUME = 0

        /** 设备音量命令集：设置与调节都给，音量键与滑杆才各走各的 */
        val DEVICE_VOLUME_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()
    }
}
