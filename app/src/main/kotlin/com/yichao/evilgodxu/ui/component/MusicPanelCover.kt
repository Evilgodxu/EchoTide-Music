package com.yichao.evilgodxu.ui.component

import android.content.Context
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.DiscArt
import com.yichao.evilgodxu.ui.copyToClipboard
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CurrentCover(
    track: MusicTrack?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onOnlineCover: () -> Unit = {},
    onLocalCover: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (track != null) showMenu = true }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 复用光碟效果：旋转 + 黑胶质感，与迷你播放器一致
        DiscArt(
            track = track,
            isPlaying = isPlaying,
            modifier = Modifier.size(64.dp)
        )
        CoverContextMenu(
            visible = showMenu,
            onOnlineCover = {
                showMenu = false
                onOnlineCover()
            },
            onLocalCover = {
                showMenu = false
                onLocalCover()
            },
            onDismiss = { showMenu = false }
        )
    }
}

/**
 * 封面缓存文件（磁盘缓存兜底）的加载模型，供面板/列表封面与沉浸背景共用。
 *
 * 封面缓存按「标题 - 艺术家」索引命名，换封面即覆盖同名文件——路径不变而内容已变，
 * 仅以路径作缓存键会让 Coil 一直命中旧图，故以 路径 + 封面写入版本号 作键；版本号是全局的，
 * 任一封面重写都会让可见封面重新解码，与首页大封面的重载口径一致。
 * 无路径或文件不可用时返回 null，交由调用方回退系统略缩图 / 在线原图 / 占位符
 */
internal fun coverModel(context: Context, track: MusicTrack?, coverRevision: Int): ImageRequest? {
    return track?.coverCachePath
        ?.takeIf { MusicMetadataCache.isValid(it) }
        ?.let { path ->
            val key = "$path@$coverRevision"
            ImageRequest.Builder(context)
                .data(File(path))
                .memoryCacheKey(key)
                .diskCacheKey(key)
                .build()
        }
}

// 系统略缩图即时加载：MediaStore 索引曲目直接走 OS 常驻略缩图缓存（首帧命中），
// 不等待全量解析器逐首读内嵌→解码→转码→落盘；非索引曲目回退磁盘缓存/占位符。
// 封面加载顺序：系统略缩图 → 磁盘缓存/在线原图 → 占位符；在线曲目仍等封面缓存落盘后再展示，
// 避免开始播放即请求在线封面地址。
// [thumbnailSize] 按显示尺寸适配：列表行 256px，音乐面板/轮播/迷你播放器 512px。
@Composable
private fun SystemCoverArt(
    track: MusicTrack?,
    modifier: Modifier,
    thumbnailSize: Int,
    placeholderIconSize: Dp,
) {
    val context = LocalContext.current
    val stateHolder = LocalMusicPanelStateHolder.current
    val audioUri = track?.audioUri
    val indexed = audioUri != null && audioUri.startsWith("content://media/")
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        audioUri,
    ) {
        value = if (indexed) {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        Uri.parse(audioUri),
                        Size(thumbnailSize, thumbnailSize),
                        null,
                    ).asImageBitmap()
                }.getOrNull()
            }
        } else null
    }
    // 磁盘缓存兜底：非 MediaStore 索引曲目 / 系统略缩图读取失败时使用既有缓存或在线原图。
    // 封面写入版本号参与模型键：重新写入封面后路径可能不变，需据此重建模型才能重载
    val coverRevision = stateHolder.state.coverRevision
    val model = remember(track?.id, track?.coverCachePath, track?.neteaseCoverUrl, coverRevision) {
        coverModel(context, track, coverRevision)
    }
    // 封面缺失时按需补全：幂等，补全成功后回写 coverCachePath 驱动重组重新加载；
    // 仅 MediaStore 索引曲目不再依赖此回填来显示略缩图，但仍需其为大封面/歌词补全
    LaunchedEffect(track?.id, track?.coverCachePath, track?.neteaseCoverUrl, track?.coverFailed) {
        track?.let { stateHolder.state.requestMetadata(it) }
    }
    when {
        thumb != null -> Image(
            bitmap = thumb!!,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
        model != null -> AsyncImage(
            model = model,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
        else -> Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(placeholderIconSize)
            )
        }
    }
}

