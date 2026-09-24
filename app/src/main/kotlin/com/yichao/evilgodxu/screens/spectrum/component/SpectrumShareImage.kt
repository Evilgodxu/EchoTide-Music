package com.yichao.evilgodxu.screens.spectrum.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.yichao.evilgodxu.data.music.analysis.SPECTROGRAM_DYNAMIC_RANGE_DB
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import com.yichao.evilgodxu.utils.formatTime
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 导出图内容：图下方参数行与检测结果、左下角曲名与歌手、右下角免责说明。
// 文案由界面按当前语言产出后传入，渲染端不与资源耦合
internal class SpectrumShareContent(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val info: String,
    val verdicts: List<SpectrumShareVerdict>,
    val disclaimer: String,
)

// 导出图中的一段检测结果：告警段取告警色
internal class SpectrumShareVerdict(val text: String, val flagged: Boolean)

// 频谱图导出渲染：把时频图、频率刻度、dB 色标与底部信息排成一张固定宽度的高清图。
// 版面按导出图自身尺寸定（不随屏幕尺寸变化），绘图区按 3:2 铺开取更多频率与时间细节；
// 图内每一行与左轴刻度的换算沿用屏幕同一套 FrequencyAxisScale 与档位挑选规则，两处读数一致。
// 整体取深色版式：频谱图本身为深色，导出图与图内配色同源
internal object SpectrumShareImage {

    // 版面尺寸（px）：固定宽度，高度由内容行数决定
    private const val WIDTH = 1920
    private const val PAD = 48f

    // 各段字号：底行曲名与歌手同字号，主次由配色区分
    private const val CREDIT_TEXT_SIZE = 34f
    private const val LABEL_TEXT_SIZE = 30f
    private const val NOTE_TEXT_SIZE = 26f

    // 底行左端曲名与歌手之间的分隔符，及底行左右两端文案的最小间距
    private const val CREDIT_SEPARATOR = " · "
    private const val CREDIT_GAP = 24f

    // 绘图区版面：左右两侧分别留给频率刻度与色标
    private const val AXIS_LABEL_WIDTH = 160f
    private const val AXIS_GAP = 12f
    private const val SCALE_LABEL_WIDTH = 100f
    private const val BAR_WIDTH = 30f
    private const val BAR_GAP = 14f
    private const val PLOT_ASPECT = 3f / 2f

    // 刻度线伸出绘图区的长度与相邻标签的最小间距
    private const val TICK_LINE_LENGTH = 12f
    private const val TICK_MIN_GAP = 40f

    // 各段竖向间距
    private const val LINE_GAP = 14f
    private const val BLOCK_GAP = 24f

    // 导出图配色：深色底、浅色字。全图文字取同一色，层次只由字号给出；
    // 仅命中的告警段另取告警色
    private const val BACKGROUND = 0xFF0B0D14.toInt()
    private const val TEXT_COLOR = 0xFF9BA3B4.toInt()
    private const val TEXT_WARNING = 0xFFF28B82.toInt()
    private const val AXIS_COLOR = 0xFF6B7280.toInt()

    // 标签以字面竖向居中于给定位置时，基线相对中心的偏移（约半个字高）
    private const val BASELINE_CENTER_RATIO = 0.36f

    // 渲染导出图并编码为 PNG。重采样与绘制是纯计算，调用方须在后台线程调用
    suspend fun render(spectrogram: Spectrogram, content: SpectrumShareContent): ByteArray =
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().use { out ->
                draw(spectrogram, content).compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }

