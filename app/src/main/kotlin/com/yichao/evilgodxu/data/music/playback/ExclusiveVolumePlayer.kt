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
 * 把应用自持的音量接到媒体会话上：独占成立期间音量键归应用，系统媒体音量不被改动。
 *
 * 机制不是自造。媒体会话把音量分成两种——**本地音量**由系统改媒体流档位（音量键的默认行为），
 * **远端音量**则把音量键交给应用：系统只把调节方向或目标值送进应用注册的音量提供者，一个流档位都不碰。
 * 媒体3 没有公开"设为远端音量"的接口，但会话在**播放器自报设备音量**时会自行切换：播放器的
 * `DeviceInfo.playbackType` 报远端即装上提供者，且该判定在运行期反复读取，故能随路由进出随时切换。
 * 本类就是那个自报设备音量的播放器，设备音量的档位即 [ExclusiveVolume] 的档位。
 *
 * 只在独占承接写出期间报远端：退回平台路径时音量正该由系统掌管（那条链路的音量就是系统音量），
 * 报本地即把音量键交还系统，与独占之前的行为一致。
 *
 * 包装的播放器仍由会话原样使用，本类只替换设备音量这一项状态与它的三个写入入口。
 */
@OptIn(UnstableApi::class)
internal class ExclusiveVolumePlayer(
    player: Player,
    private val volume: ExclusiveVolume,
    /** 档位变化的出口：供落盘。只在用户按键改动时触发，读盘恢复不经此处 */
    private val onStepChanged: (Int) -> Unit = {},
) : ForwardingSimpleBasePlayer(player) {

    private var remote = false

    /**
     * 路由进出：独占期间报远端（音量键归应用），退回平台路径报本地（交还系统）。
     *
     * 必须在播放器所属线程（主线程）上调用：状态刷新与会话播报都发生在那条线上。
     */
    fun setRemote(value: Boolean) {
        if (remote == value) return
        remote = value
        invalidateState()
    }

    /** 读盘恢复的档位：写回后让会话播报新档位；不经 [onStepChanged]，避免刚读回来又写回去 */
    fun restoreStep(step: Int) {
        if (volume.setStep(step)) invalidateState()
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
            // 上限即档位上限：系统音量面板按它画滑杆，一格正好是 0.5 dB
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(MIN_DEVICE_VOLUME)
                    .setMaxVolume(EXCLUSIVE_VOLUME_STEPS)
                    .build(),
            )
            .setDeviceVolume(volume.currentStep)
            .build()
    }

    // 设备音量就地施加，一律不转发给被包装的播放器：转发即落到平台媒体流档位上，
    // 那正是"应用自持音量"要避开的事
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

        /**
         * 会话据此判定"应用可写设备音量"，并由这组命令定出音量提供者的控制类型。
         *
         * 一律取带标志位的现行命令（不带标志位的那两个已弃用）：只报调节类会退化成只能按方向调
         * （面板滑杆失效），只报设置类则音量键的方向调根本不成立——两者都不报，音量键根本到不了本类。
         * 设置类与调节类都给上，音量键与滑杆才各走各的入口。
         */
        val DEVICE_VOLUME_COMMANDS: Player.Commands = Player.Commands.Builder()
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()
    }
}
