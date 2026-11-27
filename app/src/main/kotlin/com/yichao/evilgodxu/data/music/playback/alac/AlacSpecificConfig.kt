package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 编解码参数，来自 magic cookie。
 * 每帧自带预测与 Golomb 参数，帧间不共享状态。
 * 解析失败返回 null，上层据此放弃接管。
 */
internal class AlacSpecificConfig(
    /** 每帧样本数；除末帧外每帧都解出这个数 */
    val frameLength: Int,
    /** 源位深，只取 16/20/24/32 */
    val bitDepth: Int,
    /** Golomb 的均值比例、初始均值与上限移位量 */
    val pb: Int,
    val mb: Int,
    val kb: Int,
    /** 声道数，1..8 */
    val channelCount: Int,
    /** 单帧字节数上限，用于预置输入缓冲 */
    val maxFrameBytes: Int,
    val sampleRate: Int,
) {
    companion object {
        /** ALACSpecificConfig 固定长度 */
        private const val SIZE_BYTES = 24

        // 可包在 frma/alac 后，原子头 12 字节
        private const val ATOM_HEADER_BYTES = 12
        private const val ATOM_TAG_OFFSET = 4
        private const val FRMA_ATOM = "frma"
        private const val ALAC_ATOM = "alac"

        /** 最高兼容版本，更高版本不承诺 */
        private const val SUPPORTED_VERSION = 0

        /** 支持的位深，其余值无输出 */
        private val SUPPORTED_BIT_DEPTHS = setOf(16, 20, 24, 32)
        private const val MAX_CHANNELS = 8

        /** kb 是 1 shl kb 的移位量，0 与 ≥32 无意义 */
        private const val MIN_KB = 1
        private const val MAX_KB = 31

        // 单帧样本总量上限，防止小配置申请巨型数组
        private const val MAX_SAMPLES_PER_FRAME = 1 shl 20

        /**
         * 解析 cookie，不支持返回 null。
         * 字段按算术能否成立来卡，越界即不支持。
         */
        fun parse(cookie: ByteArray): AlacSpecificConfig? {
            var offset = 0
            if (cookie.hasAtomTag(offset, FRMA_ATOM)) offset += ATOM_HEADER_BYTES
            if (cookie.hasAtomTag(offset, ALAC_ATOM)) offset += ATOM_HEADER_BYTES
            if (cookie.size - offset < SIZE_BYTES) return null

            val frameLength = cookie.readIntAt(offset)
            val compatibleVersion = cookie[offset + 4].toInt() and 0xFF
            val bitDepth = cookie[offset + 5].toInt() and 0xFF
            val pb = cookie[offset + 6].toInt() and 0xFF
            val mb = cookie[offset + 7].toInt() and 0xFF
            val kb = cookie[offset + 8].toInt() and 0xFF
            val channelCount = cookie[offset + 9].toInt() and 0xFF
            val maxFrameBytes = cookie.readIntAt(offset + 12)
            val sampleRate = cookie.readIntAt(offset + 20)

            val valid = compatibleVersion <= SUPPORTED_VERSION &&
                frameLength >= 1 &&
                frameLength.toLong() * channelCount <= MAX_SAMPLES_PER_FRAME.toLong() &&
                bitDepth in SUPPORTED_BIT_DEPTHS &&
                channelCount in 1..MAX_CHANNELS &&
                kb in MIN_KB..MAX_KB &&
                maxFrameBytes > 0 &&
                sampleRate > 0
            if (!valid) return null

            return AlacSpecificConfig(
                frameLength = frameLength,
                bitDepth = bitDepth,
                pb = pb,
                mb = mb,
                kb = kb,
                channelCount = channelCount,
                maxFrameBytes = maxFrameBytes,
                sampleRate = sampleRate,
            )
        }

        private fun ByteArray.hasAtomTag(offset: Int, tag: String): Boolean {
            if (offset + ATOM_TAG_OFFSET + tag.length > size) return false
            return tag.indices.all {
                this[offset + ATOM_TAG_OFFSET + it] == tag[it].code.toByte()
            }
        }

        // cookie 内的整数字段一律大端
        private fun ByteArray.readIntAt(offset: Int): Int =
            ((this[offset].toInt() and 0xFF) shl 24) or
                ((this[offset + 1].toInt() and 0xFF) shl 16) or
                ((this[offset + 2].toInt() and 0xFF) shl 8) or
                (this[offset + 3].toInt() and 0xFF)
    }
}
