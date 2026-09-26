package com.yichao.evilgodxu.data.music.api

/**
 * QQ 音乐 QRC 歌词解密：三段式分组密码 + zlib 压缩。
 *
 * 该算法的结构（IP 置换 / 16 轮 Feistel / 8 个 S 盒 / P 盒 / 两个 28 位半密钥）与 DES 同源，
 * 但 S 盒、P 盒、E 盒与密钥调度表均为平台私有取值，标准库的 DES/3DES 解不出结果，
 * 故按其等价逻辑内联于此 —— 仅供 QRC 歌词解密，不可用于任何实际安全用途。
 */
internal object QrcCipher {

    private const val BLOCK_SIZE = 8
    private const val BITS_28_MASK = 0xFFFFFFF0L

    // 三段密钥；解密按 D(K3) -> E(K2) -> D(K1) 执行
    private val KEY_1 = "!@#)(*$%".toByteArray(Charsets.US_ASCII)
    private val KEY_2 = "123ZXC!@".toByteArray(Charsets.US_ASCII)
    private val KEY_3 = "!@#)(NHL".toByteArray(Charsets.US_ASCII)

    /** 解密十六进制密文并 zlib 解压，返回明文；数据非法时返回 null */
    fun decrypt(hexData: String): String? {
        val encrypted = decodeHex(hexData) ?: return null
        if (encrypted.isEmpty() || encrypted.size % BLOCK_SIZE != 0) return null
        val decrypted = ByteArray(encrypted.size)
        // 三段密码的中间结果需落在独立缓冲，否则会覆盖下一段的输入
        val scratch = ByteArray(BLOCK_SIZE)
        var offset = 0
        while (offset < encrypted.size) {
            desCrypt(encrypted, offset, scratch, 0, DECRYPT_SCHEDULES[0])
            desCrypt(scratch, 0, decrypted, offset, DECRYPT_SCHEDULES[1])
            // 第二段的结果写在目标缓冲上，第三段直接就地覆盖
            desCrypt(decrypted, offset, scratch, 0, DECRYPT_SCHEDULES[2])
            System.arraycopy(scratch, 0, decrypted, offset, BLOCK_SIZE)
            offset += BLOCK_SIZE
        }
        val plain = inflateBytes(decrypted) ?: return null
        val hasBom = plain.size >= 3 &&
            plain[0] == 0xEF.toByte() && plain[1] == 0xBB.toByte() && plain[2] == 0xBF.toByte()
        val start = if (hasBom) 3 else 0
        return String(plain, start, plain.size - start, Charsets.UTF_8)
    }

    // 密钥调度：由 8 字节密钥派生 16 组 48 位轮密钥，每组按高/低 24 位各存一个 int
    private fun keySchedule(key: ByteArray, decrypt: Boolean): IntArray {
        val schedule = IntArray(32)
        var c = permuteFromKeyBytes(key, KEY_PERM_C) shl 4
        var d = permuteFromKeyBytes(key, KEY_PERM_D) shl 4
        for (round in 0 until 16) {
            c = rotateLeft28Bit(c, KEY_RND_SHIFT[round])
            d = rotateLeft28Bit(d, KEY_RND_SHIFT[round])
            var subKey = 0L
            for (index in KEY_COMPRESSION.indices) {
                val position = KEY_COMPRESSION[index]
                val bit = if (position < 28) {
                    (c ushr (31 - position)) and 1L
                } else {
                    (d ushr (31 - (position - 27))) and 1L
                }
                if (bit == 1L) subKey = subKey or (1L shl (47 - index))
            }
            // 解密时轮密钥反序使用
            val target = if (decrypt) 15 - round else round
            val high24 = ((subKey ushr 40) and 0xFF).toInt() shl 16 or
                (((subKey ushr 32) and 0xFF).toInt() shl 8) or
                ((subKey ushr 24) and 0xFF).toInt()
            val low24 = ((subKey ushr 16) and 0xFF).toInt() shl 16 or
                (((subKey ushr 8) and 0xFF).toInt() shl 8) or
                (subKey and 0xFF).toInt()
            schedule[target * 2] = high24
            schedule[target * 2 + 1] = low24
        }
        return schedule
    }