    // 版面与绘制：先按内容行数定出图高，再逐块绘制
    private fun draw(spectrogram: Spectrogram, content: SpectrumShareContent): Bitmap {
        val scale = FrequencyAxisScale.ofSampleRate(spectrogram.sampleRate)
        val plotLeft = PAD + AXIS_LABEL_WIDTH + AXIS_GAP
        val plotRight = WIDTH - PAD - SCALE_LABEL_WIDTH - BAR_WIDTH - BAR_GAP
        val plotWidth = (plotRight - plotLeft).toInt()
        val plotHeight = plotWidth / PLOT_ASPECT
        // 顶部不留标题区：曲名与歌手落在底行左端，图自顶缘留白起铺开
        val plotTop = PAD
        val plotBottom = plotTop + plotHeight

        // 曲名与歌手、刻度与参数行、检测结果、免责说明同取一种正文色，字号各自区分层次；
        // 告警段单独一支告警色画笔，绘制时不改写正文画笔
        val creditPaint = textPaint(CREDIT_TEXT_SIZE, TEXT_COLOR)
        val bodyPaint = textPaint(LABEL_TEXT_SIZE, TEXT_COLOR)
        val warningPaint = textPaint(LABEL_TEXT_SIZE, TEXT_WARNING)
        val notePaint = textPaint(NOTE_TEXT_SIZE, TEXT_COLOR)

        // 底部文案块自上而下排布：参数行与检测结果各占一行，空行不占位，
        // 末行为底行——左端曲名与歌手、右端免责说明，两端共用一条基线
        var y = plotBottom + LINE_GAP + lineHeight(bodyPaint) + BLOCK_GAP
        val infoBaseline = if (content.info.isNotBlank()) {
            baselineOfTop(bodyPaint, y).also { y += lineHeight(bodyPaint) + LINE_GAP }
        } else {
            null
        }
        val verdictBaseline = if (content.verdicts.isNotEmpty()) {
            baselineOfTop(bodyPaint, y).also { y += lineHeight(bodyPaint) + LINE_GAP }
        } else {
            null
        }
        // 底行行高按其中较大字号取，避免大字被下缘裁切
        val creditBaseline = baselineOfTop(creditPaint, y)
        val height = (y + maxOf(lineHeight(creditPaint), lineHeight(notePaint)) + PAD).toInt()

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        canvas.drawBitmap(
            renderSpectrogramBitmap(spectrogram, scale, plotWidth, plotHeight.toInt()),
            plotLeft,
            plotTop,
            null,
        )
        drawFrequencyAxis(canvas, scale, plotLeft, plotTop, plotBottom, plotHeight, bodyPaint)
        drawColorScale(canvas, plotRight, plotTop, plotBottom, plotHeight, bodyPaint)
        drawTimeLabels(canvas, plotLeft, plotRight, plotBottom, content.durationMs, bodyPaint)
        if (infoBaseline != null) {
            drawCentered(canvas, content.info, infoBaseline, bodyPaint)
        }
        if (verdictBaseline != null) {
            drawVerdicts(canvas, content.verdicts, verdictBaseline, bodyPaint, warningPaint)
        }
        val disclaimerWidth = notePaint.measureText(content.disclaimer)
        drawCredit(
            canvas = canvas,
            content = content,
            baseline = creditBaseline,
            maxWidth = WIDTH - PAD * 2 - disclaimerWidth - CREDIT_GAP,
            paint = creditPaint,
        )
        canvas.drawText(content.disclaimer, WIDTH - PAD - disclaimerWidth, creditBaseline, notePaint)
        return bitmap
    }

    // 底行左端：曲名与歌手同色相连，两者以分隔符相接；歌手名为空时只留曲名。
    // 可用宽度扣除了右端免责说明，超长时按曲名优先截断——先压歌手、再压曲名，避免两端文案相撞
    private fun drawCredit(
        canvas: Canvas,
        content: SpectrumShareContent,
        baseline: Float,
        maxWidth: Float,
        paint: TextPaint,
    ) {
        val separator = if (content.artist.isBlank()) "" else CREDIT_SEPARATOR
        val separatorWidth = paint.measureText(separator)
        val artistRoom = maxWidth - paint.measureText(content.title) - separatorWidth
        // 歌手仍有一席之地则截断歌手；连一席都没有时整串退化为截断后的曲名
        if (artistRoom <= 0f) {
            val title = TextUtils.ellipsize(content.title, paint, maxWidth, TextUtils.TruncateAt.END)
            canvas.drawText(title.toString(), PAD, baseline, paint)
            return
        }
        canvas.drawText(content.title, PAD, baseline, paint)
        if (separator.isEmpty()) return
        var x = PAD + paint.measureText(content.title)
        canvas.drawText(separator, x, baseline, paint)
        x += separatorWidth
        val artist = TextUtils.ellipsize(content.artist, paint, artistRoom, TextUtils.TruncateAt.END)
        canvas.drawText(artist.toString(), x, baseline, paint)
    }

