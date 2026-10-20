package com.yichao.evilgodxu.data.music.playback.usb

/**
 * ALSA 拼声卡名用的前缀。
 *
 * 音频栈的 `AudioDeviceInfo.productName` 取的是 ALSA 声卡名（形如「USB-Audio - KT02H20 Hifi Audio」），
 * 而 USB 描述符里的 iProduct 不带这一段——两处要认成同一台设备，先得把这层机器前缀剥掉。
 */
internal const val USB_AUDIO_CARD_PREFIX = "USB-Audio - "

/**
 * 去掉 ALSA 前缀与首尾空白的声卡名。
 *
 * 前缀按忽略大小写剥：写法由各厂 ROM 的 audio HAL 决定，本应用不该假定它是固定的一种大小写。
 */
internal fun usbCardNameOf(name: String): String {
    val trimmed = name.trim()
    val body = if (trimmed.startsWith(USB_AUDIO_CARD_PREFIX, ignoreCase = true)) {
        trimmed.substring(USB_AUDIO_CARD_PREFIX.length)
    } else {
        trimmed
    }
    return body.trim()
}

/**
 * 音频栈上报的设备名与 USB 描述符里的产品名是否指同一台设备。
 *
 * 两处名称来源不同：前者是 ALSA 声卡名，后者是设备描述符的 iProduct，故先归一再忽略大小写比较。
 * 只在归一后完全相等时才算命中：按包含关系匹配会让「K7」与「K7 Pro」互相认错，读成另一台设备的
 * 描述符比读不到更糟——那会把别人的能力当成当前设备的。
 */
internal fun usbProductNameMatches(audioStackName: String?, usbProductName: String?): Boolean {
    val left = audioStackName?.takeIf { it.isNotBlank() }?.let(::usbCardNameOf) ?: return false
    val right = usbProductName?.takeIf { it.isNotBlank() }?.let(::usbCardNameOf) ?: return false
    return left.equals(right, ignoreCase = true)
}
