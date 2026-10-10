package com.yichao.evilgodxu.ui.component.player

import android.app.Activity
import android.bluetooth.BluetoothClass
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
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
import com.yichao.evilgodxu.data.music.playback.OutputDeviceKind
import com.yichao.evilgodxu.data.music.playback.OutputEncoding
import com.yichao.evilgodxu.data.music.playback.OutputLatencyReading
import com.yichao.evilgodxu.data.music.playback.OutputLatencySampler
import com.yichao.evilgodxu.data.music.playback.outputEncodingOf
import com.yichao.evilgodxu.permission.PermissionMonitor
import com.yichao.evilgodxu.permission.bluetoothConnectPermission
import com.yichao.evilgodxu.utils.formatMebibytes
import com.yichao.evilgodxu.utils.formatMegabytes
import kotlinx.coroutines.delay
import com.yichao.evilgodxu.R

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
    val latency by rememberOutputLatencyReading(playbackState)
    // 蓝牙设备名与真实地址都受授权限制：当前输出是蓝牙而名称读不到时，在展示处就地申请授权
    BluetoothConnectPermissionRequest(snapshot?.outputDevice)
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 快照未就绪（首次采集尚未返回）时不渲染任何内容，避免空态提示一闪而过
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
    // 换行才改为起始对齐：多行文本整行尾对齐阅读成本高，而单行值一律尾对齐——
    // 按文本长度判定会让未换行的长值（如解码器名）右侧空出一块
    var wrapped by remember(row.value) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                // 与播放列表「当前曲目」行同款底色：主题色淡染，全应用选中态统一
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

// 字段行文案：值为已格式化的展示文本
private data class AudioInfoRow(val label: String, val value: String)

// 字段分组文案
private data class AudioInfoGroup(val title: String, val rows: List<AudioInfoRow>)

// 组装四个分组的字段行；不可获取的字段不产出对应行
@Composable
private fun audioInfoGroups(
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
                            AudioOutputMode.FORMAT_LOCKED -> R.string.audio_info_output_mode_format_locked
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
                    pluralStringResource(
                        R.plurals.audio_info_value_frames_ms,
                        it.trackBufferFrames,
                        it.trackBufferFrames,
                        it.trackBufferMs,
                    ),
                )
            },
            latency?.afterTrackMs?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_latency),
                    stringResource(R.string.audio_info_value_ms, it),
                )
            },
            latency?.trackResidentMs?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_track_resident),
                    stringResource(R.string.audio_info_value_ms, it),
                )
            },
            latency?.fullChainMs?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_latency_full),
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

