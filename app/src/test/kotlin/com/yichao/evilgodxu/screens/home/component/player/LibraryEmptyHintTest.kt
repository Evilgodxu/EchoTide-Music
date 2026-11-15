package com.yichao.evilgodxu.screens.home.component.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryEmptyHintTest {

    @Test
    fun `缺音频访问时提示未授权`() {
        assertEquals(
            LibraryEmptyHint.PermissionMissing,
            libraryEmptyHint(mediaAudioGranted = false, isScanning = false),
        )
    }

    @Test
    fun `授权后扫描期间不判定为空库`() {
        assertEquals(
            LibraryEmptyHint.Scanning,
            libraryEmptyHint(mediaAudioGranted = true, isScanning = true),
        )
    }

    @Test
    fun `授权且扫描结束后才是空库`() {
        assertEquals(
            LibraryEmptyHint.NoTracks,
            libraryEmptyHint(mediaAudioGranted = true, isScanning = false),
        )
    }
}