    /**
     * 按置换表从 8 字节密钥取位。密钥被视作两个小端 32 位字拼接，
     * 故第 0 位实际取 key[3] 的最高位，第 31 位取 key[0] 的最低位。
     */
    private fun permuteFromKeyBytes(key: ByteArray, table: IntArray): Long {
        var output = 0L
        var mask = 1L shl (table.size - 1)
        for (position in table) {
            val wordIndex = position shr 5
            val bitInWord = position and 31
            val byteInWord = bitInWord shr 3
            val bitInByte = bitInWord and 7
            val value = key[wordIndex * 4 + 3 - byteInWord].toInt() and 0xFF
            if (((value ushr (7 - bitInByte)) and 1) == 1) output = output or mask
            mask = mask shr 1
        }
        return output
    }

    private fun rotateLeft28Bit(value: Long, amount: Int): Long {
        val masked = value and BITS_28_MASK
        return ((masked shl amount) or (masked ushr (28 - amount))) and BITS_28_MASK
    }

    private fun desCrypt(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int, schedule: IntArray) {
        var left = 0
        var right = 0
        for (index in 0 until 8) {
            val tableIndex = (index shl 8) or (input[inOffset + index].toInt() and 0xFF)
            left = left or IP_LEFT_TABLE[tableIndex]
            right = right or IP_RIGHT_TABLE[tableIndex]
        }
        for (round in 0 until 15) {
            val swapped = right
            right = left xor fFunction(right, schedule[round * 2], schedule[round * 2 + 1])
            left = swapped
        }
        left = left xor fFunction(right, schedule[30], schedule[31])

        var outLeft = 0
        var outRight = 0
        for (index in 0 until 4) {
            val leftIndex = (index shl 8) or ((left ushr (24 - index * 8)) and 0xFF)
            outLeft = outLeft or INV_IP_LEFT_TABLE[leftIndex]
            outRight = outRight or INV_IP_RIGHT_TABLE[leftIndex]
            val rightIndex = ((index + 4) shl 8) or ((right ushr (24 - index * 8)) and 0xFF)
            outLeft = outLeft or INV_IP_LEFT_TABLE[rightIndex]
            outRight = outRight or INV_IP_RIGHT_TABLE[rightIndex]
        }
        output[outOffset] = (outLeft ushr 24).toByte()
        output[outOffset + 1] = (outLeft ushr 16).toByte()
        output[outOffset + 2] = (outLeft ushr 8).toByte()
        output[outOffset + 3] = outLeft.toByte()
        output[outOffset + 4] = (outRight ushr 24).toByte()
        output[outOffset + 5] = (outRight ushr 16).toByte()
        output[outOffset + 6] = (outRight ushr 8).toByte()
        output[outOffset + 7] = outRight.toByte()
    }

    // Feistel 轮函数：E 盒扩展 -> 与轮密钥异或 -> S 盒代换与 P 盒置换（查预生成的合并表）
    private fun fFunction(state: Int, keyHigh24: Int, keyLow24: Int): Int {
        val byte0 = (state ushr 24) and 0xFF
        val byte1 = (state ushr 16) and 0xFF
        val byte2 = (state ushr 8) and 0xFF
        val byte3 = state and 0xFF
        val expandedHigh = EBOX_HIGH_TABLE[byte0] or EBOX_HIGH_TABLE[256 or byte1] or
            EBOX_HIGH_TABLE[512 or byte2] or EBOX_HIGH_TABLE[768 or byte3]
        val expandedLow = EBOX_LOW_TABLE[byte0] or EBOX_LOW_TABLE[256 or byte1] or
            EBOX_LOW_TABLE[512 or byte2] or EBOX_LOW_TABLE[768 or byte3]
        val mixedHigh = expandedHigh xor keyHigh24
        val mixedLow = expandedLow xor keyLow24
        return SP_TABLE[(mixedHigh ushr 18) and 63] or
            SP_TABLE[64 or ((mixedHigh ushr 12) and 63)] or
            SP_TABLE[128 or ((mixedHigh ushr 6) and 63)] or
            SP_TABLE[192 or (mixedHigh and 63)] or
            SP_TABLE[256 or ((mixedLow ushr 18) and 63)] or
            SP_TABLE[320 or ((mixedLow ushr 12) and 63)] or
            SP_TABLE[384 or ((mixedLow ushr 6) and 63)] or
            SP_TABLE[448 or (mixedLow and 63)]
    }

