package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 全曲分析产物：渲染矩阵与两路判定结论。结论为 null 表示该维度不适用——
// 音质异常仅对 FLAC 判定，无本地路径的曲目两路都不适用
internal class FullSpectrumVerdict(
    val spectrogram: Spectrogram?,
    val fakeLossless: Boolean?,
    val aiMusic: Boolean?,
)

// 全曲分析入口：用户在频谱页查看该曲时执行，是「音质判定以完整频谱分析为准」的唯一产出点。
// 一次解码同时得到渲染矩阵与覆盖整曲的判定摘要，再以与曲库分析完全相同的判据得出两路结论：
// 结论写入两路共用判定缓存（歌单过滤与曲库统计随即复用），并把该曲登入锁定表，
// 使曲库分析的分段快速采样不再改写它。
// 解码不可用时不写缓存也不锁定，该曲仍由曲库分析按分段采样处理
internal object FullSpectrumAnalyzer {

    suspend fun analyze(
        context: Context,
        track: MusicTrack,
        onProgress: (Float) -> Unit = {},
    ): FullSpectrumVerdict {
        // 全曲解码是 CPU 重活：不占用调用方（界面）线程
        val decoded = withContext(Dispatchers.Default) {
            SpectrogramDecoder.decode(track, onProgress)
        }
        val spectrogram = decoded.spectrogram
        val summary = decoded.summary
        // 无矩阵即音频不可解码，无摘要即未累计到任何分析帧：此两种情形都不予采信，按不适用处理
        if (spectrogram == null || summary == null) {
            return FullSpectrumVerdict(spectrogram, null, null)
        }
        val (fake, ai) = withContext(Dispatchers.IO) {
            val fakeVerdict = FakeLosslessAnalyzer.recordFullAnalysisVerdict(context, track, summary)
            val aiVerdict = AiMusicAnalyzer.recordFullAnalysisVerdict(context, track, summary)
            // 结论写入后随即锁定：任一维度适用即锁定，与「用户查看过频谱」一一对应
            if (fakeVerdict != null || aiVerdict != null) {
                TrackAudioInfoReader.readFileSize(context, track)?.let { sizeBytes ->
                    FullAnalysisLock.lock(context, track, sizeBytes)
                }
            }
            fakeVerdict to aiVerdict
        }
        return FullSpectrumVerdict(spectrogram, fake, ai)
    }
}
