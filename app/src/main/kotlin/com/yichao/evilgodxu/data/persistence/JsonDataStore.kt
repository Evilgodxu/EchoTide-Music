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

// 结构化持久化的统一编解码口径：容错读取未知字段，写入始终带默认值，
// 使新增字段在旧文件上取默认、删除字段后旧值被忽略。
internal val persistenceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 以 kotlinx.serialization 承载 DataStore 的 [Serializer]。
 *
 * 结构化数据一律走类型化 DataStore，而不是把 JSON 文本塞进 Preferences：
 * 前者在读取时即完成校验与缺省回落，损坏文件由 DataStore 统一交给损坏处理器。
 */
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

/**
 * 读取侧兜底：磁盘 IO 失败时回落到内存默认值并留痕，而不是让收集协程中断。
 *
 * DataStore 的 `data` 流在底层读写异常时会把异常抛给收集方；不接住的话，
 * 对应存储的整个状态回流会静默停止，界面此后一直停留在最后一次成功读到的值。
 */
internal fun <T> Flow<T>.catchPersistenceIo(tag: String, fallback: () -> T): Flow<T> =
    catch { throwable ->
        if (throwable is IOException) {
            CrashLogManager.logException(tag, "读取持久化数据失败", throwable)
            emit(fallback())
        } else {
            throw throwable
        }
    }
