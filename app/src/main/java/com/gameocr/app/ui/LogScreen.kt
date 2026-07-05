package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.data.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 运行日志页：倒序显示 [LogRepository.entries]。
 *
 * - 顶部 chip 过滤（全部 / OCR / 翻译 / 截屏 / 错误）
 * - 右上角清空按钮
 * - 每条卡片：时间戳 + 类别 tag + 等级颜色 + message；OCR/翻译有"原文 → 译文"对的两行展开
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    var filter by remember { mutableStateOf<LogFilter>(LogFilter.All) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 不拦系统返回手势 / 物理键的话会走 ComponentActivity 默认行为 = 退出 App。
    // 跟 SettingsScreen 的 BackHandler 保持一致：回主屏。
    BackHandler { onBack() }

    val filtered = remember(entries, filter) {
        when (filter) {
            LogFilter.All -> entries
            LogFilter.OCR -> entries.filter { it.category == LogRepository.Category.OCR }
            LogFilter.Translate -> entries.filter { it.category == LogRepository.Category.TRANSLATE }
            LogFilter.Capture -> entries.filter { it.category == LogRepository.Category.CAPTURE }
            LogFilter.Errors -> entries.filter { it.level == LogRepository.Level.ERROR || it.level == LogRepository.Level.WARN }
        }.asReversed()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    val copiedFormat = stringResource(R.string.log_snack_copied_format)
                    IconButton(
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            val text = formatForExport(context, filtered)
                            copyToClipboard(context, text)
                            val msg = String.format(copiedFormat, filtered.size)
                            scope.launch {
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.log_copy))
                    }
                    IconButton(
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            val text = formatForExport(context, filtered)
                            shareText(context, text)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.log_share))
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.log_clear))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == LogFilter.All,
                    onClick = { filter = LogFilter.All },
                    label = { Text(stringResource(R.string.log_filter_all_format, entries.size)) }
                )
                FilterChip(
                    selected = filter == LogFilter.OCR,
                    onClick = { filter = LogFilter.OCR },
                    label = { Text(stringResource(R.string.log_category_ocr)) }
                )
                FilterChip(
                    selected = filter == LogFilter.Translate,
                    onClick = { filter = LogFilter.Translate },
                    label = { Text(stringResource(R.string.log_filter_translate)) }
                )
                FilterChip(
                    selected = filter == LogFilter.Errors,
                    onClick = { filter = LogFilter.Errors },
                    label = { Text(stringResource(R.string.log_filter_errors)) }
                )
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(if (entries.isEmpty()) R.string.log_empty_no_logs else R.string.log_empty_no_match),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 4.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        LogCard(entry)
                    }
                    item(key = "footer_spacer") { Box(modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
}

private enum class LogFilter { All, OCR, Translate, Capture, Errors }

@Composable
private fun LogCard(e: LogRepository.Entry) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val levelColor = when (e.level) {
        LogRepository.Level.ERROR -> MaterialTheme.colorScheme.error
        LogRepository.Level.WARN -> Color(0xFFE6A23C)
        LogRepository.Level.INFO -> MaterialTheme.colorScheme.primary
    }
    val categoryLabel = stringResource(
        when (e.category) {
            LogRepository.Category.CAPTURE -> R.string.log_category_capture
            LogRepository.Category.OCR -> R.string.log_category_ocr
            LogRepository.Category.TRANSLATE -> R.string.log_category_translate
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    timeFmt.format(Date(e.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .background(
                            levelColor.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        categoryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                e.elapsedMs?.let { elapsed ->
                    Text(
                        formatLogElapsedMs(elapsed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            if (e.source != null && e.translated != null) {
                Text(
                    stringResource(R.string.log_card_source_format, e.source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.log_card_translated_format, e.translated),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    e.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (e.level == LogRepository.Level.ERROR)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repo: LogRepository
) : ViewModel() {
    val entries = repo.entries
    fun clear() = repo.clear()
}

/**
 * 把当前显示的日志渲染为纯文本，方便复制 / 分享。
 * 格式（每条一段）：
 *   2026-06-23 15:21:08 [OCR/INFO] 识别到 3 段 [PADDLE_ONNX]: #1 ... | #2 ...
 *   原文：你好
 *   译文：Hello
 *
 * 顺序按传入的 entries 顺序（UI 已经按倒序展示，导出时也保持倒序）。
 */
private fun formatForExport(context: Context, entries: List<LogRepository.Entry>): String {
    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val sb = StringBuilder()
    sb.append(context.getString(R.string.log_export_header_format, entries.size)).append('\n')
    sb.append(context.getString(R.string.log_export_export_time)).append(ts.format(Date())).append('\n')
    sb.append("─".repeat(40)).append('\n')
    for (e in entries) {
        val cat = context.getString(
            when (e.category) {
                LogRepository.Category.CAPTURE -> R.string.log_category_capture
                LogRepository.Category.OCR -> R.string.log_category_ocr
                LogRepository.Category.TRANSLATE -> R.string.log_category_translate
            }
        )
        sb.append(ts.format(Date(e.timestamp)))
            .append(" [").append(cat).append('/').append(e.level.name).append(']')
        e.elapsedMs?.let { sb.append(" [").append(formatLogElapsedMs(it)).append(']') }
        sb.append(' ')
            .append(e.message).append('\n')
        if (e.source != null) sb.append("  ").append(context.getString(R.string.log_export_source)).append(e.source).append('\n')
        if (e.translated != null) sb.append("  ").append(context.getString(R.string.log_export_translated)).append(e.translated).append('\n')
    }
    return sb.toString()
}

internal fun formatLogElapsedMs(elapsedMs: Long): String {
    val safe = elapsedMs.coerceAtLeast(0L)
    return when {
        safe < 1_000L -> "${safe}ms"
        safe < 10_000L -> String.format(Locale.US, "%.1fs", safe / 1_000.0)
        else -> "${(safe + 500L) / 1_000L}s"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.log_clipboard_label), text))
}

private fun shareText(context: Context, text: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.log_share_chooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        // TransactionTooLargeException：日志文本超出 Binder 1MB 限制
        // 回退到剪贴板复制
        copyToClipboard(context, text)
    }
}
