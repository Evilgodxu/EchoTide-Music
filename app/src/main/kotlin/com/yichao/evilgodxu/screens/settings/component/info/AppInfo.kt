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
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 关于分区：应用信息，点击版本号主动检查更新
@Composable
fun AppInfo(
    version: String,
    onVersionClick: () -> Unit,
) {
    val context = LocalContext.current
    val brand = stringResource(R.string.app_info_brand)
    val logLabel = stringResource(R.string.app_info_log_label)
    val qqGroupLabel = stringResource(R.string.app_info_qq_group)
    val shareLogSubject = stringResource(R.string.app_info_share_log_subject)
    val shareLogTitle = stringResource(R.string.app_info_share_log_title)

    // 以系统浏览器打开链接；LocalContext 非 Activity 时需加 NEW_TASK
    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // 直接拉起 QQ 加群；未安装 QQ 时回退到网页加群
    fun openQqGroup() {
        val intent = Intent(Intent.ACTION_VIEW, QQ_GROUP_DEEP_LINK.toUri())
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openUrl(QQ_GROUP_URL) }
    }

    // 分享今日日志；无日志文件时直接忽略点击
    fun shareLog() {
        val file = CrashLogManager.todayLogFile() ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, shareLogSubject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, shareLogTitle)
        // LocalContext 为本地化包装 context，非 Activity 时需加 NEW_TASK
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
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
            text = qqGroupLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clickable { openQqGroup() },
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
private const val QQ_GROUP_URL = "https://qm.qq.com/q/VkFPRNmykw"
private const val QQ_GROUP_DEEP_LINK = "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=923555630&card_type=group&source=qrcode"
