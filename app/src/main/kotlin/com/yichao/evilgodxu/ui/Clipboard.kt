package com.yichao.evilgodxu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.log.CrashLogManager

// 写剪贴板并提示，长文本可传 toastText 覆盖
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
