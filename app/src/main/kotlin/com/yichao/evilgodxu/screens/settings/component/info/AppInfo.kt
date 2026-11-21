package com.yichao.evilgodxu.screens.settings.component.info

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
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
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.report.AudioReport
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 关于分区：应用信息，点击版本号主动检查更新
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

    // 以系统浏览器打开链接；LocalContext 非 Activity 时需加 NEW_TASK
    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // 单件附件走 ACTION_SEND：部分接收方不认 SEND_MULTIPLE 的单元素列表
    fun shareFile(file: File, subject: String, title: String, mimeType: String = "text/plain") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, title)
        // LocalContext 为本地化包装 context，非 Activity 时需加 NEW_TASK
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    // 分享今日日志；没有今日日志时不拉起空分享
    fun shareLog() {
        val log = CrashLogManager.todayLogFile() ?: return
        shareFile(log, shareLogSubject, shareLogTitle)
    }

    // 分享设备音频报告：报告每次重新生成，与日志互不依赖；
    // 采集要读设备描述符，放到 IO 上做，避免卡住设置页
    fun shareAudioReport() {
        scope.launch {
            val report = withContext(Dispatchers.IO) { AudioReport.writeShareable(context) } ?: return@launch
            shareFile(report, shareReportSubject, shareReportTitle, mimeType = "image/png")
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
        Text(
            text = audioReportLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable { shareAudioReport() },
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
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
