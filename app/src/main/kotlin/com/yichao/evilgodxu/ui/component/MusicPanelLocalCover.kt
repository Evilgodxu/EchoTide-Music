package com.yichao.evilgodxu.ui.component

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.RecentCover
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadRecentCovers(context: Context): List<RecentCover> = withContext(Dispatchers.IO) {
    val result = mutableListOf<RecentCover>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID),
        null,
        null,
        "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext() && result.size < 10) {
            val id = cursor.getLong(idIndex)
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
            if (context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    BitmapFactory.decodeFileDescriptor(fd.fileDescriptor)?.apply { recycle() } != null
                } == true) {
                result += RecentCover(uri, id)
            }
        }
    }
    result
}

internal suspend fun applyLocalCover(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    cover: RecentCover,
): Boolean = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(cover.uri)?.use { it.readBytes() } ?: return@withContext false
        val writeSuccess = MusicMetadataWriter.writeCover(context, track, bytes)
        if (!writeSuccess) return@withContext false
        val path = MusicMetadataCache.saveCover(context, track.title, track.artist, bytes) ?: return@withContext false
        // 旧索引下的文件若已无引用，由扫描后的窗口回收统一处理（连续数天无引用才删），避免误删共享的封面
        withContext(Dispatchers.Main) {
            // 封面已就位，清掉此前的失败标记：否则「文件里有封面、缓存也在」却仍被标记为失败，
            // 一旦缓存文件被删就再也不会重建
            playbackState.updateTrack(
                track.copy(coverCachePath = path, neteaseCoverUrl = "", coverFailed = false)
            )
            playbackState.bumpCoverRevision()
            playbackState.setLocalCoverCandidates(emptyList())
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelLocalCover", "应用本地封面失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    }
}

@Composable
internal fun LocalCoverOverlay(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    selected: RecentCover?,
    saving: Boolean,
    onSelected: (RecentCover) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = .97f)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LocalCoverContent(
            playbackState = playbackState,
            selected = selected,
            saving = saving,
            onSelected = onSelected,
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun LocalCoverDialog(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    selected: RecentCover?,
    saving: Boolean,
    onSelected: (RecentCover) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!visible) return
    DialogCard(onDismiss = onCancel) {
        LocalCoverContent(
            playbackState = playbackState,
            selected = selected,
            saving = saving,
            onSelected = onSelected,
            onConfirm = onConfirm,
            onCancel = onCancel,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// 本地封面共享主体：标题 + 候选列表 + 按钮，供全屏蒙层与对话框复用
@Composable
private fun LocalCoverContent(
    playbackState: MusicPlaybackState,
    selected: RecentCover?,
    saving: Boolean,
    onSelected: (RecentCover) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.music_panel_local_cover), color = MaterialTheme.colorScheme.onSurface)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(playbackState.localCoverCandidates, key = { it.id }) { cover ->
                Box(
                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).clickable { onSelected(cover) },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = cover.uri,
                        contentDescription = stringResource(R.string.music_panel_local_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (cover == selected) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                onClick = onCancel
            ) {
                Text(
                    stringResource(R.string.music_panel_rename_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected != null && !saving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                onClick = { if (selected != null && !saving) onConfirm() }
            ) {
                Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.music_panel_rename_confirm),
                        color = if (saving) Color.Transparent else if (selected != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
