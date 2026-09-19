package com.yichao.evilgodxu.screens.home.component.player

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.panel.AlignOutcome
import com.yichao.evilgodxu.data.music.panel.alignLyricsWords
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 逐字对齐会话控制器：状态与对齐协程常驻首页层，进度对话框收起后任务继续执行并自动应用结果，
 * 因此切换首页页面不会中断一次对齐。
 */
internal class LyricsAlignmentController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    // 进度对话框显隐：收起仅隐藏展示，不中断对齐
    var visible by mutableStateOf(false)
        private set
    // 对齐进度：(已处理行数, 待对齐行数)；null 表示尚在解码首行
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    // 对齐进行中
    var aligning by mutableStateOf(false)
        private set
    // 对齐失败：由首页错误横幅展示
    var failed by mutableStateOf(false)
        private set

    /** 对指定曲目发起逐字对齐；已有任务在跑时只重新展开进度，不重复发起。 */
    fun start(playbackState: MusicPlaybackState, track: MusicTrack) {
        if (aligning) {
            visible = true
            return
        }
        if (track.lyricLines.isEmpty()) return
        visible = true
        failed = false
        progress = null
        aligning = true
        scope.launch {
            val outcome = alignLyricsWords(context, playbackState, track) { done, total ->
                withContext(Dispatchers.Main) { progress = done to total }
            }
            when (outcome) {
                AlignOutcome.Failed -> failed = true
                // 没有可对齐的歌词行：提示而非报错
                AlignOutcome.NoTargets -> Toast.makeText(
                    context,
                    R.string.music_panel_word_align_no_target,
                    Toast.LENGTH_SHORT,
                ).show()
                AlignOutcome.Applied -> Unit
            }
            aligning = false
            progress = null
            visible = false
        }
    }

    fun dismiss() {
        visible = false
    }

    fun clearFailed() {
        failed = false
    }
}