    private fun decodeHex(text: String): ByteArray? {
        val hex = text.trim()
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun applyPermutation(input: Long, rule: IntArray): Long {
        var output = 0L
        for (index in 0 until 64) {
            if (((input ushr (64 - rule[index])) and 1L) == 1L) output = output or (1L shl (63 - index))
        }
        return output
    }

    private fun applyPBox(input: Int): Int {
        var output = 0
        for (index in 0 until 32) {
            if ((input and (1 shl (32 - P_BOX[index]))) != 0) output = output or (1 shl (31 - index))
        }
        return output
    }

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )

    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )

    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private val IP_RULE = intArrayOf(
        34, 42, 50, 58, 2, 10, 18, 26, 36, 44, 52, 60, 4, 12, 20, 28,
        38, 46, 54, 62, 6, 14, 22, 30, 40, 48, 56, 64, 8, 16, 24, 32,
        33, 41, 49, 57, 1, 9, 17, 25, 35, 43, 51, 59, 3, 11, 19, 27,
        37, 45, 53, 61, 5, 13, 21, 29, 39, 47, 55, 63, 7, 15, 23, 31,
    )

    private val INV_IP_RULE = intArrayOf(
        37, 5, 45, 13, 53, 21, 61, 29, 38, 6, 46, 14, 54, 22, 62, 30,
        39, 7, 47, 15, 55, 23, 63, 31, 40, 8, 48, 16, 56, 24, 64, 32,
        33, 1, 41, 9, 49, 17, 57, 25, 34, 2, 42, 10, 50, 18, 58, 26,
        35, 3, 43, 11, 51, 19, 59, 27, 36, 4, 44, 12, 52, 20, 60, 28,
    )

    private val P_BOX = intArrayOf(
        16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
        2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25,
    )

    private val E_BOX_TABLE = intArrayOf(
        32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
        12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
        22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1,
    )

    private val S_BOXES = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )

    private val IP_LEFT_TABLE = IntArray(2048)
    private val IP_RIGHT_TABLE = IntArray(2048)
    private val INV_IP_LEFT_TABLE = IntArray(2048)
    private val INV_IP_RIGHT_TABLE = IntArray(2048)
    private val SP_TABLE = IntArray(512)
    private val EBOX_HIGH_TABLE = IntArray(1024)
    private val EBOX_LOW_TABLE = IntArray(1024)

    init {
        // IP 置换拆成左右两半查表，按（字节位置, 字节值）索引
        for (bytePosition in 0 until 8) {
            for (byteValue in 0 until 256) {
                val index = (bytePosition shl 8) or byteValue
                val source = byteValue.toLong() shl (56 - bytePosition * 8)
                val permuted = applyPermutation(source, IP_RULE)
                IP_LEFT_TABLE[index] = ((permuted ushr 32) and 0xFFFFFFFFL).toInt()
                IP_RIGHT_TABLE[index] = (permuted and 0xFFFFFFFFL).toInt()
                val inverse = applyPermutation(source, INV_IP_RULE)
                INV_IP_LEFT_TABLE[index] = ((inverse ushr 32) and 0xFFFFFFFFL).toInt()
                INV_IP_RIGHT_TABLE[index] = (inverse and 0xFFFFFFFFL).toInt()
            }
        }
        // S 盒代换与 P 盒置换合并成一张 8×64 的表
        for (boxIndex in 0 until 8) {
            for (boxInput in 0 until 64) {
                val value = (boxInput and 32) or ((boxInput and 31) shr 1) or ((boxInput and 1) shl 4)
                val substituted = S_BOXES[boxIndex][value] shl (28 - boxIndex * 4)
                SP_TABLE[(boxIndex shl 6) or boxInput] = applyPBox(substituted)
            }
        }
        // E 盒扩展按 4 段 8 位切片建表，高/低 24 位分开存储
        for (chunkIndex in 0 until 4) {
            val shift = (3 - chunkIndex) * 8
            for (byteValue in 0 until 256) {
                val input = byteValue shl shift
                var expandedHigh = 0
                var expandedLow = 0
                for (bit in 0 until 24) {
                    if (((input ushr (32 - E_BOX_TABLE[bit])) and 1) != 0) {
                        expandedHigh = expandedHigh or (1 shl (23 - bit))
                    }
                }
                for (bit in 24 until 48) {
                    if (((input ushr (32 - E_BOX_TABLE[bit])) and 1) != 0) {
                        expandedLow = expandedLow or (1 shl (47 - bit))
                    }
                }
                val index = (chunkIndex shl 8) or byteValue
                EBOX_HIGH_TABLE[index] = expandedHigh
                EBOX_LOW_TABLE[index] = expandedLow
            }
        }
    }

    // 声明在所有密钥表之后：轮密钥依赖密钥调度表，必须等其初始化完成
    private val DECRYPT_SCHEDULES: Array<IntArray> = arrayOf(
        keySchedule(KEY_3, decrypt = true),
        keySchedule(KEY_2, decrypt = false),
        keySchedule(KEY_1, decrypt = true),
    )
}
