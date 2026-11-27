package com.yichao.evilgodxu.screens.metadata

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.analysis.REPLAY_GAIN_REFERENCE_LUFS
import com.yichao.evilgodxu.data.music.analysis.ReplayGainAnalyzer
import com.yichao.evilgodxu.data.music.api.enrichTrackDetail
import com.yichao.evilgodxu.data.music.metadata.CoverExporter
import com.yichao.evilgodxu.data.music.metadata.CoverUpdate
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.METADATA_FILL_DETAIL_WAIT_MS
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.metadata.TrackTagFields
import com.yichao.evilgodxu.data.music.metadata.fillPlan
import com.yichao.evilgodxu.data.music.metadata.formatReplayGainDb
import com.yichao.evilgodxu.data.music.metadata.formatReplayGainPeak
import com.yichao.evilgodxu.data.music.metadata.formatReplayGainReferenceLoudness
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.TranslateLineOutcome
import com.yichao.evilgodxu.data.music.panel.applyCoverCandidate
import com.yichao.evilgodxu.data.music.panel.autoTranslateLyricLine
import com.yichao.evilgodxu.data.music.panel.searchCoverCandidates
import com.yichao.evilgodxu.data.music.panel.searchMetadataCandidates
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// 输入停顿 600ms 后合并落盘，避免反复重写音频
private const val AUTO_SAVE_DEBOUNCE_MS = 600L

// 同专辑封面候选上限：横向缩略图一屏可见即可
private const val SAME_ALBUM_COVER_LIMIT = 5

// 同专辑扫描上限：挡住专辑名判等过宽
private const val SAME_ALBUM_SCAN_LIMIT = 60

