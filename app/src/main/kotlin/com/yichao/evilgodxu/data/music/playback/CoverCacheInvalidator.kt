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

/**
 * 封面写入成功后的缓存作废与版本号推进。
 *
 * 旧图、旧色调与旧结论均已失效：系统略缩图、内嵌封面、大封面、取色缓存、落盘封面与
 * 「无封面可取」的跳过标记一并清除；全部作废完成后才自增版本号。
 */
internal class CoverCacheInvalidator(
    private val context: () -> Context?,
    private val colorMemory: BackgroundColorMemory,
) {

    // 封面写入成功后自增，通知封面组件强制重载最新封面
    var coverRevision by mutableIntStateOf(0)

    /**
     * 全部作废完成后才自增版本号：显示端的重新取图由版本号驱动，若把落盘档的清除丢到后台与版本号并发，
     * 重新取图会命中尚未删净的旧档（大封面的内存驻留被逐出后正好回落到这一档），封面停在旧图不再刷新。
     * 整段置于 NonCancellable：作废是「新封面已写入」的必然结论，不随调用方是否还在而取舍。
     */
    suspend fun bumpCoverRevision() {
        SystemThumbnailCache.clear()
        EmbeddedCoverCache.clear()
        CoverSkipRegistry.clear()
        CoverColorCache.clear()
        // 持久化的取色结果同样出自被重写的封面：一并作废，避免冷启动仍按旧色调铺底；
        // 新色调由显示端或切歌预取重取后重新落盘
        colorMemory.clear()
        withContext(NonCancellable) {
            // 落盘封面同样是旧图：一并作废，避免冷启动拿旧封面顶出（新封面由下次切歌重新落盘）
            context()?.let { context ->
                CurrentCoverCache.clear(context)
                LargeCoverStore.clear(context)
            }
        }
        coverRevision++
    }
}
