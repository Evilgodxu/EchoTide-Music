package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.trackIdentityKey
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 播放队列缓存的落盘落点：过滤结果、默认队列、浏览态与排序同属一份原子状态
internal val PLAYBACK_QUEUE_STORE = PersistedData("playback_queue", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class QueueArchive(
    val tracks: List<StoredTrack> = emptyList(),
    // 有过滤来源时才单独保存默认队列；无过滤时 tracks 即默认队列
    val defaultTracks: List<StoredTrack> = emptyList(),
    val sourceKey: String? = null,
    val sourceName: String = "",
    val followsQueue: Boolean = true,
    val viewedSourceKey: String? = null,
    val viewedSourceName: String = "",
    val sortField: String = PlaylistSortField.DEFAULT.name,
    val sortDescending: Boolean = false,
    val librarySignature: String? = null,
)

private val Context.playbackQueueDataStore: DataStore<QueueArchive> by dataStore(
    fileName = "${PLAYBACK_QUEUE_STORE.name}.json",
    serializer = JsonSerializer(QueueArchive.serializer(), QueueArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("PlaylistPersistence", "播放队列缓存损坏，已重置", throwable)
        QueueArchive()
    },
)

/**
 * 播放列表相关落盘：过滤结果、过滤来源、默认队列、浏览态、排序规则与曲库签名。
 *
 * 整份队列是一次原子提交：拆开写会留下「列表已更新、来源未更新」的不一致。
 * 写入合流到最新快照——界面连续触发时只保留最后一次，避免旧快照后发先至。
 */
internal class PlaylistPersistence(
    context: Context,
    scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val writeQueue = Channel<Snapshot>(Channel.CONFLATED)

    // 最近一次读到的原始档案：曲库签名与队列同文件，启动链路已整份读过一次，
    // 签名查询直接复用它，避免为了一个字段再解析一遍整份曲目列表
    @Volatile
    private var lastArchive: QueueArchive? = null

    data class Snapshot(
        val tracks: List<MusicTrack>,
        val source: PlaylistSource?,
        val defaultPlaylist: List<MusicTrack>,
        val followsQueue: Boolean,
        val viewedSource: PlaylistSource?,
        val sortField: PlaylistSortField,
        val sortDescending: Boolean,
    )

    data class StoredQueue(
        val tracks: List<MusicTrack>,
        val backup: List<MusicTrack>,
        val source: PlaylistSource?,
        val sortField: PlaylistSortField,
        val sortDescending: Boolean,
        val followsQueue: Boolean,
        val viewedSource: PlaylistSource?,
        val librarySignature: String?,
    )

    init {
        scope.launch {
            for (snapshot in writeQueue) {
                appContext.playbackQueueDataStore.updateData { archive -> archive.withSnapshot(snapshot) }
            }
        }
    }

    fun persist(snapshot: Snapshot) {
        writeQueue.trySend(snapshot)
    }

    // 历史缓存可能残留同文件不同 URI 形态的重复条目，按真实文件路径去重，避免冷启动直接展示重名歌曲
    suspend fun read(): StoredQueue {
        val archive = appContext.playbackQueueDataStore.data
            .catchPersistenceIo("PlaylistPersistence") { QueueArchive() }
            .first()
            .also { lastArchive = it }
        val tracks = archive.tracks.map { it.toMusicTrack(appContext) }
            .distinctBy { trackIdentityKey(appContext, it) }
        val backup = archive.defaultTracks.map { it.toMusicTrack(appContext) }
            .distinctBy { trackIdentityKey(appContext, it) }
        val sortField = runCatching { PlaylistSortField.valueOf(archive.sortField) }
            .getOrDefault(PlaylistSortField.DEFAULT)
        return StoredQueue(
            tracks = tracks,
            backup = backup,
            source = archive.sourceKey?.let { PlaylistSource(it, archive.sourceName) },
            sortField = sortField,
            sortDescending = archive.sortDescending,
            followsQueue = archive.followsQueue,
            viewedSource = archive.viewedSourceKey?.let { PlaylistSource(it, archive.viewedSourceName) },
            librarySignature = archive.librarySignature,
        )
    }

    /** 落盘本轮扫描所用的曲库签名，供下次冷启动比对。 */
    suspend fun writeLibrarySignature(signature: String) {
        lastArchive = appContext.playbackQueueDataStore.updateData {
            it.copy(librarySignature = signature)
        }
    }

    /** 上次扫描时的曲库签名：为空表示从未扫完过，需按首启动处理。 */
    suspend fun readLibrarySignature(): String? {
        lastArchive?.let { return it.librarySignature }
        return appContext.playbackQueueDataStore.data
            .catchPersistenceIo("PlaylistPersistence") { QueueArchive() }
            .first()
            .also { lastArchive = it }
            .librarySignature
    }
}

private fun QueueArchive.withSnapshot(snapshot: PlaylistPersistence.Snapshot): QueueArchive = QueueArchive(
    tracks = snapshot.tracks.map { it.toStoredTrack() },
    defaultTracks = if (snapshot.source != null) {
        snapshot.defaultPlaylist.map { it.toStoredTrack() }
    } else {
        emptyList()
    },
    sourceKey = snapshot.source?.key,
    sourceName = snapshot.source?.name.orEmpty(),
    followsQueue = snapshot.followsQueue,
    viewedSourceKey = snapshot.viewedSource?.key,
    viewedSourceName = snapshot.viewedSource?.name.orEmpty(),
    sortField = snapshot.sortField.name,
    sortDescending = snapshot.sortDescending,
    // 签名与队列同文件，但由扫描侧单独写；更新队列时保留既有值
    librarySignature = librarySignature,
)
