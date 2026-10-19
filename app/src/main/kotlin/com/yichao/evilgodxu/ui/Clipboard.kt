package com.yichao.evilgodxu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager

// 写入系统剪贴板，并弹出复制成功提示；调用方传入的 context 已是应用内语言的本地化 Context
//
// toastText 单独给出：默认提示会带上复制内容本身，短文本足以自证，而整篇歌词这类长文本
// 会把提示撑成整屏，只能改为不带内容的确认文案
internal fun copyToClipboard(
    context: Context,
    text: String,
    toastText: String = context.getString(R.string.copy_success_toast, text),
) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        CrashLogManager.logException("Clipboard", "复制到剪贴板失败", e)
    }
}
