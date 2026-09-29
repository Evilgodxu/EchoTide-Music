package com.yichao.evilgodxu.ui.component.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.yichao.evilgodxu.data.music.playback.AudioInfoCollector
import com.yichao.evilgodxu.data.music.playback.AudioInfoSnapshot
import com.yichao.evilgodxu.data.music.playback.AudioOutputMode
import com.yichao.evilgodxu.data.music.playback.AudioTransportState
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.OutputDeviceInfo
import com.yichao.evilgodxu.data.music.playback.OutputDeviceKind
import com.yichao.evilgodxu.R

// 字段值超过该长度即改为起始对齐：设备信息与文件路径这类长文本换行后以尾对齐阅读成本高
private const val LONG_VALUE_LENGTH = 22

// 行内文本最大行数：超出以省略号截断，避免个别超长设备描述撑开整块面板
private const val VALUE_MAX_LINES = 3

/**
 * 音频信息内容：按分组渲染当前播放链路实际可获取的字段行。
 *
 * 采集不到的字段不产出对应行；滚动容器由宿主提供（[scrollState] 供宿主绑定下拉收起等手势），
 * 内容区自身只负责分组排版。
 */
@Composable
internal fun AudioInfoContent(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val snapshot by rememberAudioInfoSnapshot(playbackState)
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快照未就绪（首次采集尚未返回）时不渲染任何内容，避免空态提示一闪而过
        val collected = snapshot ?: return@Column
        val groups = audioInfoGroups(collected)
        if (groups.isEmpty()) {
            Text(
                text = stringResource(R.string.audio_info_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        } else {
            groups.forEach { group -> AudioInfoGroupView(group) }
        }
    }
}

// 单个分组：小标题 + 字段行
@Composable
private fun AudioInfoGroupView(group: AudioInfoGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = group.title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        group.rows.forEach { row -> AudioInfoRowView(row) }
    }
}

// 字段行：左侧字段名受主题弱化，右侧为取值
@Composable
private fun AudioInfoRowView(row: AudioInfoRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = VALUE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = row.value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            textAlign = if (row.value.length > LONG_VALUE_LENGTH) TextAlign.Start else TextAlign.End,
            maxLines = VALUE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1.3f)
                .padding(start = 8.dp),
        )
    }
}

// 字段行文案：值为已格式化的展示文本
private data class AudioInfoRow(val label: String, val value: String)

// 字段分组文案
private data class AudioInfoGroup(val title: String, val rows: List<AudioInfoRow>)

// 组装四个分组的字段行；不可获取的字段不产出对应行
@Composable
private fun audioInfoGroups(snapshot: AudioInfoSnapshot): List<AudioInfoGroup> = listOf(
    AudioInfoGroup(
        title = stringResource(R.string.audio_info_group_source),
        rows = listOfNotNull(
            snapshot.sourcePath?.let {
                AudioInfoRow(stringResource(R.string.audio_info_file_path), it)
            },
            snapshot.format?.let {
                AudioInfoRow(stringResource(R.string.audio_info_format), it)
            },
            snapshot.decoder?.let {
                AudioInfoRow(stringResource(R.string.audio_info_decoder), it)
            },
        ),
    ),
    AudioInfoGroup(
        title = stringResource(R.string.audio_info_group_parameters),
        rows = listOfNotNull(
            snapshot.sourceSampleRate?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_source_sample_rate),
                    stringResource(R.string.audio_info_value_hz, it),
                )
            },
            snapshot.outputSampleRate?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_output_sample_rate),
                    stringResource(R.string.audio_info_value_hz, it),
                )
            },
            snapshot.bitrateKbps?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_bitrate),
                    stringResource(R.string.audio_info_value_kbps, it),
                )
            },
            snapshot.channelCount?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_channel_layout),
                    channelLayoutLabel(it),
                )
            },
            snapshot.channelCount?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_channel_count),
                    stringResource(R.string.audio_info_value_channel_count, it),
                )
            },
            snapshot.resampled?.let {
                AudioInfoRow(stringResource(R.string.audio_info_resampled), booleanLabel(it))
            },
            qualityLabel(snapshot)?.let {
                AudioInfoRow(stringResource(R.string.audio_info_quality), it)
            },
        ),
    ),
    AudioInfoGroup(
        title = stringResource(R.string.audio_info_group_chain),
        rows = listOfNotNull(
            snapshot.outputMode?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_output_mode),
                    stringResource(
                        when (it) {
                            AudioOutputMode.BIT_PERFECT -> R.string.audio_info_output_mode_bit_perfect
                            AudioOutputMode.MIXER -> R.string.audio_info_output_mode_mixer
                        }
                    ),
                )
            },
            snapshot.audioSessionId?.let {
                AudioInfoRow(stringResource(R.string.audio_info_session_id), it.toString())
            },
            snapshot.floatOutput?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_float_output),
                    stringResource(
                        if (it) R.string.audio_info_value_supported
                        else R.string.audio_info_value_unsupported
                    ),
                )
            },
            snapshot.latencyMs?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_latency),
                    stringResource(R.string.audio_info_value_ms, it),
                )
            },
            snapshot.transportState?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_transport_state),
                    stringResource(
                        when (it) {
                            AudioTransportState.PLAYING -> R.string.audio_info_transport_playing
                            AudioTransportState.BUFFERING -> R.string.audio_info_transport_buffering
                            AudioTransportState.PAUSED -> R.string.audio_info_transport_paused
                            AudioTransportState.ENDED -> R.string.audio_info_transport_ended
                            AudioTransportState.IDLE -> R.string.audio_info_transport_idle
                        }
                    ),
                )
            },
        ),
    ),
    AudioInfoGroup(
        title = stringResource(R.string.audio_info_group_devices),
        rows = snapshot.outputDevices.mapNotNull { device ->
            deviceValueText(device)?.let {
                AudioInfoRow(
                    stringResource(
                        when (device.kind) {
                            OutputDeviceKind.USB -> R.string.audio_info_device_usb
                            OutputDeviceKind.BLUETOOTH -> R.string.audio_info_device_bluetooth
                            OutputDeviceKind.SPEAKER -> R.string.audio_info_device_speaker
                        }
                    ),
                    it,
                )
            }
        },
    ),
).filter { it.rows.isNotEmpty() }

