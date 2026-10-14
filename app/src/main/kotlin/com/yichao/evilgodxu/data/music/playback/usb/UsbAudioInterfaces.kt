package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

/**
 * 从 Android 的设备模型里挑出独占直出要接管的接口。
 *
 * 接管范围是「AudioControl 接口 + 带等时 OUT 端点的 AudioStreaming 接口」，两者缺一不可：
 * 控制接口承载 Feature Unit 与时钟实体，是本应用下发音量与采样率的通道；流接口承载数据端点。
 * 只接管其中一个都会让设备处于半接管状态——内核驱动仍会参与另一部分，独占随之不成立。
 *
 * 必须按接口号去重：Android 把同一接口的每个备用设置都当作一个独立的 [UsbInterface] 暴露，
 * 逐个接管会在第二次调用时失败（同一接口已被自己持有）。
 */
internal object UsbAudioInterfaces {

    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_CONTROL = 0x01
    private const val SUBCLASS_STREAMING = 0x02

    /** 设备是否暴露了 USB 音频的播放能力，用于在枚举到的设备里筛选目标 */
    fun isUsbAudioOutput(device: UsbDevice): Boolean =
        playbackInterfaces(device).any { it.interfaceSubclass == SUBCLASS_STREAMING }

    /** 需要接管的接口，按接口号去重后的顺序即为设备声明顺序 */
    fun playbackInterfaces(device: UsbDevice): List<UsbInterface> {
        val audioInterfaces = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .filter { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }

        val streamingOutputIds = audioInterfaces
            .filter { it.interfaceSubclass == SUBCLASS_STREAMING }
            .filter { intf ->
                (0 until intf.endpointCount).any { index ->
                    val endpoint = intf.getEndpoint(index)
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT
                }
            }
            .map { it.id }
            .toSet()

        return audioInterfaces
            .filter {
                it.interfaceSubclass == SUBCLASS_CONTROL || it.id in streamingOutputIds
            }
            .distinctBy { it.id }
    }
}
