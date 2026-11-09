package com.yichao.evilgodxu.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 待更新信息的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
internal val UPDATE_STORE = PersistedData("update", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class UpdateArchive(
    val lastCheckDay: String? = null,
    val pendingVersion: String? = null,
    val pendingUrl: String? = null,
    val pendingChangelog: String = "",
    val pendingSha256: String = "",
    val ignoredVersion: String? = null,
)

private val Context.updateDataStore: DataStore<UpdateArchive> by dataStore(
    fileName = "${UPDATE_STORE.name}.json",
    serializer = JsonSerializer(UpdateArchive.serializer(), UpdateArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("UpdateStore", "更新信息数据损坏，已重置", throwable)
        UpdateArchive()
    },
)

/** 尚未处理的待更新信息。 */
data class PendingUpdate(
    val version: String,
    val url: String,
    val changelog: String,
    val sha256: String,
)

/**
 * 更新检查与待更新信息的持久化。
 *
 * 检查日、待更新提示与忽略版本同属一份原子状态：一次检查要么整组写入，要么全不写，
 * 不会留下「已记为今天检查过、却没有待更新信息」的中间态。
 */
class UpdateStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val archive = appContext.updateDataStore.data
        .catchPersistenceIo("UpdateStore") { UpdateArchive() }

    // 预热读盘：首次进入界面时检查日与待更新信息已可用
    init {
        scope.launch { archive.first() }
    }

    suspend fun shouldCheckUpdate(today: String): Boolean =
        archive.first().lastCheckDay != today

    suspend fun ignoredVersion(): String? =
        archive.first().ignoredVersion

    suspend fun readPending(): PendingUpdate? {
        val value = archive.first()
        val version = value.pendingVersion?.trim().orEmpty()
        val url = value.pendingUrl?.trim().orEmpty()
        if (version.isBlank() || url.isBlank()) return null
        return PendingUpdate(
            version = version,
            url = url,
            changelog = value.pendingChangelog,
            sha256 = value.pendingSha256,
        )
    }

    /** 记录本次检查日；[pending] 为空表示本次没有可提供的更新。 */
    suspend fun recordCheck(today: String, pending: PendingUpdate?) {
        appContext.updateDataStore.updateData { archive ->
            archive.copy(
                lastCheckDay = today,
                pendingVersion = pending?.version,
                pendingUrl = pending?.url,
                pendingChangelog = pending?.changelog.orEmpty(),
                pendingSha256 = pending?.sha256.orEmpty(),
            )
        }
    }

    suspend fun clearPending() {
        appContext.updateDataStore.updateData { archive ->
            archive.copy(
                pendingVersion = null,
                pendingUrl = null,
                pendingChangelog = "",
                pendingSha256 = "",
            )
        }
    }

    suspend fun ignore(version: String) {
        appContext.updateDataStore.updateData { archive ->
            archive.copy(
                ignoredVersion = version,
                pendingVersion = null,
                pendingUrl = null,
                pendingChangelog = "",
                pendingSha256 = "",
            )
        }
    }
}