// 输出设备取值：名称（受权限限制可能不可得）、地址、支持采样率与声道数按序拼接，各项缺失即跳过
@Composable
private fun deviceValueText(device: OutputDeviceInfo): String? = listOfNotNull(
    device.name,
    device.address?.let { stringResource(R.string.audio_info_value_address, it) },
    device.supportedSampleRates
        .takeIf { it.isNotEmpty() }
        ?.let { stringResource(R.string.audio_info_value_hz_list, it.joinToString("/")) },
    device.channelCount?.let { stringResource(R.string.audio_info_value_channels, it) },
).joinToString(" · ").takeIf { it.isNotBlank() }

// 品质：以格式判定有无损失，位深可得时一并展示（位深属源文件规格，与有无损失相互独立）
@Composable
private fun qualityLabel(snapshot: AudioInfoSnapshot): String? = when {
    snapshot.lossless == null -> null
    snapshot.lossless && snapshot.bitDepth != null ->
        stringResource(R.string.audio_info_quality_lossless_bit_depth, snapshot.bitDepth)
    snapshot.lossless -> stringResource(R.string.audio_info_quality_lossless)
    else -> stringResource(R.string.audio_info_quality_lossy)
}

// 声道布局：单声道与立体声按名称展示，其余按通道数展示
@Composable
private fun channelLayoutLabel(channelCount: Int): String = when (channelCount) {
    1 -> stringResource(R.string.audio_info_value_mono)
    2 -> stringResource(R.string.audio_info_value_stereo)
    else -> stringResource(R.string.audio_info_value_channels, channelCount)
}

@Composable
private fun booleanLabel(value: Boolean): String = stringResource(
    if (value) R.string.audio_info_value_yes else R.string.audio_info_value_no
)

/**
 * 采集音频信息快照。
 *
 * 曲目、格式与输出链路状态均为 Compose 状态，变化即重算；传输状态、音频会话 ID 与输出设备
 * 不在 Compose 状态中，改由播放器与音频设备回调驱动版本号重算。
 */
@Composable
private fun rememberAudioInfoSnapshot(playbackState: MusicPlaybackState): State<AudioInfoSnapshot?> {
    val context = LocalContext.current

    // 传输状态与音频会话 ID 由播放器回调驱动
    val player = playbackState.player
    var playerEventVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playerEventVersion++
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playerEventVersion++
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                playerEventVersion++
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    // 输出设备插拔由音频设备回调驱动
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    var deviceEventVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                deviceEventVersion++
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                deviceEventVersion++
            }
        }
        audioManager?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { audioManager?.unregisterAudioDeviceCallback(callback) }
    }

    return produceState(
        initialValue = null,
        playbackState.currentTrack?.id,
        playbackState.audioSignalPathFormat,
        playbackState.isAudioSignalPathCurrent,
        playbackState.audioDecoderName,
        playbackState.audioSinkFloatOutput,
        playbackState.bitPerfectOutputActive,
        playerEventVersion,
        deviceEventVersion,
    ) {
        value = AudioInfoCollector.collect(context, playbackState)
    }
}
