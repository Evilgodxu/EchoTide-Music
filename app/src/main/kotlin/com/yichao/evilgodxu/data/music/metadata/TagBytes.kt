package com.yichao.evilgodxu.data.music.metadata

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

// 容器标签读写共用的小端/大端字节原语：各格式实现按需取用，避免每处各写一份
internal fun int32(b: ByteArray, p: Int): Int = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int

internal fun long64(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long

internal fun writeInt32(b: ByteArray, p: Int, value: Int) {
    ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).putInt(value)
}

internal fun writeLong64(b: ByteArray, p: Int, value: Long) {
    ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
}

internal fun intLE(b: ByteArray, p: Int): Int = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int

internal fun longLE(b: ByteArray, p: Int): Long = ByteBuffer.wrap(b, p, 8).order(ByteOrder.LITTLE_ENDIAN).long

internal fun intBytes(v: Int): ByteArray =
    byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

internal fun intBytesLE(v: Int): ByteArray =
    byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

internal fun longBytesLE(v: Long): ByteArray =
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()

internal fun ByteArray.startsWith(value: String): Boolean =
    size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value
