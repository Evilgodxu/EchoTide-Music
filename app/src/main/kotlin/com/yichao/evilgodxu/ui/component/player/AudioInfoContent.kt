package com.yichao.evilgodxu.ui.component.player

import android.app.Activity
import android.bluetooth.BluetoothClass
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yichao.evilgodxu.data.music.playback.AudioInfoCollector
import com.yichao.evilgodxu.data.music.playback.AudioInfoSnapshot
import com.yichao.evilgodxu.data.music.playback.AudioOutputMode
import com.yichao.evilgodxu.data.music.playback.AudioTransportState
import com.yichao.evilgodxu.data.music.playback.BluetoothChannelMode
import com.yichao.evilgodxu.data.music.playback.BluetoothLinkType
import com.yichao.evilgodxu.data.music.playback.DecodedOutputFormat
import com.yichao.evilgodxu.data.music.playback.FloatOutputState
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.OutputDeviceInfo
import com.yichao.evilgodxu.data.music.playback.ResampleState
import com.yichao.evilgodxu.data.music.playback.SampleRateSource
import com.yichao.evilgodxu.data.music.playback.OutputDeviceKind
import com.yichao.evilgodxu.data.music.playback.OutputEncoding
import com.yichao.evilgodxu.data.music.playback.OutputLatencyReading
import com.yichao.evilgodxu.data.music.playback.OutputLatencySampler
import com.yichao.evilgodxu.data.music.playback.outputEncodingOf
import com.yichao.evilgodxu.data.music.playback.usb.usbCardNameOf
import com.yichao.evilgodxu.permission.PermissionMonitor
import com.yichao.evilgodxu.permission.bluetoothConnectPermission
import com.yichao.evilgodxu.utils.formatMebibytes
import com.yichao.evilgodxu.utils.formatMegabytes
import com.yichao.evilgodxu.utils.formatSampleRateKHz
import kotlinx.coroutines.delay
import com.yichao.evilgodxu.R

// 取值最多 3 行，超出省略
private const val VALUE_MAX_LINES = 3

/**
 * 音频信息内容：按分组渲染可获取的字段；滚动容器由宿主提供
 */
@Composable
internal fun AudioInfoContent(
    snapshot: AudioInfoSnapshot?,
    latency: OutputLatencyReading?,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    // 面板打开即申请蓝牙权限
    BluetoothConnectPermissionRequest()
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快照未就绪时不渲染，避免闪现空态
        val collected = snapshot ?: return@Column
        val groups = audioInfoGroups(collected, latency)
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

// 字段行：左标签弱化，右取值
@Composable
private fun AudioInfoRowView(row: AudioInfoRow) {
    // 换行才改起始对齐，单行仍尾对齐
    var wrapped by remember(row.value) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // 与播放列表当前行同款淡染底
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
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
            textAlign = if (wrapped) TextAlign.Start else TextAlign.End,
            maxLines = VALUE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { wrapped = it.lineCount > 1 },
            modifier = Modifier
                .weight(1.3f)
                .padding(start = 8.dp),
        )
    }
}

internal data class AudioInfoRow(val label: String, val value: String)

internal data class AudioInfoGroup(val title: String, val rows: List<AudioInfoRow>)

