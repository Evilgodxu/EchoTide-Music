package com.yichao.evilgodxu.data.cache

import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登记表一致性：写错只会静默出错 */
class StorageRegistryTest {

    // 名称即文件名，重名会读写同一文件
    @Test
    fun `具名持久化数据不重名`() {
        val declared = declaredData()
        assertEquals(declared.size, declared.map { it.name }.toSet().size)
        assertEquals(declared.size, declared.map { it.fileName }.toSet().size)
    }

    // 分类重复会让同一产出拆成两行
    @Test
    fun `具名条目分类不重复`() {
        val categories = StorageRegistry.entries.map { it.category }
        assertEquals(categories.size, categories.toSet().size)
    }

    // 分类须有条目或落点根兜底
    @Test
    fun `每个分类都有条目或落点根`() {
        val covered = (StorageRegistry.entries.map { it.category } + StorageRegistry.roots.map { it.category }).toSet()
        assertEquals(CacheCategory.entries.toSet(), covered)
    }

    // 兜底分类不得同时登记为条目
    @Test
    fun `兜底分类不作为具名条目登记`() {
        val catchAll = StorageRegistry.roots.map { it.category }.toSet()
        val registered = StorageRegistry.entries.map { it.category }.filter { it in catchAll }
        assertTrue("兜底分类被当成具名条目登记：$registered", registered.isEmpty())
    }

    // 文件名由形态推导，错则统计为空
    @Test
    fun `落盘文件名按形态推导`() {
        assertEquals(
            "playlists.json",
            PersistedData("playlists", PersistedFileKind.DATA_STORE_JSON).fileName,
        )
        assertEquals(
            "settings.preferences_pb",
            PersistedData("settings", PersistedFileKind.DATA_STORE).fileName,
        )
        assertEquals(
            "boot_mirror.json",
            PersistedData("boot_mirror.json", PersistedFileKind.APP_FILE).fileName,
        )
    }

    private fun declaredData(): List<PersistedData> = StorageRegistry.entries.mapNotNull { it.persisted }
}