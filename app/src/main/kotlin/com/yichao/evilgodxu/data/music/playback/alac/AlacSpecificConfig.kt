package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 的编解码参数，对应 magic cookie 里的 `ALACSpecificConfig`。
 *
 * 这是解码所需的全部容器外信息：每一帧都自带预测系数与自适应 Golomb 参数，帧与帧之间不共享状态，
 * 故除本配置与逐帧数据之外无第三处输入。
 *
 * 解析失败一律返回 null 而不以默认值顶替：不认识的 cookie 就是「本实现不认识这一条流」，
 * 上层据此放弃接管该轨道，交由媒体3 的其它渲染器处置（本机无 ALAC 解码器时表现为静音跳过），
 * 而不是按猜测的参数解出一段错误的音频。
 */
internal class AlacSpecificConfig(
    /** 每帧样本数；除末帧外每帧都解出这个数 */
    val frameLength: Int,
    /** 源位深，只取 16/20/24/32——参考实现的输出分支只覆盖这四个 */
    val bitDepth: Int,
    /** 自适应 Golomb 的均值比例、初始均值与均值上限所用的移位量 */
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
        /** `ALACSpecificConfig` 的固定长度 */
        private const val SIZE_BYTES = 24

        // 配置可能被包在 'frma' / 'alac' 原子之后（历史编码器所为），每个原子头 12 字节
        private const val ATOM_HEADER_BYTES = 12
        private const val ATOM_TAG_OFFSET = 4
        private const val FRMA_ATOM = "frma"
        private const val ALAC_ATOM = "alac"

        /** 参考实现声明的最高兼容版本，更高的版本不作兼容承诺 */
        private const val SUPPORTED_VERSION = 0

        /** 参考实现的输出分支只覆盖这四个位深，其余值会走到「什么都不写」的分支 */
        private val SUPPORTED_BIT_DEPTHS = setOf(16, 20, 24, 32)
        private const val MAX_CHANNELS = 8

        /** `kb` 会作为 `1 shl kb` 的移位量使用，0 与 32 及以上都会让该表达式失去意义 */
        private const val MIN_KB = 1
        private const val MAX_KB = 31

        /**
         * 解析 magic cookie；含本实现不支持的内容时返回 null。
         *
         * 各字段的取值域按「参考实现的算术能否成立」而不是「文档怎么写」来卡：位深决定输出分支，
         * 声道数决定混音缓冲的维数，`kb` 决定均值上限的移位量，任一越界都会让解码走进未定义路径。
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