// 组装四个分组，缺失字段不产出行
@Composable
internal fun audioInfoGroups(
    snapshot: AudioInfoSnapshot,
    latency: OutputLatencyReading?,
): List<AudioInfoGroup> = listOf(
    AudioInfoGroup(
        title = stringResource(R.string.audio_info_group_source),
        rows = listOfNotNull(
            snapshot.sourcePath?.let {
                AudioInfoRow(stringResource(R.string.audio_info_file_path), it)
            },
            snapshot.fileSizeBytes?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_file_size),
                    stringResource(
                        R.string.audio_info_value_file_size,
                        formatMegabytes(it),
                        formatMebibytes(it),
                    ),
                )
            },
            snapshot.format?.let {
                AudioInfoRow(stringResource(R.string.audio_info_format), it)
            },
            snapshot.decoder?.let {
                AudioInfoRow(stringResource(R.string.audio_info_decoder), it)
            },
            // 无解码器时此行代理解释缺失原因
            snapshot.decoderUnavailableFormat?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_decoder),
                    stringResource(R.string.audio_info_decoder_unavailable, it),
                )
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
            snapshot.resampleState?.let { state ->
                AudioInfoRow(
                    stringResource(R.string.audio_info_resampled),
                    when (state) {
                        ResampleState.NONE -> booleanLabel(false)
                        ResampleState.SYSTEM -> booleanLabel(true)
                        // 同族插值不算重采样，原样本逐位保留
                        ResampleState.SAME_FAMILY -> stringResource(R.string.audio_info_resample_same_family)
                    },
                )
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
                            AudioOutputMode.FORMAT_LOCKED -> R.string.audio_info_output_mode_format_locked
                            AudioOutputMode.EXCLUSIVE -> R.string.audio_info_output_mode_exclusive
                            AudioOutputMode.MIXER -> R.string.audio_info_output_mode_mixer
                        }
                    ),
                )
            },
            snapshot.audioSessionId?.let {
                AudioInfoRow(stringResource(R.string.audio_info_session_id), it.toString())
            },
            decodedOutputRow(snapshot.decodedOutput),
            snapshot.floatOutput?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_float_output),
                    floatOutputLabel(it),
                )
            },
            snapshot.outputEncoding?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_output_encoding),
                    outputEncodingLabel(it),
                )
            },
            latency?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_track_buffer),
                    bufferSizeLabel(it.trackBufferFrames, it.trackBufferMs),
                )
            },
            latency?.trackResidentMs?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_track_resident),
                    stringResource(R.string.audio_info_value_ms, it),
                )
            },
            latency?.afterTrackMs?.let {
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
        rows = outputDeviceRows(snapshot.outputDevice),
    ),
).filter { it.rows.isNotEmpty() }

// 输出设备行，读不到的项不产出
@Composable
private fun outputDeviceRows(device: OutputDeviceInfo?): List<AudioInfoRow> {
    if (device == null) return emptyList()
    val codec = device.bluetooth?.codec
    return listOfNotNull(
        AudioInfoRow(
            stringResource(R.string.audio_info_device_kind),
            outputDeviceKindLabel(device.kind),
        ),
        device.name
            ?.takeIf { it.isNotBlank() }
            ?.let { AudioInfoRow(stringResource(R.string.audio_info_device_name), deviceNameLabel(it)) },
        device.address?.let {
            AudioInfoRow(stringResource(R.string.audio_info_device_address), it)
        },
        device.supportedSampleRates
            .takeIf { it.isNotEmpty() }
            ?.let { rates ->
                val rateSource = when (device.sampleRateSource) {
                    SampleRateSource.DEVICE -> stringResource(R.string.audio_info_rate_source_device)
                    SampleRateSource.PLATFORM -> stringResource(R.string.audio_info_rate_source_platform)
                    null -> null
                }
                val list = rates.joinToString(" / ", transform = ::formatSampleRateKHz)
                AudioInfoRow(
                    stringResource(R.string.audio_info_device_sample_rates),
                    if (rateSource == null) {
                        stringResource(R.string.audio_info_value_khz_list, list)
                    } else {
                        stringResource(R.string.audio_info_value_khz_list_source, list, rateSource)
                    },
                )
            },
        device.supportedEncodings
            .takeIf { it.isNotEmpty() }
            ?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_device_encodings),
                    it.map { encoding -> outputEncodingLabel(encoding) }.joinToString(" / "),
                )
            },
        device.channelCount?.let {
            AudioInfoRow(
                stringResource(R.string.audio_info_device_channels),
                pluralStringResource(R.plurals.audio_info_value_channels, it, it),
            )
        },
        device.bluetooth?.linkType?.let {
            AudioInfoRow(stringResource(R.string.audio_info_bluetooth_type), bluetoothLinkTypeLabel(it))
        },
        codec?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { AudioInfoRow(stringResource(R.string.audio_info_bluetooth_codec), it) },
        codec?.sampleRateHz?.let {
            AudioInfoRow(
                stringResource(R.string.audio_info_bluetooth_codec_sample_rate),
                stringResource(R.string.audio_info_value_hz, it),
            )
        },
        codec?.bitsPerSample?.let {
            AudioInfoRow(
                stringResource(R.string.audio_info_bluetooth_codec_bits_per_sample),
                stringResource(R.string.audio_info_value_bit, it),
            )
        },
        codec?.channelMode?.let {
            AudioInfoRow(
                stringResource(R.string.audio_info_bluetooth_codec_channel_mode),
                bluetoothChannelModeLabel(it),
            )
        },
        device.bluetooth?.deviceClass
            ?.let { bluetoothDeviceClassLabel(it) }
            ?.let { AudioInfoRow(stringResource(R.string.audio_info_bluetooth_category), it) },
    )
}

