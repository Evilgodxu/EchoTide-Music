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

// 队列缓存落盘：过滤结果、默认队列、浏览态与排序
internal val PLAYBACK_QUEUE_STORE = PersistedData("playback_queue", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class QueueArchive(
    val tracks: List<StoredTrack> = emptyList(),
    // 有过滤才单存默认队列；无过滤即 tracks
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

/** 播放列表落盘：整份原子提交，写入合流到最新快照 */
internal class PlaylistPersistence(
    context: Context,
    scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val writeQueue = Channel<Snapshot>(Channel.CONFLATED)

    // 最近读到的档案，签名查询复用免得重解析
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

    // 历史缓存可能有重复条目，按真实路径去重
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

    /** 落盘本轮曲库签名 */
    suspend fun writeLibrarySignature(signature: String) {
        lastArchive = appContext.playbackQueueDataStore.updateData {
            it.copy(librarySignature = signature)
        }
    }

    /** 上次曲库签名，空表示从未扫完 */
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
    // 签名由扫描侧单写，更新队列时保留
    librarySignature = librarySignature,
)
