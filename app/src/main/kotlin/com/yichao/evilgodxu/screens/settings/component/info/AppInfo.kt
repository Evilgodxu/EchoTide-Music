package com.yichao.evilgodxu.screens.settings.component.info

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.AlbumImageStore
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.report.AudioReport
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 关于区：点版本号检查更新
@Composable
fun AppInfo(
    version: String,
    onVersionClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val brand = stringResource(R.string.app_info_brand)
    val logLabel = stringResource(R.string.app_info_log_label)
    val audioReportLabel = stringResource(R.string.app_info_audio_report)
    val shareLogSubject = stringResource(R.string.app_info_share_log_subject)
    val shareLogTitle = stringResource(R.string.app_info_share_log_title)
    val shareReportSubject = stringResource(R.string.app_info_share_report_subject)
    val shareReportTitle = stringResource(R.string.app_info_share_report_title)
    val reportSaved = stringResource(R.string.app_info_audio_report_saved)
    val reportSaveFailed = stringResource(R.string.app_info_audio_report_save_failed)
    val reportFailed = stringResource(R.string.app_info_audio_report_failed)
    // 报告忙时禁止重复触发
    var reportBusy by remember { mutableStateOf(false) }

    // 非 Activity 时需 NEW_TASK
    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // 单件附件走 ACTION_SEND
    fun shareUri(uri: Uri, subject: String, title: String, mimeType: String = "text/plain") {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, title)
        // 非 Activity 时需 NEW_TASK
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun shareFile(file: File, subject: String, title: String, mimeType: String = "text/plain") {
        shareUri(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
            subject,
            title,
            mimeType,
        )
    }

    // 无今日日志则不分享
    fun shareLog() {
        val log = CrashLogManager.todayLogFile() ?: return
        shareFile(log, shareLogSubject, shareLogTitle)
    }

    // 报告每次重生成，阻塞 IO 放 IO 线程
    fun shareAudioReport() {
        if (reportBusy) return
        reportBusy = true
        scope.launch {
            val report = withContext(Dispatchers.IO) { AudioReport.writeShareable(context) }
            if (report == null) {
                reportBusy = false
                Toast.makeText(context, reportFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 先存相册，成功才分享
            val albumUri = withContext(Dispatchers.IO) {
                runCatching {
                    AlbumImageStore.writeToAlbum(context, report.name, "image/png", report.readBytes())
                }.getOrNull()
            }
            reportBusy = false
            if (albumUri == null) {
                Toast.makeText(context, reportSaveFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            Toast.makeText(context, reportSaved, Toast.LENGTH_SHORT).show()
            shareUri(albumUri, shareReportSubject, shareReportTitle, mimeType = "image/png")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
    ) {
        Text(
            text = brand,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_version, version),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .clickable(onClick = onVersionClick),
            textAlign = TextAlign.Center,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text = logLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable { shareLog() },
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable(enabled = !reportBusy) { shareAudioReport() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reportBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(12.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Text(
                text = audioReportLabel,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable { openUrl(GITHUB_URL) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    AppIcons.Code,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = GITHUB_URL,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

private const val GITHUB_URL = "https://github.com/Evilgodxu/EchoTide-Music"
