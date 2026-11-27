package com.yichao.evilgodxu.data.music.playback.usb

/**
 * ALSA 拼卡名前缀，比对前须剥掉。
 */
internal const val USB_AUDIO_CARD_PREFIX = "USB-Audio - "

/**
 * 去掉前缀与首尾空白的卡名。
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
 * 两处名称是否指同一台设备。
 * 归一后忽略大小写，只认完全相等。
 */
internal fun usbProductNameMatches(audioStackName: String?, usbProductName: String?): Boolean {
    val left = audioStackName?.takeIf { it.isNotBlank() }?.let(::usbCardNameOf) ?: return false
    val right = usbProductName?.takeIf { it.isNotBlank() }?.let(::usbCardNameOf) ?: return false
    return left.equals(right, ignoreCase = true)
}
