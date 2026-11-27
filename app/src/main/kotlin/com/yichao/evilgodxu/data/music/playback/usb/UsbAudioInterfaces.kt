package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

/**
 * 挑出独占要接管的接口。
 *
 * 控制接口 + 带等时 OUT 的流接口，缺一即半接管。
 * 须按接口号去重，每个备用设置都被当独立接口。
 */
internal object UsbAudioInterfaces {

    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_CONTROL = 0x01
    private const val SUBCLASS_STREAMING = 0x02

    /** 是否暴露 USB 音频播放能力 */
    fun isUsbAudioOutput(device: UsbDevice): Boolean =
        playbackInterfaces(device).any { it.interfaceSubclass == SUBCLASS_STREAMING }

    /** 需接管的接口，按接口号去重 */
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