// 剥掉 USB 声卡前缀，保留设备自称
private fun deviceNameLabel(name: String): String = usbCardNameOf(name).ifEmpty { name }

// 设备类型名
@Composable
private fun outputDeviceKindLabel(kind: OutputDeviceKind): String = stringResource(
    when (kind) {
        OutputDeviceKind.USB -> R.string.audio_info_device_usb
        OutputDeviceKind.BLUETOOTH -> R.string.audio_info_device_bluetooth
        OutputDeviceKind.SPEAKER -> R.string.audio_info_device_speaker
        OutputDeviceKind.WIRED -> R.string.audio_info_device_wired
        OutputDeviceKind.OTHER -> R.string.audio_info_device_other
    }
)

// 链路类型：经典、LE 或双模
@Composable
private fun bluetoothLinkTypeLabel(linkType: BluetoothLinkType): String = stringResource(
    when (linkType) {
        BluetoothLinkType.CLASSIC -> R.string.audio_info_bluetooth_type_classic
        BluetoothLinkType.LE -> R.string.audio_info_bluetooth_type_le
        BluetoothLinkType.DUAL -> R.string.audio_info_bluetooth_type_dual
    }
)

// 编解码器声道模式，复用声道文案
@Composable
private fun bluetoothChannelModeLabel(mode: BluetoothChannelMode): String = stringResource(
    when (mode) {
        BluetoothChannelMode.MONO -> R.string.audio_info_value_mono
        BluetoothChannelMode.STEREO -> R.string.audio_info_value_stereo
    }
)

/**
 * 只译常见设备类别，其余留空
 */
@Composable
private fun bluetoothDeviceClassLabel(deviceClass: Int): String? {
    val labelRes = when (deviceClass) {
        BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ->
            R.string.audio_info_bluetooth_class_wearable_headset
        BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> R.string.audio_info_bluetooth_class_handsfree
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> R.string.audio_info_bluetooth_class_loudspeaker
        BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> R.string.audio_info_bluetooth_class_headphones
        BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> R.string.audio_info_bluetooth_class_portable_audio
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> R.string.audio_info_bluetooth_class_car_audio
        BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> R.string.audio_info_bluetooth_class_hifi_audio
        BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED -> R.string.audio_info_bluetooth_class_audio_video
        BluetoothClass.Device.Major.COMPUTER -> R.string.audio_info_bluetooth_class_computer
        BluetoothClass.Device.Major.PHONE -> R.string.audio_info_bluetooth_class_phone
        BluetoothClass.Device.Major.WEARABLE -> R.string.audio_info_bluetooth_class_wearable
        else -> return null
    }
    return stringResource(labelRes)
}

// 按格式判有无损失，位深可得时一并展示
@Composable
private fun qualityLabel(snapshot: AudioInfoSnapshot): String? = when {
    snapshot.lossless == null -> null
    snapshot.lossless && snapshot.bitDepth != null ->
        stringResource(R.string.audio_info_quality_lossless_bit_depth, snapshot.bitDepth)
    snapshot.lossless -> stringResource(R.string.audio_info_quality_lossless)
    else -> stringResource(R.string.audio_info_quality_lossy)
}

// 单声道、立体声按名，其余按通道数
@Composable
private fun channelLayoutLabel(channelCount: Int): String = when (channelCount) {
    1 -> stringResource(R.string.audio_info_value_mono)
    2 -> stringResource(R.string.audio_info_value_stereo)
    else -> pluralStringResource(R.plurals.audio_info_value_channels, channelCount, channelCount)
}

@Composable
private fun booleanLabel(value: Boolean): String = stringResource(
    if (value) R.string.audio_info_value_yes else R.string.audio_info_value_no
)

// 帧数与时长并列，便于对照
@Composable
private fun bufferSizeLabel(frames: Int, millis: Float): String =
    pluralStringResource(R.plurals.audio_info_value_frames_ms, frames, frames, millis)

// 浮点写出状态；未建立与未启用分开表述
@Composable
private fun floatOutputLabel(state: FloatOutputState): String = stringResource(
    when (state) {
        FloatOutputState.ENABLED -> R.string.audio_info_value_enabled
        FloatOutputState.DISABLED -> R.string.audio_info_value_disabled
        FloatOutputState.NOT_ESTABLISHED -> R.string.audio_info_value_not_established
    }
)

