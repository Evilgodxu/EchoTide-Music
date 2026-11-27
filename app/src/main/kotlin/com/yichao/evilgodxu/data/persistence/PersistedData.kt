package com.yichao.evilgodxu.data.persistence

import android.content.Context
import java.io.File

/** 落盘形态：决定文件名与所在目录，解析只在此处 */
enum class PersistedFileKind {
    /** <名>.preferences_pb */
    DATA_STORE,

    /** <名>.json，位于 datastore */
    DATA_STORE_JSON,

    /** 直接落在 filesDir，名字即文件名 */
    APP_FILE,
}

/** 具名持久化数据；名字即磁盘标识，改名会丢数据 */
data class PersistedData(
    val name: String,
    val kind: PersistedFileKind,
) {
    /** 落盘文件名，不依赖 Context */
    val fileName: String = when (kind) {
        PersistedFileKind.DATA_STORE -> "$name.preferences_pb"
        PersistedFileKind.DATA_STORE_JSON -> "$name.json"
        PersistedFileKind.APP_FILE -> name
    }

    /** 落点：目录布局只在此拼装 */
    fun locate(context: Context): File = when (kind) {
        PersistedFileKind.DATA_STORE, PersistedFileKind.DATA_STORE_JSON ->
            File(File(context.filesDir, DATA_STORE_DIR), fileName)
        PersistedFileKind.APP_FILE -> File(context.filesDir, fileName)
    }

    companion object {
        /** 旧版偏好目录名，仅用于统计遗留 */
        const val SHARED_PREFS_DIR = "shared_prefs"

        /** DataStore 目录，位于 filesDir */
        const val DATA_STORE_DIR = "datastore"

        /** 偏好目录可能不存在，缺失按 0 计 */
        fun sharedPrefsDir(context: Context): File = File(context.filesDir.parentFile, SHARED_PREFS_DIR)
    }
}