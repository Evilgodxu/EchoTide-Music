package com.yichao.evilgodxu.data.persistence

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// 忽略未知字段并写入默认值，兼容增删字段
internal val persistenceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 类型化 DataStore 的 JSON 序列化器 */
internal class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    defaultValue: T,
) : Serializer<T> {

    override val defaultValue: T = defaultValue

    override suspend fun readFrom(input: InputStream): T {
        val text = input.readBytes().decodeToString()
        return try {
            persistenceJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw CorruptionException("无法解析持久化数据", e)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(persistenceJson.encodeToString(serializer, t).encodeToByteArray())
    }
}

/** IO 失败时回落到默认值并留痕，避免状态流中断 */
internal fun <T> Flow<T>.catchPersistenceIo(tag: String, fallback: () -> T): Flow<T> =
    catch { throwable ->
        if (throwable is IOException) {
            CrashLogManager.logException(tag, "读取持久化数据失败", throwable)
            emit(fallback())
        } else {
            throw throwable
        }
    }
