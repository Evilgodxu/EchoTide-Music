package com.yichao.evilgodxu.data.cache

import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登记表一致性复核：存储页的每一行都由 [StorageRegistry] 决定，登记表写错只会表现为「某行占用为 0」
 * 或「同一份产出占两行」，二者在界面上都不像错误，运行时也发现不了，故放在构建期把关。
 *
 * 改动登记表（新增分类、新增落点声明、调整条目）时若漏了配套改动，这里直接失败。
 */
class StorageRegistryTest {

    // 名称即落盘文件名：两份数据重名会读写同一个文件，统计上也只会剩一行
    @Test
    fun `具名持久化数据不重名`() {
        val declared = declaredData()
        assertEquals(declared.size, declared.map { it.name }.toSet().size)
        assertEquals(declared.size, declared.map { it.fileName }.toSet().size)
    }

    // 一个分类对应存储页的一行：同一分类在具名条目里出现两次，等于同一类产出被拆成两行
    @Test
    fun `具名条目分类不重复`() {
        val categories = StorageRegistry.entries.map { it.category }
        assertEquals(categories.size, categories.toSet().size)
    }

    // 漏一行不会报错，只会让某类产出凭空消失：分类必须要么有条目、要么有落点根兜着
    @Test
    fun `每个分类都有条目或落点根`() {
        val covered = (StorageRegistry.entries.map { it.category } + StorageRegistry.roots.map { it.category }).toSet()
        assertEquals(CacheCategory.entries.toSet(), covered)
    }

    // 兜底分类只用于兜底：若同时被当成具名条目登记，同一份产出会既算条目又算兜底
    @Test
    fun `兜底分类不作为具名条目登记`() {
        val catchAll = StorageRegistry.roots.map { it.category }.toSet()
        val registered = StorageRegistry.entries.map { it.category }.filter { it in catchAll }
        assertTrue("兜底分类被当成具名条目登记：$registered", registered.isEmpty())
    }

    // 落盘文件名由形态推导，读写与统计都按它定位文件，推导错会让统计永远读到空
    @Test
    fun `落盘文件名按形态推导`() {
        assertEquals("playlists.xml", PersistedData("playlists", PersistedFileKind.SHARED_PREFS).fileName)
        assertEquals(
            "settings.preferences_pb",
            PersistedData("settings", PersistedFileKind.DATA_STORE).fileName,
        )
        assertEquals(
            "highlight_segments.json",
            PersistedData("highlight_segments.json", PersistedFileKind.APP_FILE).fileName,
        )
    }

    private fun declaredData(): List<PersistedData> = StorageRegistry.entries.mapNotNull { it.persisted }
}