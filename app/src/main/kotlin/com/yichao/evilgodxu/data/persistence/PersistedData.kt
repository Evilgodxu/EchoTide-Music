package com.yichao.evilgodxu.data.persistence

import android.content.Context
import java.io.File

/**
 * 持久化落盘形态：决定名字如何变成磁盘文件名、文件落在哪个目录。
 *
 * 三种形态的生成规则由各自框架固定（SharedPreferences 写 `<名>.xml`、DataStore 写
 * `<名>.preferences_pb`、自管文件名字即文件名），故解析只在此实现一次，读写方与统计方共用，
 * 不在别处再拼一遍路径。
 */
enum class PersistedFileKind {
    /** SharedPreferences：应用数据根目录下的 shared_prefs/<名>.xml */
    SHARED_PREFS,

    /** DataStore：filesDir/datastore/<名>.preferences_pb */
    DATA_STORE,

    /** 自管文件：直接落在 filesDir 下，名字即文件名 */
    APP_FILE,
}

/**
 * 一份具名持久化数据：由各存储模块在自身旁边声明，读写处一律引用它。
 *
 * 名字是这份数据在磁盘上的唯一标识 —— 改名等于放弃既有文件，已装机用户的数据将读不回来；
 * 故声明必须与读写处同源，不能让统计方另存一份字面量。归类（在存储页展示为哪一行）不在此声明：
 * 那是存储页的口径，见 `data.cache.StorageRegistry`。
 */
data class PersistedData(
    val name: String,
    val kind: PersistedFileKind,
) {
    /** 落盘文件名：供存储页推导「已主张落点」，也供一致性检查使用，不依赖 Context */
    val fileName: String = when (kind) {
        PersistedFileKind.SHARED_PREFS -> "$name.xml"
        PersistedFileKind.DATA_STORE -> "$name.preferences_pb"
        PersistedFileKind.APP_FILE -> name
    }

    /** 落点：目录布局只在此拼装 */
    fun locate(context: Context): File = when (kind) {
        PersistedFileKind.SHARED_PREFS -> File(sharedPrefsDir(context), fileName)
        PersistedFileKind.DATA_STORE -> File(File(context.filesDir, DATA_STORE_DIR), fileName)
        PersistedFileKind.APP_FILE -> File(context.filesDir, fileName)
    }

    companion object {
        /** SharedPreferences 目录名：框架写在应用数据根目录下，与 filesDir 同级 */
        const val SHARED_PREFS_DIR = "shared_prefs"

        /** DataStore 目录名：位于 filesDir 下，由 preferencesDataStore 按名生成 */
        const val DATA_STORE_DIR = "datastore"

        /** shared_prefs 目录：从未写入过任何偏好时可能不存在，统计按 0 计即可 */
        fun sharedPrefsDir(context: Context): File = File(context.filesDir.parentFile, SHARED_PREFS_DIR)
    }
}