@Composable
internal fun AlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 音乐面板封面（DiscArt 迷你播放器、刷新预览、轮播）：走系统略缩图即时出图；
    // 轮播居中封面最大约面板高度 55%，用 512px 请求保证清晰度，仍远轻于全量内嵌解码
    SystemCoverArt(track, modifier, thumbnailSize = 512, placeholderIconSize = 24.dp)
}

@Composable
internal fun PlaylistArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    // 列表行略缩图很小，256px 系统略缩图已足够
    SystemCoverArt(track, modifier, thumbnailSize = 256, placeholderIconSize = 12.dp)
}

// 长按菜单定位：水平居中于父布局，纵向紧贴父布局顶部或底部
@Composable
internal fun menuEdgePositionProvider(atTop: Boolean): PopupPositionProvider {
    val density = LocalDensity.current
    return remember(density, atTop) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val gapPx = with(density) { 2.dp.roundToPx() }
                val y = if (atTop) {
                    anchorBounds.top - popupContentSize.height - gapPx
                } else {
                    anchorBounds.bottom + gapPx
                }
                return IntOffset(
                    x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
                    y = y,
                )
            }
        }
    }
}

@Composable
internal fun CoverContextMenu(
    visible: Boolean,
    onOnlineCover: () -> Unit,
    onLocalCover: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (visible) {
        Popup(
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
            popupPositionProvider = menuEdgePositionProvider(atTop = false),
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                Row(horizontalArrangement = Arrangement.Center) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onOnlineCover) {
                        Text(
                            text = stringResource(R.string.music_panel_online_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color.Transparent, onClick = onLocalCover) {
                        Text(
                            text = stringResource(R.string.music_panel_local_cover),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MiniContextMenu(
    visible: Boolean,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
    onSearch: (() -> Unit)? = null,
) {
    if (visible) {
        Popup(
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            onDismissRequest = onDismiss,
            popupPositionProvider = menuEdgePositionProvider(atTop = true),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onCopy
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_copy),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Transparent,
                        onClick = onRename
                    ) {
                        Text(
                            text = stringResource(R.string.music_panel_rename),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    if (onSearch != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            onClick = onSearch
                        ) {
                            Text(
                                text = stringResource(R.string.music_panel_menu_search),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrackInfo(
    playbackState: MusicPlaybackState,
    onClick: () -> Unit = {},
    onRenameRequest: ((isTitle: Boolean, text: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var menuText by remember { mutableStateOf("") }
    var menuIsTitle by remember { mutableStateOf(true) }

    val onLongClickTitle: () -> Unit = {
        val track = playbackState.currentTrack
        if (track != null) {
            menuText = track.title
            menuIsTitle = true
            showMenu = true
        }
    }
    val onLongClickArtist: () -> Unit = {
        val artist = playbackState.currentTrack?.artist
        if (!artist.isNullOrBlank()) {
            menuText = artist
            menuIsTitle = false
            showMenu = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (!showMenu) onClick() },
                onLongClick = onLongClickTitle
            )
            .padding(top = 4.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val title = when {
            playbackState.currentTrack != null -> playbackState.currentTrack!!.title
            playbackState.isScanning -> stringResource(R.string.music_panel_scanning)
            else -> stringResource(R.string.music_panel_empty)
        }
        Box {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .basicMarquee(iterations = Int.MAX_VALUE)
                    .combinedClickable(
                        onClick = { if (!showMenu) onClick() },
                        onLongClick = onLongClickTitle
                    )
            )
        }
        Box {
            Text(
                text = playbackState.currentTrack?.artist ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = { if (!showMenu) onClick() },
                    onLongClick = onLongClickArtist
                )
            )
        }
        // 长按菜单锚定本列：显示在父布局顶部
        MiniContextMenu(
            visible = showMenu && menuIsTitle,
            onCopy = {
                showMenu = false
                copyToClipboard(context, menuText)
            },
            onRename = {
                showMenu = false
                onRenameRequest?.invoke(menuIsTitle, menuText)
            },
            onDismiss = { showMenu = false }
        )
        MiniContextMenu(
            visible = showMenu && !menuIsTitle,
            onCopy = {
                showMenu = false
                copyToClipboard(context, menuText)
            },
            onRename = {
                showMenu = false
                onRenameRequest?.invoke(menuIsTitle, menuText)
            },
            onDismiss = { showMenu = false }
        )
    }
}