// 输出设备行：类型、名称、地址、支持格式与蓝牙链路各自成条，读不到的项不产出
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
            ?.let { AudioInfoRow(stringResource(R.string.audio_info_device_name), it) },
        device.address?.let {
            AudioInfoRow(stringResource(R.string.audio_info_device_address), it)
        },
        device.supportedSampleRates
            .takeIf { it.isNotEmpty() }
            ?.let {
                AudioInfoRow(
                    stringResource(R.string.audio_info_device_sample_rates),
                    stringResource(R.string.audio_info_value_hz_list, it.joinToString("/")),
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

// 设备类型名：便于一眼区分当前出口通路的类别
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

// 蓝牙链路类型：经典蓝牙承载 A2DP，低功耗蓝牙承载 LE Audio，双模两者兼有
@Composable
private fun bluetoothLinkTypeLabel(linkType: BluetoothLinkType): String = stringResource(
    when (linkType) {
        BluetoothLinkType.CLASSIC -> R.string.audio_info_bluetooth_type_classic
        BluetoothLinkType.LE -> R.string.audio_info_bluetooth_type_le
        BluetoothLinkType.DUAL -> R.string.audio_info_bluetooth_type_dual
    }
)

// 蓝牙编解码器声道模式：与音频参数的声道布局同义，复用同一组文案
@Composable
private fun bluetoothChannelModeLabel(mode: BluetoothChannelMode): String = stringResource(
    when (mode) {
        BluetoothChannelMode.MONO -> R.string.audio_info_value_mono
        BluetoothChannelMode.STEREO -> R.string.audio_info_value_stereo
    }
)

/**
 * 蓝牙设备类别：设备类字段由厂商声明，只译常见类别，其余留空。
 *
 * 未归类音视频设备与音视频大类共用同一编码，故前者一条即可覆盖大类。
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
    else -> pluralStringResource(R.plurals.audio_info_value_channels, channelCount, channelCount)
}

@Composable
private fun booleanLabel(value: Boolean): String = stringResource(
    if (value) R.string.audio_info_value_yes else R.string.audio_info_value_no
)

// 浮点写出状态：直述链路当前取向；输出未建立与未启用分开表述，前者说明尚未起播而非能力欠缺
@Composable
private fun floatOutputLabel(state: FloatOutputState): String = stringResource(
    when (state) {
        FloatOutputState.ENABLED -> R.string.audio_info_value_enabled
        FloatOutputState.DISABLED -> R.string.audio_info_value_disabled
        FloatOutputState.NOT_ESTABLISHED -> R.string.audio_info_value_not_established
    }
)

/**
 * 解码输出行：采样率 / 声道 / 位深三合一。
 *
 * 取解码头在重配那一刻上报的格式，与源格式可能不同；位深归不到线性 PCM（直通输出）时该行无位深可言，
 * 整行不产出。
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

// 输出编码：音频轨实际写出的 PCM 编码，位深与整型/浮点一并给出，与链路的浮点取向相互独立
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
 * 采集音频信息快照。
 *
 * 曲目、格式与输出链路状态均为 Compose 状态，变化即重算；传输状态、音频会话 ID 与输出设备
 * 不在 Compose 状态中，改由播放器、音频设备回调与回到前台三类事件驱动版本号重算。
 */
@OptIn(UnstableApi::class)
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

    // 授权变更不在设备回调覆盖范围内：回到前台后重新采集，使刚授予的权限立即反映到设备信息
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
        playbackState.audioSinkDecodedFormat,
        playbackState.directOutputMode,
        playerEventVersion,
        deviceEventVersion,
    ) {
        value = AudioInfoCollector.collect(context, playbackState)
    }
}

/**
 * 采样当前输出延迟。
 *
 * 延迟随链路持续波动，与本文件其余「随关键项变化才重算」的字段节奏不同，故独立按固定间隔采样，
 * 由 [OutputLatencySampler] 取滑动平均。音频轨是跨线程的易变引用、不是 Compose 状态，读取不会引发
 * 重组，故每次采样都重新取一次，换轨由采样器按实例身份发现并重建窗口；面板关闭即离开组合，采样随之停止。
 */
@Composable
private fun rememberOutputLatencyReading(
    playbackState: MusicPlaybackState,
): State<OutputLatencyReading?> =
    produceState<OutputLatencyReading?>(initialValue = null, playbackState) {
        var sampler = OutputLatencySampler()
        var sampledTrack: AudioTrack? = null
        while (true) {
            val track = playbackState.audioTrack
            if (track !== sampledTrack) {
                sampledTrack = track
                sampler = OutputLatencySampler()
            }
            value = sampler.sample(track, playbackState.audioSinkWrittenFrames)
            delay(OutputLatencySampler.SAMPLE_INTERVAL_MS)
        }
    }

/**
 * 蓝牙授权补申请。
 *
 * 远端设备名与真实地址都受 BLUETOOTH_CONNECT 保护，未授权时两者都读不到（地址还会被平台匿名化），
 * 故在当前输出是蓝牙且名称读不到时就地申请，省去用户自行去系统设置里翻找。
 * 每次打开面板至多申请一次，避免反复打扰。
 *
 * 悬浮窗宿主没有 Activity（系统授权对话框会落在悬浮窗之下，用户无从操作），此处不申请，
 * 该场景交由首页权限对话框覆盖。
 */
@Composable
private fun BluetoothConnectPermissionRequest(currentOutput: OutputDeviceInfo?) {
    val context = LocalContext.current
    // LocalContext 为本地化包装 context，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity ?: return
    if (currentOutput?.kind != OutputDeviceKind.BLUETOOTH || currentOutput.name != null) return
    val permissionMonitor = remember(context) { PermissionMonitor(context) }
    if (permissionMonitor.isBluetoothConnectGranted()) return

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 授权结果由回到前台后的重新采集体现，此处无需处理
    }
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(activity) {
        if (requested) return@LaunchedEffect
        requested = true
        permissionLauncher.launch(bluetoothConnectPermission())
    }
}