/** 元数据编辑页状态：读标签回填，改动自动写回音频 */
class MetadataViewModel(
    application: Application,
    private val playbackState: MusicPlaybackState,
    private val trackId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MetadataUiState(trackId = trackId))
    val uiState: StateFlow<MetadataUiState> = _uiState.asStateFlow()

    // 文件内嵌标签原值：判断字段是否改动，写入后刷新
    private var snapshot = TrackTagFields.EMPTY

    // 待落盘的封面改动：不改 / 换图 / 删除
    private var pendingCover: CoverUpdate = CoverUpdate.Keep

    // 待写字段：同字段只留最后一次，避免重写多遍
    private var pending: MutableMap<TagField, String>? = null
    // 在写的批次：据此判断改回原值是否已写入
    private var inFlight: Map<TagField, String>? = null
    private var saveJob: Job? = null
    // 单行补译任务：重新读盘即作废
    private var translateJob: Job? = null
    // 写入互斥：同一时刻只允许一遍重写
    private val writeMutex = Mutex()

    init {
        // 首次读取兜底：预览、测试单独构造时也有状态
        reload()
    }

    /** 重读回填表单；读取时置 loading */
    fun reload() {
        val target = findTrack()
        if (target == null || !target.isLocalAudioSource) {
            // 在线流无本地文件，表单不可编辑
            _uiState.update { it.copy(loading = false, editable = false) }
            return
        }
        // 曲目可能已被整体替换，重置快照与待写状态
        resetEditingState()
        _uiState.update {
            it.copy(
                loading = true,
                editable = true,
                message = null,
                editing = null,
                lyricLineDraft = null,
                lyricsWholeMode = false,
                translatingLine = null,
                sameAlbumVisible = false,
                sameAlbumLoading = false,
                sameAlbumCovers = emptyList(),
                coverRefreshVisible = false,
                coverRefreshSelectedId = null,
                coverRefreshSaving = false,
                exportingCover = false,
                fillVisible = false,
                fillSelectedId = null,
                fillModeVisible = false,
                fillApplying = false,
                fillOverwrite = false,
                replayGainComputing = false,
            )
        }
        loadTags(target)
    }

    // 清空编辑中间态
    private fun resetEditingState() {
        saveJob?.cancel()
        saveJob = null
        translateJob?.cancel()
        translateJob = null
        pending = null
        pendingCover = CoverUpdate.Keep
        snapshot = TrackTagFields.EMPTY
    }

    // 队列与曲库各查一次
    private fun findTrack(): MusicTrack? {
        val playbackState = this.playbackState
        return playbackState.playlist.firstOrNull { it.id == trackId }
            ?: playbackState.libraryTracks.firstOrNull { it.id == trackId }
    }

    private fun loadTags(target: MusicTrack) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val tags = TrackMetadataEditor.read(context, target)
            val textFields = tags?.fields
            val lyrics = resolveLyrics(target, tags?.lyrics, tags?.lyricsModifiedMs ?: 0L)
            val lines = MusicMetadataCache.parseLyricsText(lyrics)
            // 有文本却解析不出行：格式错，需与无歌词区分
            val lyricsUnparsable = lyrics.isNotBlank() && lines.isEmpty()
            // 初值：读不出标签时回落到曲目内存态
            val initial = TrackTagFields.build {
                TagField.entries.forEach { field ->
                    val value = when (field) {
                        TagField.LYRICS -> lyrics.takeIf { it.isNotBlank() }
                        TagField.TITLE -> textFields?.get(field)?.takeIf { it.isNotBlank() } ?: target.title.takeIf { it.isNotBlank() }
                        TagField.ARTIST -> textFields?.get(field)?.takeIf { it.isNotBlank() } ?: target.artist.takeIf { it.isNotBlank() }
                        TagField.ALBUM -> textFields?.get(field)?.takeIf { it.isNotBlank() } ?: target.albumName.takeIf { it.isNotBlank() }
                        else -> textFields?.get(field)
                    }
                    put(field, value)
                }
            }
            val cover = TrackMetadataEditor.readCover(context, target)
            snapshot = initial
            _uiState.update {
                it.copy(
                    fields = initial.entries().associate { (field, value) -> field to value },
                    lyricLines = lines,
                    lyricsUnparsable = lyricsUnparsable,
                    coverBytes = cover,
                    loading = false,
                )
            }
        }
    }

    /** 内嵌与缓存取较新的一份；时间不可比时优先内嵌 */
    private suspend fun resolveLyrics(target: MusicTrack, embedded: String?, embeddedMs: Long): String {
        val cachePath = target.lyricCachePath.takeIf { MusicMetadataCache.isValid(getApplication(), it) }
            ?: MusicMetadataCache.findLyrics(getApplication(), target.title, target.artist)
        val cached = cachePath?.let { path ->
            withContext(Dispatchers.IO) { MusicMetadataCache.readLyricsText(getApplication(), path) }
                .takeIf { it.isNotBlank() }
                ?.let { it to MusicMetadataCache.lyricsModifiedMs(getApplication(), path) }
        }
        return when {
            embedded.isNullOrBlank() -> cached?.first.orEmpty()
            cached == null -> embedded
            // 缓存更新时用缓存，否则内嵌优先
            cached.second > embeddedMs && embeddedMs > 0L -> cached.first
            else -> embedded
        }
    }

    // 进入编辑：单行可编辑，切换前先提交草稿
    fun onEditStart(target: MetadataEditTarget) {
        if (_uiState.value.saving || _uiState.value.loading) return
        if (_uiState.value.editing == target) return
        commitLyricDraft()
        // 预填完整增强 LRC（不含翻译），时间戳可改
        val draft = when (target) {
            is MetadataEditTarget.LyricLineAt -> _uiState.value.lyricLines.getOrNull(target.index)
                ?.let { MusicMetadataCache.encodeLyricLine(it, includeTranslation = false) }
            MetadataEditTarget.LyricsWhole -> MusicMetadataCache.encodeLyrics(_uiState.value.lyricLines)
            else -> null
        }
        _uiState.update { it.copy(editing = target, message = null, lyricLineDraft = draft) }
    }

    // 结束编辑：先提交草稿再收起输入框
    fun onEditEnd() {
        commitLyricDraft()
        _uiState.update { it.copy(editing = null, lyricLineDraft = null) }
    }

    // 字段统一回调
    fun onFieldChange(field: TagField, value: String) {
        _uiState.update { it.copy(fields = it.fields + (field to value), message = null) }
        scheduleFieldSave(field, value)
    }

    // 切换全文模式：只换形态，不弹键盘
    fun onLyricsWholeModeToggle() {
        if (_uiState.value.saving || _uiState.value.loading) return
        commitLyricDraft()
        val wholeMode = !_uiState.value.lyricsWholeMode
        _uiState.update {
            it.copy(lyricsWholeMode = wholeMode, editing = null, lyricLineDraft = null, message = null)
        }
    }

    // 原文行草稿：只暂存，编辑结束再解析提交
    fun onLyricRawChange(raw: String) =
        _uiState.update { it.copy(lyricLineDraft = raw, message = null) }

    // 翻译行改写：留空即清除，去空白只在落盘时做
    fun onLyricTranslationChange(index: Int, text: String) {
        val lines = _uiState.value.lyricLines
        if (index !in lines.indices) return
        if (lines[index].translation == text) return
        val updated = lines.toMutableList().also { it[index] = it[index].copy(translation = text) }
        _uiState.update { it.copy(lyricLines = updated, message = null) }
        scheduleLyricsSave(updated)
    }

    /** 单行补译：译文回来按原文校验，行已变则丢弃 */
    fun onLyricLineTranslate(index: Int) {
        val state = _uiState.value
        if (state.saving || state.loading || state.translatingLine != null) return
        val source = state.lyricLines.getOrNull(index)?.text?.trim() ?: return
        if (source.isEmpty()) {
            _uiState.update {
                it.copy(messageIsError = false, message = message(R.string.metadata_lyrics_translate_nothing))
            }
            return
        }
        _uiState.update { it.copy(translatingLine = index, message = null) }
        translateJob = viewModelScope.launch {
            when (val outcome = autoTranslateLyricLine(source)) {
                is TranslateLineOutcome.Applied -> applyLineTranslation(index, source, outcome.translation)
                // 原文已是中文或译文相同：普通提示
                TranslateLineOutcome.SameLanguage -> _uiState.update {
                    it.copy(messageIsError = false, message = message(R.string.music_panel_auto_translate_same_language))
                }
                TranslateLineOutcome.NothingToDo -> _uiState.update {
                    it.copy(messageIsError = false, message = message(R.string.metadata_lyrics_translate_nothing))
                }
                TranslateLineOutcome.Failed -> _uiState.update {
                    it.copy(messageIsError = true, message = message(R.string.music_panel_auto_translate_failed))
                }
            }
            _uiState.update { it.copy(translatingLine = null) }
        }
    }

    // 原文已变则丢弃译文
    private fun applyLineTranslation(index: Int, source: String, translation: String) {
        val lines = _uiState.value.lyricLines
        val current = lines.getOrNull(index) ?: return
        if (current.text.trim() != source) return
        val updated = lines.toMutableList().also { it[index] = current.copy(translation = translation) }
        _uiState.update {
            it.copy(lyricLines = updated, messageIsError = false, message = message(R.string.metadata_lyrics_translate_done))
        }
        scheduleLyricsSave(updated)
    }

    /** 解析增强 LRC；失败保留原词并提示 */
    private fun commitLyricDraft() {
        val draft = _uiState.value.lyricLineDraft ?: return
        val target = _uiState.value.editing ?: return
        val lines = _uiState.value.lyricLines
        val parsed = MusicMetadataCache.parseLyricsText(draft)
        if (parsed.isEmpty()) {
            _uiState.update {
                it.copy(messageIsError = true, message = message(R.string.metadata_lyrics_invalid))
            }
            return
        }
        val updated: List<LyricLine> = when (target) {
            is MetadataEditTarget.LyricLineAt -> {
                val existing = lines.getOrNull(target.index) ?: return
                val replaced = parsed.map { it.copy(translation = existing.translation) }
                lines.toMutableList().also {
                    it.removeAt(target.index)
                    it.addAll(target.index, replaced)
                }
            }
            MetadataEditTarget.LyricsWhole -> parsed
            else -> return
        }
        // 文本未变则不写入
        if (updated == lines) return
        _uiState.update { it.copy(lyricLines = updated) }
        scheduleLyricsSave(updated)
    }

    // 封面

    fun onCoverSelected(bytes: ByteArray) {
        pendingCover = CoverUpdate.Set(bytes)
        _uiState.update {
            it.copy(
                coverBytes = bytes,
                sameAlbumVisible = false,
                message = null,
            )
        }
        // 一次性选择，直接落盘
        scheduleSave()
    }

    fun onCoverRemoved() {
        pendingCover = CoverUpdate.Remove
        _uiState.update { it.copy(coverBytes = null, message = null) }
        scheduleSave()
    }

    // 保存当前封面到相册，含未落盘封面
    fun onCoverExport() {
        val state = _uiState.value
        if (state.exportingCover) return
        val bytes = state.coverBytes
        if (bytes == null || bytes.isEmpty()) {
            _uiState.update {
                it.copy(messageIsError = true, message = message(R.string.metadata_cover_none_to_save))
            }
            return
        }
        val target = findTrack() ?: return
        _uiState.update { it.copy(exportingCover = true, message = null) }
        viewModelScope.launch {
            val result = CoverExporter.exportBytes(getApplication(), target, bytes)
            _uiState.update {
                it.copy(
                    exportingCover = false,
                    messageIsError = result != CoverExporter.Result.Saved,
                    message = message(
                        if (result == CoverExporter.Result.Saved) {
                            R.string.metadata_cover_saved
                        } else {
                            R.string.metadata_cover_save_failed
                        },
                    ),
                )
            }
        }
    }

    // 打开即读取候选
    fun onSameAlbumCoversRequest() {
        if (_uiState.value.sameAlbumLoading) return
        _uiState.update { it.copy(sameAlbumVisible = true, sameAlbumLoading = true, sameAlbumCovers = emptyList(), message = null) }
        viewModelScope.launch {
            val target = findTrack()
            val covers = if (target == null) emptyList() else loadSameAlbumCovers(target)
            _uiState.update {
                it.copy(sameAlbumLoading = false, sameAlbumCovers = covers, sameAlbumVisible = true)
            }
        }
    }

    fun onSameAlbumCoversDismiss() {
        _uiState.update { it.copy(sameAlbumVisible = false, sameAlbumCovers = emptyList(), sameAlbumLoading = false) }
    }

    // 同专辑其它本地封面：按内容去重，取满即停
    private suspend fun loadSameAlbumCovers(target: MusicTrack): List<ByteArray> = withContext(Dispatchers.IO) {
        val album = target.albumName.trim()
        if (album.isEmpty()) return@withContext emptyList()
        val candidates = (playbackState.playlist + playbackState.libraryTracks)
            .filter { it.id != target.id && it.isLocalAudioSource && it.albumName.trim().equals(album, ignoreCase = true) }
            .distinctBy { it.id }
            .take(SAME_ALBUM_SCAN_LIMIT)
        val seen = mutableSetOf<Long>()
        val covers = mutableListOf<ByteArray>()
        for (track in candidates) {
            if (covers.size >= SAME_ALBUM_COVER_LIMIT) break
            val bytes = TrackMetadataEditor.readCover(getApplication(), track) ?: continue
            val key = (bytes.size.toLong() shl 32) or (bytes.contentHashCode().toLong() and 0xffffffffL)
            if (seen.add(key)) covers += bytes
        }
        covers
    }

    // 在线刷新封面

    fun onCoverRefreshOpen() {
        val target = findTrack() ?: return
        _uiState.update { it.copy(coverRefreshVisible = true, coverRefreshSelectedId = null, message = null) }
        viewModelScope.launch {
            searchCoverCandidates(playbackState, target, playbackState.coverRefreshSource)
        }
    }

    fun onCoverRefreshSourceSelected(source: MusicSearchSource) {
        val target = findTrack() ?: return
        if (source == playbackState.coverRefreshSource) return
        playbackState.setCoverRefreshSource(source)
        _uiState.update { it.copy(coverRefreshSelectedId = null) }
        viewModelScope.launch { searchCoverCandidates(playbackState, target, source) }
    }

    fun onCoverRefreshReload() {
        val target = findTrack() ?: return
        _uiState.update { it.copy(coverRefreshSelectedId = null) }
        viewModelScope.launch {
            searchCoverCandidates(playbackState, target, playbackState.coverRefreshSource)
        }
    }

    fun onCoverRefreshCandidateSelected(candidate: NeteaseSongSearchResult) {
        _uiState.update { it.copy(coverRefreshSelectedId = candidate.id) }
    }

    /** 确认在线刷新：写入候选封面并回填；同时作废本地待写封面 */
    fun onCoverRefreshConfirm() {
        val state = _uiState.value
        if (state.coverRefreshSaving) return
        val candidate = playbackState.coverCandidates.firstOrNull { it.id == state.coverRefreshSelectedId } ?: return
        val target = findTrack() ?: return
        _uiState.update { it.copy(coverRefreshSaving = true) }
        viewModelScope.launch {
            val success = applyCoverCandidate(getApplication(), playbackState, target, candidate)
            if (success) {
                pendingCover = CoverUpdate.Keep
                val cover = TrackMetadataEditor.readCover(getApplication(), target)
                _uiState.update {
                    it.copy(
                        coverRefreshSaving = false,
                        coverRefreshVisible = false,
                        coverRefreshSelectedId = null,
                        coverBytes = cover,
                                messageIsError = false,
                        message = message(R.string.metadata_cover_refresh_done),
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        coverRefreshSaving = false,
                        messageIsError = true,
                        message = message(R.string.metadata_cover_refresh_failed),
                    )
                }
            }
        }
    }

    fun onCoverRefreshCancel() {
        _uiState.update { it.copy(coverRefreshVisible = false, coverRefreshSelectedId = null) }
        playbackState.setCoverCandidates(emptyList())
    }

    // 音量增益自算

    /** 整曲解码算单曲增益、峰值与参考响度；专辑增益不参与 */
    fun onReplayGainCompute() {
        val state = _uiState.value
        if (state.replayGainComputing || state.loading || !state.editable) return
        val target = findTrack() ?: return
        _uiState.update { it.copy(replayGainComputing = true, message = null, messageIsError = false) }
        viewModelScope.launch {
            val measurement = ReplayGainAnalyzer.analyze(target)
            if (measurement == null) {
                _uiState.update {
                    it.copy(
                        replayGainComputing = false,
                        messageIsError = true,
                        message = message(R.string.metadata_replay_gain_failed),
                    )
                }
                return@launch
            }
            onFieldChange(TagField.REPLAY_GAIN_TRACK_GAIN, formatReplayGainDb(measurement.gainDb))
            onFieldChange(TagField.REPLAY_GAIN_TRACK_PEAK, formatReplayGainPeak(measurement.peak))
            onFieldChange(
                TagField.REPLAY_GAIN_REFERENCE_LOUDNESS,
                formatReplayGainReferenceLoudness(REPLAY_GAIN_REFERENCE_LUFS),
            )
            _uiState.update {
                it.copy(
                    replayGainComputing = false,
                    messageIsError = false,
                    message = message(R.string.metadata_replay_gain_done),
                )
            }
        }
    }

    // 在线补全

    fun onFillOpen() {
        val target = findTrack() ?: return
        _uiState.update { it.copy(fillVisible = true, fillSelectedId = null, message = null) }
        viewModelScope.launch { searchMetadataCandidates(playbackState, target, playbackState.metadataFillSource) }
    }

    fun onFillSourceSelected(source: MusicSearchSource) {
        if (source == playbackState.metadataFillSource) return
        val target = findTrack() ?: return
        playbackState.metadataFillSource = source
        _uiState.update { it.copy(fillSelectedId = null) }
        viewModelScope.launch { searchMetadataCandidates(playbackState, target, source) }
    }

    fun onFillReload() {
        val target = findTrack() ?: return
        _uiState.update { it.copy(fillSelectedId = null) }
        viewModelScope.launch { searchMetadataCandidates(playbackState, target, playbackState.metadataFillSource) }
    }

    fun onFillCandidateSelected(candidate: NeteaseSongSearchResult) {
        _uiState.update { it.copy(fillSelectedId = candidate.id) }
    }

    fun onFillOverwriteChange(overwrite: Boolean) {
        _uiState.update { it.copy(fillOverwrite = overwrite) }
    }

    // 确认候选后进入补全方式对话框，取消可回到候选
    fun onFillConfirm() {
        if (_uiState.value.fillSelectedId == null) return
        _uiState.update { it.copy(fillVisible = false, fillModeVisible = true) }
    }

    fun onFillModeDismiss() {
        _uiState.update { it.copy(fillModeVisible = false, fillVisible = true) }
    }

    /** 填入表单，复用 onFieldChange 落盘 */
    fun onFillApply() {
        val state = _uiState.value
        if (state.fillApplying) return
        val candidate = playbackState.metadataFillCandidates.firstOrNull { it.id == state.fillSelectedId } ?: return
        _uiState.update { it.copy(fillApplying = true) }
        viewModelScope.launch {
            // 音轨号等只在详情接口，落地前补一次
            val detailed = withTimeoutOrNull(METADATA_FILL_DETAIL_WAIT_MS) { enrichTrackDetail(candidate) } ?: candidate
            val plan = fillPlan(state.fields, detailed, state.fillOverwrite)
            if (plan.isEmpty()) {
                _uiState.update {
                    it.copy(
                        fillApplying = false,
                        fillModeVisible = false,
                        fillSelectedId = null,
                        messageIsError = false,
                        message = message(R.string.metadata_fill_nothing),
                    )
                }
                playbackState.metadataFillCandidates = emptyList()
                return@launch
            }
            plan.forEach { (field, value) -> onFieldChange(field, value) }
            _uiState.update {
                it.copy(
                    fillApplying = false,
                    fillModeVisible = false,
                    fillSelectedId = null,
                    messageIsError = false,
                    message = getApplication<Application>()
                        .resources.getQuantityString(R.plurals.metadata_fill_done, plan.size, plan.size),
                )
            }
            playbackState.metadataFillCandidates = emptyList()
        }
    }

    fun onFillCancel() {
        _uiState.update { it.copy(fillVisible = false, fillModeVisible = false, fillSelectedId = null) }
        playbackState.metadataFillCandidates = emptyList()
    }

    // 落盘

    // 字段待写值去空白；等于原值则移出待写集
    private fun scheduleFieldSave(field: TagField, value: String) {
        val current = value.trim()
        val newValue = current.takeIf { it != snapshot[field].orEmpty() }
        if (newValue == null) {
            // 写入中改回原值：需补写，否则文件停在中间值
            val writing = inFlight?.get(field)
            if (writing != null && writing != current) {
                (pending ?: mutableMapOf<TagField, String>().also { pending = it })[field] = current
            } else {
                pending?.remove(field)
            }
        } else {
            (pending ?: mutableMapOf<TagField, String>().also { pending = it })[field] = newValue
        }
        if (pending.isNullOrEmpty() && pendingCover is CoverUpdate.Keep) return
        scheduleSave()
    }

    /** 歌词待写值：改回原词则清除；落盘前统一去空白 */
    private fun scheduleLyricsSave(lines: List<LyricLine>) {
        val normalized = lines.map { it.copy(translation = it.translation?.trim()?.takeIf(String::isNotEmpty)) }
        val encoded = MusicMetadataCache.encodeLyrics(normalized)
        val newValue = normalized.takeIf { it != snapshotLyrics() }
        if (newValue == null) {
            // 在途歌词不同时仍需补写
            val writing = inFlight?.get(TagField.LYRICS)
            if (writing != null && writing != encoded) {
                (pending ?: mutableMapOf<TagField, String>().also { pending = it })[TagField.LYRICS] = encoded
            } else {
                pending?.remove(TagField.LYRICS)
            }
        } else {
            (pending ?: mutableMapOf<TagField, String>().also { pending = it })[TagField.LYRICS] =
                encoded
        }
        if (pending.isNullOrEmpty() && pendingCover is CoverUpdate.Keep) return
        scheduleSave()
    }

    // 快照歌词按解析结果比较
    private fun snapshotLyrics(): List<LyricLine> =
        MusicMetadataCache.parseLyricsText(snapshot[TagField.LYRICS].orEmpty())

    /** 停顿后合并落盘；只取消未开始的等待，不打断进行中的写入 */
    private fun scheduleSave() {
        if (_uiState.value.loading || !_uiState.value.editable) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /** 立即落盘；交 playbackScope 保证写完 */
    fun flushPending() {
        // 先提交未结束的歌词草稿，避免离开时丢失
        commitLyricDraft()
        saveJob?.cancel()
        saveJob = null
        if (pending.isNullOrEmpty() && pendingCover is CoverUpdate.Keep) return
        playbackState.playbackScope.launch { flush() }
    }

    /** 整批写回并同步内存态；写入串行且不可取消，避免改动丢失 */
    private suspend fun flush() = withContext(NonCancellable) { writeMutex.withLock { drain() } }

    // 逐轮写入直到待写集为空
    private suspend fun drain() {
        while (true) {
            val changes = pending
            val coverEdit = pendingCover
            if (changes.isNullOrEmpty() && coverEdit is CoverUpdate.Keep) return
            pending = null
            inFlight = changes
            val target = findTrack() ?: run { inFlight = null; return }
            val fields = TrackTagFields.build { changes?.forEach { (field, value) -> put(field, value) } }
            _uiState.update { it.copy(saving = true, message = null, messageIsError = false) }
            val success = TrackMetadataEditor.save(
                context = getApplication(),
                track = target,
                fields = fields,
                cover = coverEdit,
            )
            if (!success) {
                // 失败退回待写集，下次再试
                requeue(changes, coverEdit)
                inFlight = null
                _uiState.update {
                    it.copy(saving = false, messageIsError = true, message = message(R.string.metadata_save_failed))
                }
                return
            }
            applyToMemory(
                target = target,
                fields = fields,
                lyrics = changes?.get(TagField.LYRICS),
                coverChanged = coverEdit !is CoverUpdate.Keep,
            )
            // 快照按本批推进，界面后续改动留给下一轮
            snapshot = TrackTagFields.overlay(snapshot) { fields.entries().forEach { (field, value) -> put(field, value) } }
            inFlight = null
            if (coverEdit !is CoverUpdate.Keep) pendingCover = CoverUpdate.Keep
            _uiState.update { state ->
                state.copy(
                    saving = false,
                    messageIsError = false,
                    message = message(R.string.metadata_save_done),
                    // 回填本批写过的字段；界面已改动的保留
                    fields = state.fields.mapValues { (field, current) ->
                        val batch = fields[field]
                        if (batch != null && current.trim() == batch) batch else current
                    },
                )
            }
        }
    }

    // 退回本批改动，不覆盖新改动
    private fun requeue(changes: Map<TagField, String>?, coverEdit: CoverUpdate) {
        if (changes != null) {
            val current = pending ?: mutableMapOf<TagField, String>().also { pending = it }
            changes.forEach { (field, value) -> current.putIfAbsent(field, value) }
        }
        // 封面写入失败保留待写状态，下次重试
        if (coverEdit !is CoverUpdate.Keep && pendingCover is CoverUpdate.Keep) pendingCover = coverEdit
    }

    /** 同步曲目内存态并重建歌词缓存，播放器立即刷新 */
    private suspend fun applyToMemory(
        target: MusicTrack,
        fields: TrackTagFields,
        lyrics: String?,
        coverChanged: Boolean,
    ) {
        val state = playbackState
        var updated = target
        fields[TagField.TITLE]?.let { updated = updated.copy(title = it) }
        fields[TagField.ARTIST]?.let { updated = updated.copy(artist = it) }
        fields[TagField.ALBUM]?.let { updated = updated.copy(albumName = it) }
        if (lyrics != null) {
            // 歌词缓存写盘切到 IO
            val lines = MusicMetadataCache.parseLyricsText(lyrics)
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
        // 封面变化，作废显示端缓存
        if (coverChanged) state.coverCache.bumpCoverRevision()
    }

    private fun message(resId: Int): String = getApplication<Application>().getString(resId)
}