    // 频率刻度：档位挑选与屏幕同一规则，标签右对齐到刻度线以左
    private fun drawFrequencyAxis(
        canvas: Canvas,
        scale: FrequencyAxisScale,
        plotLeft: Float,
        plotTop: Float,
        plotBottom: Float,
        plotHeight: Float,
        labelPaint: Paint,
    ) {
        val linePaint = strokePaint()
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, linePaint)
        layoutFrequencyTicks(
            ticks = frequencyTicks(scale),
            scale = scale,
            plotHeightPx = plotHeight,
            minGapPx = TICK_MIN_GAP,
        ).forEach { (tick, offsetY) ->
            val y = plotTop + offsetY
            canvas.drawLine(plotLeft - TICK_LINE_LENGTH, y, plotLeft, y, linePaint)
            val width = labelPaint.measureText(tick.label)
            canvas.drawText(
                tick.label,
                plotLeft - TICK_LINE_LENGTH - AXIS_GAP - width,
                y + LABEL_TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // dB 色标：渐变条自上而下由满强度降至量程下限，与屏幕色标同色板、同分档
    private fun drawColorScale(
        canvas: Canvas,
        plotRight: Float,
        plotTop: Float,
        plotBottom: Float,
        plotHeight: Float,
        labelPaint: Paint,
    ) {
        val barLeft = plotRight + BAR_GAP
        val barRight = barLeft + BAR_WIDTH
        val barPaint = Paint().apply {
            shader = LinearGradient(
                0f,
                plotTop,
                0f,
                plotBottom,
                SPECTRUM_COLOR_STOPS.reversedArray(),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(barLeft, plotTop, barRight, plotBottom, barPaint)
        for (step in 0..SCALE_INTERVALS) {
            val fraction = step.toFloat() / SCALE_INTERVALS
            val decibel = -SPECTROGRAM_DYNAMIC_RANGE_DB * fraction
            val label = if (decibel == 0f) "0" else decibel.toInt().toString()
            canvas.drawText(
                label,
                barRight + AXIS_GAP,
                plotTop + plotHeight * fraction + LABEL_TEXT_SIZE * BASELINE_CENTER_RATIO,
                labelPaint,
            )
        }
    }

    // 时间刻度：两端分别贴绘图区左右缘
    private fun drawTimeLabels(
        canvas: Canvas,
        plotLeft: Float,
        plotRight: Float,
        plotBottom: Float,
        durationMs: Long,
        labelPaint: Paint,
    ) {
        val baseline = plotBottom + LINE_GAP + LABEL_TEXT_SIZE
        canvas.drawText(formatTime(0L), plotLeft, baseline, labelPaint)
        val end = formatTime(durationMs)
        canvas.drawText(end, plotRight - labelPaint.measureText(end), baseline, labelPaint)
    }

    // 检测结果：多段文案作为一行整体居中，只有告警段取告警色，其余与正文同色
    private fun drawVerdicts(
        canvas: Canvas,
        verdicts: List<SpectrumShareVerdict>,
        baseline: Float,
        paint: TextPaint,
        warningPaint: TextPaint,
    ) {
        val total = verdicts.fold(0f) { acc, verdict -> acc + paint.measureText(verdict.text) }
        var x = WIDTH / 2f - total / 2f
        verdicts.forEach { verdict ->
            canvas.drawText(verdict.text, x, baseline, if (verdict.flagged) warningPaint else paint)
            x += paint.measureText(verdict.text)
        }
    }

    // 单行居中绘制
    private fun drawCentered(canvas: Canvas, text: String, baseline: Float, paint: Paint) {
        canvas.drawText(text, WIDTH / 2f - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun textPaint(textSize: Float, color: Int): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
        typeface = Typeface.DEFAULT
    }

    private fun strokePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_COLOR
        strokeWidth = 1.5f
    }

    // 字面高度与基线换算：绘制端按字面顶/中心定位，避免受字体度量细节影响
    private fun lineHeight(paint: Paint): Float = -paint.fontMetrics.ascent + paint.fontMetrics.descent

    private fun baselineOfTop(paint: Paint, top: Float): Float = top - paint.fontMetrics.ascent
}