/**
 * 解码输出三合一：采样率、声道、位深；直通时无位深不产出
 */
@Composable
private fun decodedOutputRow(format: DecodedOutputFormat?): AudioInfoRow? {
    val encoding = format?.let { outputEncodingOf(it.pcmEncoding) } ?: return null
    return AudioInfoRow(
        stringResource(R.string.audio_info_decoded_output),
        stringResource(
            R.string.audio_info_value_decoded_output,
            stringResource(R.string.audio_info_value_hz, format.sampleRate),
            channelLayoutLabel(format.channelCount),
            outputEncodingLabel(encoding),
        ),
    )
}

// 实际写出的 PCM 编码，含位深与整型/浮点
@Composable
private fun outputEncodingLabel(encoding: OutputEncoding): String = stringResource(
    when (encoding) {
        OutputEncoding.PCM_8BIT -> R.string.audio_info_value_pcm_8bit
        OutputEncoding.PCM_16BIT -> R.string.audio_info_value_pcm_16bit
        OutputEncoding.PCM_24BIT -> R.string.audio_info_value_pcm_24bit
        OutputEncoding.PCM_32BIT -> R.string.audio_info_value_pcm_32bit
        OutputEncoding.PCM_FLOAT -> R.string.audio_info_value_pcm_float
        OutputEncoding.NOT_ESTABLISHED -> R.string.audio_info_value_not_established
    }
)

/**
 * 采集音频快照，非状态项由事件版本号驱动
 */
@OptIn(UnstableApi::class)
@Composable
internal fun rememberAudioInfoSnapshot(playbackState: MusicPlaybackState): State<AudioInfoSnapshot?> {
    val context = LocalContext.current

    // 传输状态与会话 ID 由播放器回调驱动
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

    // 设备插拔由音频设备回调驱动
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

    // 回前台重采，反映刚授予的权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceEventVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return produceState(
        initialValue = null,
        playbackState.currentTrack?.id,
        playbackState.audioSignalPathFormat,
        playbackState.isAudioSignalPathCurrent,
        playbackState.audioDecoderName,
        playbackState.audioSinkFloatOutput,
        playbackState.audioSinkOutputEncoding,
        // 同族换档编码可能不变，速率单独作键
        playbackState.audioSinkOutputSampleRate,
        playbackState.audioSinkDecodedFormat,
        playbackState.directOutputMode,
        // 独占路由与设备不由平台给出，变化即重算
        playbackState.usbExclusiveRoute,
        playbackState.usbExclusiveDevice,
        playerEventVersion,
        deviceEventVersion,
    ) {
        value = AudioInfoCollector.collect(context, playbackState)
    }
}

/**
 * 按固定间隔采样输出延迟，取滑动平均。
 * 换轨或换来源即重建窗口；面板关闭即停止。
 */
@Composable
internal fun rememberOutputLatencyReading(
    playbackState: MusicPlaybackState,
): State<OutputLatencyReading?> =
    produceState<OutputLatencyReading?>(initialValue = null, playbackState) {
        var sampler = OutputLatencySampler()
        var sampledSource: Any? = null
        while (true) {
            val provided = playbackState.usbExclusiveLatency?.invoke()
            val source: Any? = if (provided != null) ExclusiveLatencySource else playbackState.audioTrack
            if (source !== sampledSource) {
                sampledSource = source
                sampler = OutputLatencySampler()
            }
            value = provided?.let { sampler.sampleProvided(it) }
                ?: sampler.sample(playbackState.audioTrack, playbackState.audioSinkWrittenFrames)
            delay(OutputLatencySampler.SAMPLE_INTERVAL_MS)
        }
    }

/** 采样来源标记，区分独占与音频轨 */
private object ExclusiveLatencySource

/**
 * 面板打开时申请蓝牙授权，每次至多一次。
 * 悬浮窗无 Activity 时不申请，留待主界面授权。
 */
@Composable
private fun BluetoothConnectPermissionRequest() {
    val context = LocalContext.current
    // Activity 需从注册表所有者取
    val activity = LocalActivityResultRegistryOwner.current as? Activity ?: return
    val permissionMonitor = remember(context) { PermissionMonitor(context) }
    if (permissionMonitor.isBluetoothConnectGranted()) return

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 结果由回前台重采体现，此处无需处理
    }
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (requested) return@LaunchedEffect
        requested = true
        permissionLauncher.launch(bluetoothConnectPermission())
    }
}
