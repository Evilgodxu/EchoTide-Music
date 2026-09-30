package com.yichao.evilgodxu.screens.metadata

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 元数据编辑页状态持有者：进入页面读取内嵌标签回填表单，保存时整批写回音频文件并同步曲目内存态
class MetadataViewModel(
    application: Application,
    private val stateHolder: MusicPanelStateHolder,
    private val trackId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MetadataUiState())
    val uiState: StateFlow<MetadataUiState> = _uiState.asStateFlow()

    // 文件内嵌标签的原值：作为「哪些字段被改动过」的比较基准，保存成功后随之刷新
    private var snapshot = MetadataFormSnapshot("", "", "", "")

    // 用户选择的本地封面：已选新图时保存写入，已移除时为 null 表示删除内嵌封面
    private var pendingCover: ByteArray? = null
    private var coverChanged = false

    init {
        // 首次读取兜底：页面进入时还会再调一次 reload，正常情况下此处的结果随即被覆盖；
        // 保留它是为了让 ViewModel 单独构造（预览、测试）时也有完整状态
        reload()
    }

    /**
     * 重新读取表单内容，每次进入页面都调用一次。
     *
     * ViewModel 按曲目缓存复用于同一次导航会话，只靠 init 会在二次进入时展示上次的旧快照 ——
     * 用户在别处（在线刷新歌词、改名等）改动过曲目后回到本页，看到的必须是磁盘上的当前值。
     * 读取期间置 loading，避免旧内容与新内容在界面上交叠。
     *
     * 幂等：清空编辑中间态后重新读盘，连续调用只会以最后一次的结果落地
     */
    fun reload() {
        val target = findTrack()
        if (target == null || !target.isLocalAudioSource) {
            // 无本地音频文件的曲目（纯在线流）没有可写的标签目标，表单不可编辑
            _uiState.update { it.copy(loading = false, editable = false) }
            return
        }
        // 曲目引用可能已被上一轮保存整体替换，快照与待写入状态一并按当前曲目重置
        resetEditingState()
        // 歌词折叠回默认态：上次离开时展开与否不应影响本次进入的首屏布局
        _uiState.update { it.copy(loading = true, editable = true, message = null, lyricsExpanded = false) }
        loadTags(target)
    }

    // 把编辑中间态清回初始值：重新读取后此前未提交的改动与提示都不再适用
    private fun resetEditingState() {
        pendingCover = null
        coverChanged = false
        snapshot = MetadataFormSnapshot("", "", "", "")
    }

    // 曲目可能经播放队列或曲库浏览列表进入，两处都查一遍
    private fun findTrack(): MusicTrack? {
        val playbackState = stateHolder.state
        return playbackState.playlist.firstOrNull { it.id == trackId }
            ?: playbackState.libraryTracks.firstOrNull { it.id == trackId }
    }

    private fun loadTags(target: MusicTrack) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val tags = TrackMetadataEditor.read(context, target)
            // 文本字段：文件读不出标签时回落到曲目内存态（扫描结果比空表单更接近真实值）
            val title = tags?.title ?: target.title
            val artist = tags?.artist ?: target.artist
            val album = tags?.album ?: target.albumName
            val lyrics = resolveLyrics(target, tags?.lyrics, tags?.lyricsModifiedMs ?: 0L)
            val cover = TrackMetadataEditor.readCover(context, target)
            snapshot = MetadataFormSnapshot(title, artist, album, lyrics)
            _uiState.update {
                it.copy(
                    title = title,
                    artist = artist,
                    album = album,
                    lyrics = lyrics,
                    coverBytes = cover,
                    coverPresent = cover != null,
                    loading = false,
                )
            }
        }
    }

    /**
     * 歌词择优：内嵌歌词与缓存文件是同一份歌词的两个副本，并不总在同一次事务里落盘
     * ——在线刷新先写缓存再内嵌，内嵌失败时缓存是新的、内嵌是旧的。
     * 因此取二者中修改时间较新的一个；时间不可比（任一侧取不到）时优先内嵌歌词。
     */
    private suspend fun resolveLyrics(target: MusicTrack, embedded: String?, embeddedMs: Long): String {
        val cachePath = target.lyricCachePath.takeIf { MusicMetadataCache.isValid(it) }
            ?: MusicMetadataCache.findLyrics(getApplication(), target.title, target.artist)
        val cached = cachePath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching { File(path).readText() }.getOrNull()
            }?.takeIf { it.isNotBlank() }?.let { it to MusicMetadataCache.lyricsModifiedMs(path) }
        }
        return when {
            embedded.isNullOrBlank() -> cached?.first.orEmpty()
            cached == null -> embedded
            // 缓存更新即采用缓存；时间不可比时保持内嵌优先
            cached.second > embeddedMs && embeddedMs > 0L -> cached.first
            else -> embedded
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value, message = null) }

    fun onArtistChange(value: String) = _uiState.update { it.copy(artist = value, message = null) }

    fun onAlbumChange(value: String) = _uiState.update { it.copy(album = value, message = null) }

    fun onLyricsChange(value: String) = _uiState.update { it.copy(lyrics = value, message = null) }

    // 歌词折叠开关：展开状态不参与保存，纯展示态
    fun onLyricsExpandedChange(expanded: Boolean) = _uiState.update { it.copy(lyricsExpanded = expanded) }

    fun onCoverSelected(bytes: ByteArray) {
        pendingCover = bytes
        coverChanged = true
        _uiState.update { it.copy(coverBytes = bytes, coverPresent = true, message = null) }
    }

    fun onCoverRemoved() {
        pendingCover = null
        coverChanged = true
        _uiState.update { it.copy(coverBytes = null, coverPresent = false, message = null) }
    }

    /**
     * 保存表单：整批字段一次性写回音频文件，成功后同步曲目内存态并重建歌词缓存，
     * 使列表与播放器立即看到新标题/艺术家/专辑。
     *
     * 仅改动过的字段参与写入 —— 未改动的字段传 null，由写入端保留文件原值。
     */
    fun save() {
        val target = findTrack() ?: return
        val state = _uiState.value
        if (state.saving || state.loading) return
        val lyricsChanged = state.lyrics != snapshot.lyrics
        // 歌词格式无效时不予写入：写进去的文本读不回来，等于把可显示的歌词换成无法解析的数据
        if (lyricsChanged && state.lyrics.isNotBlank() &&
            MusicMetadataCache.parseLyricsText(state.lyrics).isEmpty()
        ) {
            _uiState.update { it.copy(messageIsError = true, message = message(R.string.metadata_lyrics_invalid)) }
            return
        }
        _uiState.update { it.copy(saving = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            // 文本字段按去空白后的值写入：快照同步存去空白值，否则仅改空白会被判为「未改动」而反复提交
            val title = state.title.trim()
            val artist = state.artist.trim()
            val album = state.album.trim()
            val success = TrackMetadataEditor.save(
                context = context,
                track = target,
                title = title.takeIf { it != snapshot.title },
                artist = artist.takeIf { it != snapshot.artist },
                album = album.takeIf { it != snapshot.album },
                cover = pendingCover.takeIf { coverChanged },
                lyrics = state.lyrics.takeIf { lyricsChanged },
            )
            if (!success) {
                _uiState.update {
                    it.copy(saving = false, messageIsError = true, message = message(R.string.metadata_save_failed))
                }
                return@launch
            }
            applyToMemory(
                target = target,
                title = title.takeIf { it != snapshot.title },
                artist = artist.takeIf { it != snapshot.artist },
                album = album.takeIf { it != snapshot.album },
                lyrics = state.lyrics.takeIf { lyricsChanged },
                coverChangedNow = coverChanged,
            )
            // 保存成功后以当前表单为新基准：此后不再有未保存改动
            snapshot = MetadataFormSnapshot(title, artist, album, state.lyrics)
            pendingCover = null
            coverChanged = false
            _uiState.update {
                it.copy(
                    saving = false,
                    messageIsError = false,
                    message = message(R.string.metadata_save_done),
                    // 去空白后的值回填界面，使界面与文件内容一致
                    title = title,
                    artist = artist,
                    album = album,
                )
            }
        }
    }

    /**
     * 内存态同步：标题/艺术家/专辑写回曲目，歌词重解析为时间轴后落缓存，
     * 使播放器立即按新字段刷新而不必重新扫描曲库。
     *
     * 歌词缓存索引按「标题 - 艺术家」命名，两者任一被改动即换了文件名，故按新字段重建缓存。
     */
    private suspend fun applyToMemory(
        target: MusicTrack,
        title: String?,
        artist: String?,
        album: String?,
        lyrics: String?,
        coverChangedNow: Boolean,
    ) {
        val state = stateHolder.state
        var updated = target
        if (title != null) updated = updated.copy(title = title)
        if (artist != null) updated = updated.copy(artist = artist)
        if (album != null) updated = updated.copy(albumName = album)
        if (lyrics != null) {
            val lines = MusicMetadataCache.parseLyricsText(lyrics)
            // 歌词缓存写入是文件 IO，切到 IO 调度，避免在主线程上落盘
            val path = withContext(Dispatchers.IO) {
                MusicMetadataCache.saveLyrics(getApplication(), updated.title, updated.artist, lines)
            }
            updated = updated.copy(
                lyricLines = lines,
                lyricCachePath = path ?: updated.lyricCachePath,
                lyricFailed = lines.isEmpty(),
            )
        }
        state.updateTrack(updated)
        // 封面字节随文件重写而变（写入新图或移除内嵌封面），作废显示端的封面缓存，
        // 使列表与播放器取到新图而不是旧缩略图
        if (coverChangedNow) state.bumpCoverRevision()
    }

    private fun message(resId: Int): String = getApplication<Application>().getString(resId)
}
