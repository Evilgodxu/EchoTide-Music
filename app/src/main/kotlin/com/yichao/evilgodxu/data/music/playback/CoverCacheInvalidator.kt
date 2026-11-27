package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CoverSkipRegistry
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.EmbeddedCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 封面写入后作废相关缓存，作废完成才自增版本号 */
internal class CoverCacheInvalidator(
    private val context: () -> Context?,
    private val colorMemory: BackgroundColorMemory,
) {

    // 封面写入成功后自增，通知封面组件强制重载最新封面
    var coverRevision by mutableIntStateOf(0)

    /** 全部作废后才推进版本号；整段不可取消 */
    suspend fun bumpCoverRevision() {
        SystemThumbnailCache.clear()
        EmbeddedCoverCache.clear()
        CoverSkipRegistry.clear()
        CoverColorCache.clear()
        // 持久化取色出自旧封面，一并作废
        colorMemory.clear()
        withContext(NonCancellable) {
            // 落盘封面同为旧图，一并清除
            context()?.let { context ->
                CurrentCoverCache.clear(context)
                LargeCoverStore.clear(context)
            }
        }
        coverRevision++
    }
}
