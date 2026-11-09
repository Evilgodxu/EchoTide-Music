package com.yichao.evilgodxu.data.playlist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

// 自定义歌单的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
internal val PLAYLISTS_STORE = PersistedData("playlists", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class StoredPlaylist(
    val id: Long,
    val name: String,
    val trackIds: List<Long> = emptyList(),
    val createdAt: Long = 0L,
)

@Serializable
private data class PlaylistArchive(
    val playlists: List<StoredPlaylist> = emptyList(),
)

// 类型化 DataStore：整份歌单是一个原子状态，增删改都在一次事务里完成
private val Context.playlistsDataStore: DataStore<PlaylistArchive> by dataStore(
    fileName = "${PLAYLISTS_STORE.name}.json",
    serializer = JsonSerializer(PlaylistArchive.serializer(), PlaylistArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("PlaylistStore", "歌单数据损坏，已重置为空", throwable)
        PlaylistArchive()
    },
)

/**
 * 自定义歌单存储。
 *
 * DataStore 是唯一事实源：读取由 [playlists] 的收集侧持续回流，写入一律经 [DataStore.updateData]
 * 原子提交，不再在内存里另存一份再异步补写。内存中的 [playlists] 只服务 Compose 的同步读取。
 */
class PlaylistStore(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    // 歌单 id 生成：时间戳高 44 位 + 进程内自增低 20 位，避免同毫秒快速创建碰撞
    private val idCounter = AtomicLong(0)

    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    // 首次读盘完成：界面据此区分「暂无歌单」与「尚未读完」
    var isLoaded by mutableStateOf(false)
        private set

    init {
        scope.launch {
            appContext.playlistsDataStore.data
                .catchPersistenceIo("PlaylistStore") { PlaylistArchive() }
                .collect { archive ->
                    playlists = archive.playlists.map { it.toPlaylist() }
                    isLoaded = true
                }
        }
    }

    suspend fun create(name: String): Playlist? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = (now shl 20) or (idCounter.incrementAndGet() and 0xFFFFF),
            name = trimmed,
            trackIds = emptyList(),
            createdAt = now,
        )
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(playlists = archive.playlists + playlist.toStored())
        }
        return playlist
    }

    suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { if (it.id == id) it.copy(name = trimmed) else it },
            )
        }
    }

    suspend fun delete(id: Long) {
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(playlists = archive.playlists.filterNot { it.id == id })
        }
    }

    suspend fun addTracks(id: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { playlist ->
                    if (playlist.id == id) {
                        playlist.copy(trackIds = (playlist.trackIds + trackIds).distinct())
                    } else {
                        playlist
                    }
                },
            )
        }
    }

    suspend fun removeTracks(id: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        val removed = trackIds.toSet()
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { playlist ->
                    if (playlist.id == id) {
                        playlist.copy(trackIds = playlist.trackIds.filterNot { it in removed })
                    } else {
                        playlist
                    }
                },
            )
        }
    }

    // 曲目换 MediaStore ID 后把用户歌单里的引用迁到新 ID：保留原位置与顺序
    suspend fun replaceTrackId(oldId: Long, newId: Long) {
        if (oldId == newId) return
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { playlist ->
                    if (oldId !in playlist.trackIds) {
                        playlist
                    } else {
                        playlist.copy(
                            trackIds = playlist.trackIds
                                .map { if (it == oldId) newId else it }
                                .distinct(),
                        )
                    }
                },
            )
        }
    }

    // 歌曲被彻底删除后，从所有自定义歌单中清除残留引用
    suspend fun removeTrackFromAll(trackId: Long) {
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { playlist ->
                    if (trackId in playlist.trackIds) {
                        playlist.copy(trackIds = playlist.trackIds.filterNot { it == trackId })
                    } else {
                        playlist
                    }
                },
            )
        }
    }

    suspend fun setTrackOrder(id: Long, orderedIds: List<Long>) {
        appContext.playlistsDataStore.updateData { archive ->
            archive.copy(
                playlists = archive.playlists.map { playlist ->
                    if (playlist.id == id) playlist.copy(trackIds = orderedIds.distinct()) else playlist
                },
            )
        }
    }
}

private fun StoredPlaylist.toPlaylist(): Playlist = Playlist(
    id = id,
    name = name,
    trackIds = trackIds,
    createdAt = createdAt,
)

private fun Playlist.toStored(): StoredPlaylist = StoredPlaylist(
    id = id,
    name = name,
    trackIds = trackIds,
    createdAt = createdAt,
)
