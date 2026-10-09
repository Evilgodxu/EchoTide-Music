package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.PlaylistArt

// 新建歌单弹窗，创建成功后回调返回新歌单
@Composable
internal fun CreatePlaylistDialog(
    visible: Boolean,
    onCreated: (Playlist) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    var name by remember { mutableStateOf("") }
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_create_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            val created = playlistStore.create(context, trimmed)
                            if (created != null) onCreated(created)
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.playlist_create_confirm),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// 重命名歌单弹窗
@Composable
internal fun RenamePlaylistDialog(
    playlist: Playlist?,
    onDismiss: () -> Unit,
) {
    if (playlist == null) return
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    var name by remember(playlist.id) { mutableStateOf(playlist.name) }
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_rename_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            playlistStore.rename(context, playlist.id, trimmed)
                            onDismiss()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_confirm),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// 删除歌单确认弹窗
@Composable
internal fun DeletePlaylistDialog(
    playlist: Playlist?,
    onDismiss: () -> Unit,
) {
    if (playlist == null) return
    val context = LocalContext.current
    val playlistStore = LocalPlaylistStore.current
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_delete_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.playlist_delete_message, playlist.name),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        playlistStore.delete(context, playlist.id)
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.playlist_delete_confirm),
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// 手动选曲弹窗：全量曲目多选后加入歌单
@Composable
internal fun AddSongsPicker(
    visible: Boolean,
    playlistName: String,
    candidateTracks: List<MusicTrack>,
    existingIds: Set<Long>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    val selectable = candidateTracks.filterNot { it.id in existingIds }
    // 按标题/艺术家关键词实时筛选候选曲目
    val filtered = remember(selectable, query) {
        val q = query.trim()
        if (q.isEmpty()) selectable
        else selectable.filter { it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true) }
    }
    val allSelected = filtered.isNotEmpty() && filtered.all { it.id in selected }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.425f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.playlist_picker_title, playlistName),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 用紧凑点击区替代 Material 图标按钮：其固定 48dp 触控高度会把标题行撑高，在标题上下留下大片留白
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (selectable.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.playlist_picker_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            } else {
                // 搜索输入框：按标题/艺术家关键词筛选候选曲目
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        imageVector = AppIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .size(16.dp)
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 28.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp
                        ),
                        decorationBox = { innerTextField ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.music_panel_search_placeholder),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        fontSize = 12.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { query = "" },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(24.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.music_panel_search_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filtered, key = { it.audioUri }) { track ->
                            SelectableTrackRow(
                                track = track,
                                checked = track.id in selected,
                                onToggle = { checked ->
                                    selected = if (checked) selected + track.id else selected - track.id
                                },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.playlist_picker_selected,
                                selected.size,
                                selected.size,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        // 用紧凑点击区替代 Material 文字按钮：其固定 48dp 触控高度会把底栏撑得过高
                        PickerAction(
                            text = stringResource(
                                if (allSelected) R.string.playlist_picker_select_none else R.string.playlist_picker_select_all
                            ),
                            enabled = true,
                            onClick = {
                                selected = if (allSelected) emptySet() else filtered.map { it.id }.toSet()
                            },
                        )
                        PickerAction(
                            text = stringResource(R.string.playlist_picker_add, selected.size),
                            enabled = selected.isNotEmpty(),
                            onClick = { onConfirm(selected.toList()) },
                        )
                    }
                }
            }
        }
    }
}

// 选曲弹窗底栏的紧凑文字操作：无固定触控高度，按文案 + 内边距撑开，避免 Material 文字按钮把底栏撑高
@Composable
private fun PickerAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// 选曲行：封面 + 标题/艺术家 + 选中标识，几何与排版对齐歌单列表行
@Composable
private fun SelectableTrackRow(
    track: MusicTrack,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaylistArt(
            track = track,
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 选中态用主色对勾，与加入歌单行的归属标识一致；未选中留出等宽占位，
        // 避免勾选切换时标题的可排版宽度变化导致文本跳动
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
