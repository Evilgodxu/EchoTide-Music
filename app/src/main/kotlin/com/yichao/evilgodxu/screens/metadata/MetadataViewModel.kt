package com.yichao.evilgodxu.screens.metadata

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.TrackMetadataEditor
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.panel.TranslateLineOutcome
import com.yichao.evilgodxu.data.music.panel.autoTranslateLyricLine
import java.io.File
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

// 条目改动的落盘延迟：每次输入都重写整段音频，逐键写入会把同一份音频反复搬运。
// 以停顿为界合并连续输入，用户停止输入后统一写一次
private const val AUTO_SAVE_DEBOUNCE_MS = 600L

/**
 * 元数据编辑页状态持有者：进入页面读取内嵌标签回填表单，
 * 条目改动后自动写回音频文件并同步曲目内存态，页面不再有手动保存入口。
 */
class MetadataViewModel(
    application: Application,
    private val stateHolder: MusicPanelStateHolder,
    private val trackId: Long,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MetadataUiState())
    val uiState: StateFlow<MetadataUiState> = _uiState.asStateFlow()

    // 文件内嵌标签的原值：作为「哪些字段被改动过」的比较基准，写入成功后随之刷新
    private var snapshot = MetadataFormSnapshot("", "", "", emptyList())

    // 用户选择的本地封面：已选新图时保存写入，已移除时为 null 表示删除内嵌封面
    private var pendingCover: ByteArray? = null
    private var coverChanged = false

    // 待落盘的条目改动：同一字段的连续输入只保留最后一次，避免排队重写多遍文件
    private var pending: PendingChanges? = null
    private var saveJob: Job? = null
    // 单行补译的进行中任务：重新读盘会作废它，避免结果落到已刷新的歌词列表上
    private var translateJob: Job? = null
    // 写入互斥：同一时刻只允许一遍文件重写，并发重写同一文件会相互覆盖
    private val writeMutex = Mutex()

    // 一次待写入的改动集合：按条目累积，到点后合并成一次文件重写
    private data class PendingChanges(
        var title: String? = null,
        var artist: String? = null,
        var album: String? = null,
        var lyrics: List<LyricLine>? = null,
    ) {
        val isEmpty: Boolean get() = title == null && artist == null && album == null && lyrics == null
    }

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
        _uiState.update {
            it.copy(
                loading = true,
                editable = true,
                message = null,
                editing = null,
                lyricLineDraft = null,
                lyricsWholeMode = false,
                translatingLine = null,
            )
        }
        loadTags(target)
    }

    // 把编辑中间态清回初始值：重新读取后此前未提交的改动与提示都不再适用
    private fun resetEditingState() {
        saveJob?.cancel()
        saveJob = null
        translateJob?.cancel()
        translateJob = null
        pending = null
        pendingCover = null
        coverChanged = false
        snapshot = MetadataFormSnapshot("", "", "", emptyList())
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
            val lines = MusicMetadataCache.parseLyricsText(lyrics)
            // 有歌词文本却解析不出行，说明格式有问题：这与「本来就没有歌词」是两回事，
            // 界面须区分二者，否则用户会以为歌词丢了而去找回，实际只是时间戳格式不对
            val lyricsUnparsable = lyrics.isNotBlank() && lines.isEmpty()
            val cover = TrackMetadataEditor.readCover(context, target)
            snapshot = MetadataFormSnapshot(title, artist, album, lines)
            _uiState.update {
                it.copy(
                    title = title,
                    artist = artist,
                    album = album,
                    lyricLines = lines,
                    lyricsUnparsable = lyricsUnparsable,
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

    // 点击条目进入编辑态：同时只允许一行可编辑。切换到其它条目时先提交上一行的原文草稿，
    // 避免正在编辑的歌词行因切换而丢掉改动
    fun onEditStart(target: MetadataEditTarget) {
        if (_uiState.value.saving || _uiState.value.loading) return
        if (_uiState.value.editing == target) return
        commitLyricDraft()
        // 歌词原文行预填完整增强 LRC（行时间戳 + 逐字标签 + 文本，不含翻译），
        // 使时间戳可被完整修改，能力对齐首页歌词编辑模块；全文编辑预填整篇歌词的增强 LRC
        val draft = when (target) {
            is MetadataEditTarget.LyricLineAt -> _uiState.value.lyricLines.getOrNull(target.index)
                ?.let { MusicMetadataCache.encodeLyricLine(it, includeTranslation = false) }
            MetadataEditTarget.LyricsWhole -> MusicMetadataCache.encodeLyrics(_uiState.value.lyricLines)
            else -> null
        }
        _uiState.update { it.copy(editing = target, message = null, lyricLineDraft = draft) }
    }

    // 结束编辑态：先提交原文草稿再收起输入框。
    // 由「完成」键与点击其它区域触发，两者都是用户的明确意图，不依赖焦点事件时序
    fun onEditEnd() {
        commitLyricDraft()
        _uiState.update { it.copy(editing = null, lyricLineDraft = null) }
    }

    fun onTitleChange(value: String) = updateField(MetadataField.TITLE, value)

    fun onArtistChange(value: String) = updateField(MetadataField.ARTIST, value)

    fun onAlbumChange(value: String) = updateField(MetadataField.ALBUM, value)

    private fun updateField(field: MetadataField, value: String) {
        _uiState.update {
            when (field) {
                MetadataField.TITLE -> it.copy(title = value, message = null)
                MetadataField.ARTIST -> it.copy(artist = value, message = null)
                MetadataField.ALBUM -> it.copy(album = value, message = null)
            }
        }
        scheduleFieldSave(field, value)
    }

    // 切换歌词全文编辑模式：只切换展示形态，不建立编辑态，
    // 故进入全文时不会自动弹出键盘；退出前提交可能存在的草稿
    fun onLyricsWholeModeToggle() {
        if (_uiState.value.saving || _uiState.value.loading) return
        commitLyricDraft()
        val wholeMode = !_uiState.value.lyricsWholeMode
        _uiState.update {
            it.copy(lyricsWholeMode = wholeMode, editing = null, lyricLineDraft = null, message = null)
        }
    }

    // 原文行内联草稿：仅暂存用户输入，不解析也不落盘。
    // 编辑过程中文本可能处于中间态（如 <mm:ss.5 标签尚未补全），此时解析会丢字，故等到编辑结束再提交
    fun onLyricRawChange(raw: String) =
        _uiState.update { it.copy(lyricLineDraft = raw, message = null) }

    // 翻译行改写：与原文行互不干扰，留空即清除该行翻译。
    // 输入过程中原样保留用户文本（含首尾空白）：若按去空白后的值回写状态，
    // 「敲入的空格」会与当前值相等而被判定成无改动，空格键等于失效；去空白只在落盘边界做
    fun onLyricTranslationChange(index: Int, text: String) {
        val lines = _uiState.value.lyricLines
        if (index !in lines.indices) return
        if (lines[index].translation == text) return
        val updated = lines.toMutableList().also { it[index] = it[index].copy(translation = text) }
        _uiState.update { it.copy(lyricLines = updated, message = null) }
        scheduleLyricsSave(updated)
    }

    /**
     * 单行补译：为该行的原文请求译文并并入其翻译行，随后照常自动落盘。
     *
     * 目标行由用户在翻译行的长按菜单中显式指定，故不套用整篇补译的「歌词正文行」规则。
     * 译文回来时按发起时的原文校验目标行 —— 请求期间用户可能改写或增删了歌词，
     * 下标所指的行已不是发起时那一行，此时丢弃结果，避免把译文写到别的行上。
     */
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
                // 原文已是中文、或译文与原文相同：都是正常结果，用普通提示而非报错
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

    // 把译文并入指定行：仅当该行原文仍是发起补译时的文本才写入，否则视为行已错位而丢弃
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

    /**
     * 提交歌词草稿：把内联编辑的增强 LRC 解析回歌词。
     *
     * 逐行编辑整体替换该行（解析结果可能拆分为多行，与首页一致），全文编辑整体替换整篇。
     * 解析不出行时保留原歌词并提示格式问题，避免一次误删时间戳前缀就丢掉内容。
     * 翻译不由原文入口维护，逐行提交后按原值保留。
     */
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
        // 文本未变（如仅打开又退出）时不做任何写入与状态刷新
        if (updated == lines) return
        _uiState.update { it.copy(lyricLines = updated) }
        scheduleLyricsSave(updated)
    }

    fun onCoverSelected(bytes: ByteArray) {
        pendingCover = bytes
        coverChanged = true
        _uiState.update { it.copy(coverBytes = bytes, coverPresent = true, message = null) }
        // 封面是一次性的选择结果，不参与逐键输入，直接落盘
        scheduleSave()
    }

    fun onCoverRemoved() {
        pendingCover = null
        coverChanged = true
        _uiState.update { it.copy(coverBytes = null, coverPresent = false, message = null) }
        scheduleSave()
    }

    // 文本字段待写入值：去空白后的内容。
    // 等于快照原值时把该条目的待写入值清回 null —— 否则「改回原值」会残留上一轮记录的旧值，
    // 停顿时仍按旧值重写文件，出现「内容没变也写入」
    private fun scheduleFieldSave(field: MetadataField, value: String) {
        val newValue = value.trim().takeIf { it != snapshot.value(field) }
        val changes = pending ?: if (newValue != null) PendingChanges().also { pending = it } else return
        when (field) {
            MetadataField.TITLE -> changes.title = newValue
            MetadataField.ARTIST -> changes.artist = newValue
            MetadataField.ALBUM -> changes.album = newValue
        }
        if (changes.isEmpty && !coverChanged) return
        scheduleSave()
    }

    /**
     * 歌词待写入值：与文本字段同理，改回原歌词时清掉待写入值，避免按旧值重写文件。
     *
     * 落盘前统一去空白（含翻译行，空串即清除）：界面为了能用空格键而原样保留输入，
     * 写入与「是否改动」的比较都按去空白后的内容进行 —— 否则只多敲一个空格也会重写整段音频
     */
    private fun scheduleLyricsSave(lines: List<LyricLine>) {
        val normalized = lines.map { it.copy(translation = it.translation?.trim()?.takeIf(String::isNotEmpty)) }
        val newValue = normalized.takeIf { it != snapshot.lyricLines }
        val changes = pending ?: if (newValue != null) PendingChanges().also { pending = it } else return
        changes.lyrics = newValue
        if (changes.isEmpty && !coverChanged) return
        scheduleSave()
    }

    /**
     * 安排一次自动落盘：延迟到输入停顿后执行，期间的新改动合并进同一批。
     *
     * 合并的意义在于文件重写成本 —— 每次落盘都要把整段音频重新搬运一遍，
     * 逐键写入会让同一份音频被反复复制；以停顿为界则一次输入只写一遍。
     *
     * 只取消尚未开始的等待，不打断进行中的写入：写入一旦开始就要写完，
     * 中途取消会留下半截写入且本批改动已在 pending 中被取出，改动将无从补写
     */
    private fun scheduleSave() {
        if (_uiState.value.loading || !_uiState.value.editable) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(AUTO_SAVE_DEBOUNCE_MS)
            flush()
        }
    }

    /**
     * 立即落盘尚未到点的改动，供页面离开时调用。
     *
     * 写入必须交给页面之外的作用域：本方法由组合的销毁回调触发，viewModelScope 随即
     * 连同 ViewModel 一起被取消，在其上 launch 的写入会在真正执行前就被取消，
     * 改动留在 pending 中再也等不到落盘。playbackScope 随播放状态长驻，可作为承接方。
     *
     * 不等待写入完成 —— 调用方是组合的销毁回调，阻塞它只会推迟页面切换
     */
    fun flushPending() {
        // 先把未结束的歌词草稿落入待写入集：草稿只在提交时才成为待写入内容，
        // 否则用户在全文编辑或原文行编辑中直接离开，最后一次改动会随组合销毁一起丢掉
        commitLyricDraft()
        saveJob?.cancel()
        saveJob = null
        if (pending == null && !coverChanged) return
        stateHolder.state.playbackScope.launch { flush() }
    }

    /**
     * 落盘累积的改动：整批字段一次性写回音频文件，成功后同步曲目内存态并重建歌词缓存，
     * 使列表与播放器立即看到新标题/艺术家/专辑。
     *
     * 仅改动过的条目参与写入 —— 未改动的字段传 null，由写入端保留文件原值。
     *
     * 写入串行化：并发重写同一文件会相互覆盖，故由互斥量保证同一时刻只有一遍写入；
     * 等锁期间产生的新改动留在 pending 中，由持锁者的补写轮次或本次接手，不会被丢弃。
     *
     * 整段置于 NonCancellable：文件已按批取出待写，中途取消会让这次写入既没写进文件、
     * 也没能同步内存态与快照，而待写入集已被清空 —— 这批改动就彻底丢了
     */
    private suspend fun flush() = withContext(NonCancellable) { writeMutex.withLock { drain() } }

    // 逐轮写入直到待写入集为空：写入期间新到达的改动由下一轮接手
    private suspend fun drain() {
        while (true) {
            val changes = pending
            val coverChangedNow = coverChanged
            if ((changes == null || changes.isEmpty) && !coverChangedNow) return
            pending = null
            val lyricsText = changes?.lyrics?.let { MusicMetadataCache.encodeLyrics(it) }
            val target = findTrack() ?: return
            _uiState.update { it.copy(saving = true, message = null, messageIsError = false) }
            val context = getApplication<Application>()
            val success = TrackMetadataEditor.save(
                context = context,
                track = target,
                title = changes?.title,
                artist = changes?.artist,
                album = changes?.album,
                cover = pendingCover.takeIf { coverChangedNow },
                lyrics = lyricsText,
            )
            if (!success) {
                // 失败时把改动退回待写入集：用户仍停留在编辑态，下一次输入或离开页面时再试
                requeue(changes, coverChangedNow)
                _uiState.update {
                    it.copy(saving = false, messageIsError = true, message = message(R.string.metadata_save_failed))
                }
                return
            }
            applyToMemory(
                target = target,
                title = changes?.title,
                artist = changes?.artist,
                album = changes?.album,
                lyrics = changes?.lyrics,
                coverChangedNow = coverChangedNow,
            )
            // 写入成功后以本批内容为新基准：此后不再有未落盘改动。
            // 快照按批推进而非取当前界面值 —— 界面可能已被用户继续改过，那是下一轮要写的
            snapshot = MetadataFormSnapshot(
                title = changes?.title ?: snapshot.title,
                artist = changes?.artist ?: snapshot.artist,
                album = changes?.album ?: snapshot.album,
                lyricLines = changes?.lyrics ?: snapshot.lyricLines,
            )
            if (coverChangedNow) {
                pendingCover = null
                coverChanged = false
            }
            // 待回填的字段值：去空白后的本批写入值，供下方按「界面值是否已被继续改动」判定
            val batchTitle = changes?.title
            val batchArtist = changes?.artist
            val batchAlbum = changes?.album
            _uiState.update {
                it.copy(
                    saving = false,
                    messageIsError = false,
                    message = message(R.string.metadata_save_done),
                    // 去空白后的值回填界面，使界面与文件内容一致。
                    // 只回填「本批写过且界面值仍是本批值」的字段：写入期间用户可能已在改别的字段、
                    // 或继续在同一字段上输入，用快照覆盖会把那些更新的文本抹掉
                    title = if (batchTitle != null && it.title == batchTitle) snapshot.title else it.title,
                    artist = if (batchArtist != null && it.artist == batchArtist) snapshot.artist else it.artist,
                    album = if (batchAlbum != null && it.album == batchAlbum) snapshot.album else it.album,
                )
            }
        }
    }

    // 写入失败后把本批改动放回待写入集：与写入期间产生的新改动合并，不覆盖新值
    private fun requeue(changes: PendingChanges?, coverChangedNow: Boolean) {
        if (changes != null) {
            val current = pending ?: PendingChanges().also { pending = it }
            changes.title?.let { if (current.title == null) current.title = it }
            changes.artist?.let { if (current.artist == null) current.artist = it }
            changes.album?.let { if (current.album == null) current.album = it }
            changes.lyrics?.let { if (current.lyrics == null) current.lyrics = it }
        }
        // 封面写入失败时保留待写入标记，用户下次改动会连同封面一起重试
        if (coverChangedNow && !coverChanged) coverChanged = true
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
        lyrics: List<LyricLine>?,
        coverChangedNow: Boolean,
    ) {
        val state = stateHolder.state
        var updated = target
        if (title != null) updated = updated.copy(title = title)
        if (artist != null) updated = updated.copy(artist = artist)
        if (album != null) updated = updated.copy(albumName = album)
        if (lyrics != null) {
            // 歌词缓存写入是文件 IO，切到 IO 调度，避免在主线程上落盘
            val path = withContext(Dispatchers.IO) {
                MusicMetadataCache.saveLyrics(getApplication(), updated.title, updated.artist, lyrics)
            }
            updated = updated.copy(
                lyricLines = lyrics,
                lyricCachePath = path ?: updated.lyricCachePath,
                lyricFailed = lyrics.isEmpty(),
            )
        }
        state.updateTrack(updated)
        // 封面字节随文件重写而变（写入新图或移除内嵌封面），作废显示端的封面缓存，
        // 使列表与播放器取到新图而不是旧缩略图
        if (coverChangedNow) state.bumpCoverRevision()
    }

    private fun message(resId: Int): String = getApplication<Application>().getString(resId)
}
