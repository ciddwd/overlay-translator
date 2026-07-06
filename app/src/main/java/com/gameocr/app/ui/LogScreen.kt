package com.gameocr.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.data.LogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    var showExportOptions by remember { mutableStateOf(false) }
    var includeImagesInExport by remember { mutableStateOf(false) }
    var visibleLogLimit by remember { mutableStateOf(LOG_PAGE_SIZE) }
    val listState = rememberLazyListState()
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
    val hasFilteredImages = remember(filtered) { filtered.any { it.imagePath != null } }
    val displayed = remember(filtered, visibleLogLimit) {
        filtered.take(visibleLogLimit)
    }
    val hasMoreLogs = displayed.size < filtered.size
    val shouldAutoLoadMore by remember(displayed.size, filtered.size) {
        derivedStateOf {
            shouldLoadMoreLogs(
                lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                displayedCount = displayed.size,
                totalCount = filtered.size,
                prefetchThreshold = LOG_PAGE_PREFETCH_THRESHOLD
            )
        }
    }

    LaunchedEffect(filter) {
        visibleLogLimit = LOG_PAGE_SIZE
        listState.scrollToItem(0)
    }
    LaunchedEffect(shouldAutoLoadMore, filtered.size) {
        if (shouldAutoLoadMore) {
            visibleLogLimit = nextLogPageLimit(
                currentLimit = visibleLogLimit,
                totalCount = filtered.size,
                pageSize = LOG_PAGE_SIZE
            )
        }
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
                    val copiedSummary = stringResource(R.string.log_snack_copied_summary)
                    IconButton(
                        enabled = filtered.isNotEmpty(),
                        onClick = {
                            val text = formatForExport(context, filtered)
                            val copiedFull = copyToClipboardSafely(context, text)
                            val msg = if (copiedFull) {
                                String.format(copiedFormat, filtered.size)
                            } else {
                                copiedSummary
                            }
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
                            includeImagesInExport = false
                            showExportOptions = true
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 4.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayed, key = { it.id }) { entry ->
                        LogCard(
                            e = entry,
                            onCopyFull = { message ->
                                val copiedFull = copyToClipboardSafely(context, message)
                                val msg = if (copiedFull) {
                                    context.getString(R.string.log_snack_copied_full)
                                } else {
                                    context.getString(R.string.log_snack_copied_summary)
                                }
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            },
                            onShareFull = { message -> shareText(context, message) }
                        )
                    }
                    item(key = "log_paging_footer") {
                        LogListFooter(
                            displayedCount = displayed.size,
                            totalCount = filtered.size,
                            hasMoreLogs = hasMoreLogs,
                            onLoadMore = {
                                visibleLogLimit = nextLogPageLimit(
                                    currentLimit = visibleLogLimit,
                                    totalCount = filtered.size,
                                    pageSize = LOG_PAGE_SIZE
                                )
                            }
                        )
                    }
                    item(key = "footer_spacer") { Box(modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }
    if (showExportOptions) {
        ExportLogDialog(
            hasImages = hasFilteredImages,
            includeImages = includeImagesInExport,
            onIncludeImagesChange = { includeImagesInExport = it },
            onDismiss = { showExportOptions = false },
            onExport = { includeImages ->
                showExportOptions = false
                val text = formatForExport(context, filtered)
                shareLogExport(
                    context = context,
                    text = text,
                    entries = filtered,
                    includeImages = includeImages
                )
            }
        )
    }
}

private enum class LogFilter { All, OCR, Translate, Capture, Errors }

@Composable
private fun LogListFooter(
    displayedCount: Int,
    totalCount: Int,
    hasMoreLogs: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (hasMoreLogs) {
            TextButton(onClick = onLoadMore) {
                Text(stringResource(R.string.log_load_more_format, displayedCount, totalCount))
            }
        } else {
            Text(
                stringResource(R.string.log_all_loaded_format, totalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExportLogDialog(
    hasImages: Boolean,
    includeImages: Boolean,
    onIncludeImagesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onExport: (includeImages: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_export_options_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.log_export_options_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = hasImages) {
                            onIncludeImagesChange(!includeImages)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = includeImages && hasImages,
                        enabled = hasImages,
                        onCheckedChange = { checked -> onIncludeImagesChange(checked) }
                    )
                    Text(
                        stringResource(
                            if (hasImages) {
                                R.string.log_export_include_images
                            } else {
                                R.string.log_export_include_images_unavailable
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasImages) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(includeImages && hasImages) }) {
                Text(stringResource(R.string.log_export_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.log_export_cancel))
            }
        }
    )
}

@Composable
private fun LogCard(
    e: LogRepository.Entry,
    onCopyFull: (String) -> Unit,
    onShareFull: (String) -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    var expanded by remember(e.id) { mutableStateOf(false) }
    var expandedImagePath by remember(e.imagePath) { mutableStateOf<String?>(null) }
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
                val preview = remember(e.message, expanded) {
                    if (expanded) {
                        LogPreview(e.message, truncated = false, omittedChars = 0)
                    } else {
                        previewLogText(e.message)
                    }
                }
                Text(
                    preview.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (e.level == LogRepository.Level.ERROR)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
                if (preview.truncated || expanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                stringResource(
                                    if (expanded) R.string.log_action_collapse else R.string.log_action_expand_full
                                )
                            )
                        }
                        TextButton(onClick = { onCopyFull(e.message) }) {
                            Text(stringResource(R.string.log_action_copy_full))
                        }
                        TextButton(onClick = { onShareFull(e.message) }) {
                            Text(stringResource(R.string.log_action_share_full))
                        }
                    }
                }
            }
            e.imagePath?.let { path ->
                LogImagePreview(
                    path = path,
                    onOpen = { expandedImagePath = path }
                )
            }
        }
    }
    expandedImagePath?.let { path ->
        LogImageDialog(
            path = path,
            onDismiss = { expandedImagePath = null }
        )
    }
}

@Composable
private fun LogImagePreview(
    path: String,
    onOpen: () -> Unit,
) {
    val image = remember(path) { decodeLogImagePreview(path) }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.log_capture_image_content_description),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clickable(onClick = onOpen),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            stringResource(R.string.log_capture_image_missing_format, path),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogImageDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    val image = remember(path) {
        decodeLogImagePreview(path, maxDimension = LOG_IMAGE_DIALOG_MAX_DIMENSION)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_capture_image_dialog_title)) },
        text = {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.log_capture_image_content_description),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    stringResource(R.string.log_capture_image_missing_format, path),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
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
        if (e.imagePath != null) sb.append("  ").append(context.getString(R.string.log_export_image)).append(e.imagePath).append('\n')
    }
    return sb.toString()
}

internal data class LogPreview(
    val text: String,
    val truncated: Boolean,
    val omittedChars: Int,
)

internal fun previewLogText(
    text: String,
    maxChars: Int = LOG_PREVIEW_MAX_CHARS,
    maxLines: Int = LOG_PREVIEW_MAX_LINES,
): LogPreview {
    if (text.length <= maxChars && text.count { it == '\n' } < maxLines) {
        return LogPreview(text, truncated = false, omittedChars = 0)
    }

    var end = 0
    var lines = 1
    while (end < text.length && end < maxChars && lines <= maxLines) {
        if (text[end] == '\n') {
            lines++
            if (lines > maxLines) break
        }
        end++
    }
    val preview = text.take(end).trimEnd()
    return LogPreview(
        text = preview,
        truncated = true,
        omittedChars = text.length - preview.length,
    )
}

internal fun shouldShareLogAsFile(text: String): Boolean =
    text.length > MAX_DIRECT_TEXT_SHARE_CHARS

internal fun nextLogPageLimit(currentLimit: Int, totalCount: Int, pageSize: Int): Int {
    val safeTotal = totalCount.coerceAtLeast(0)
    if (pageSize <= 0) return safeTotal
    return (currentLimit.coerceAtLeast(0) + pageSize).coerceAtMost(safeTotal)
}

internal fun shouldLoadMoreLogs(
    lastVisibleItemIndex: Int?,
    displayedCount: Int,
    totalCount: Int,
    prefetchThreshold: Int,
): Boolean {
    if (lastVisibleItemIndex == null) return false
    if (displayedCount <= 0 || totalCount <= displayedCount) return false
    val threshold = prefetchThreshold.coerceAtLeast(0)
    return lastVisibleItemIndex >= (displayedCount - threshold).coerceAtLeast(0)
}

internal fun formatLogElapsedMs(elapsedMs: Long): String {
    val safe = elapsedMs.coerceAtLeast(0L)
    return when {
        safe < 1_000L -> "${safe}ms"
        safe < 10_000L -> String.format(Locale.US, "%.1fs", safe / 1_000.0)
        else -> "${(safe + 500L) / 1_000L}s"
    }
}

internal fun calculateImagePreviewSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    val largest = maxOf(width, height)
    var sampleSize = 1
    while (largest / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun decodeLogImagePreview(
    path: String,
    maxDimension: Int = LOG_IMAGE_PREVIEW_MAX_DIMENSION
): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateImagePreviewSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = maxDimension
        )
    }
    BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}.getOrNull()

private fun copyToClipboardSafely(context: Context, text: String): Boolean {
    val clipboardText = if (shouldShareLogAsFile(text)) {
        previewLogText(text, maxChars = MAX_SAFE_CLIPBOARD_CHARS, maxLines = Int.MAX_VALUE).text
    } else {
        text
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val copied = runCatching {
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.log_clipboard_label), clipboardText))
    }.isSuccess
    return copied && clipboardText === text
}

private fun shareText(context: Context, text: String) {
    runCatching {
        if (shouldShareLogAsFile(text)) {
            shareTextFile(context, text)
        } else {
            sharePlainText(context, text)
        }
    }.recoverCatching {
        copyToClipboardSafely(context, text)
    }
}

private fun shareLogExport(
    context: Context,
    text: String,
    entries: List<LogRepository.Entry>,
    includeImages: Boolean,
) {
    if (!includeImages) {
        shareText(context, text)
        return
    }
    runCatching {
        val file = writeLogZipFile(context, text, entries)
        shareFile(context, file, mimeType = "application/zip")
    }.recoverCatching {
        shareText(context, text)
    }
}

private fun writeLogZipFile(
    context: Context,
    text: String,
    entries: List<LogRepository.Entry>,
): File {
    val dir = File(context.cacheDir, "log_exports").apply { mkdirs() }
    val file = File(dir, "screen-translator-log-${System.currentTimeMillis()}.zip")
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry("screen-translator-log.txt"))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        val usedNames = mutableSetOf("screen-translator-log.txt")
        entries.forEach { entry ->
            val imagePath = entry.imagePath ?: return@forEach
            val imageFile = File(imagePath)
            if (!imageFile.isFile) return@forEach
            val zipName = uniqueZipEntryName(
                preferred = zipImageEntryName(entry.id, imagePath),
                usedNames = usedNames
            )
            zip.putNextEntry(ZipEntry(zipName))
            imageFile.inputStream().buffered().use { input ->
                input.copyTo(zip)
            }
            zip.closeEntry()
        }
    }
    return file
}

internal fun zipImageEntryName(logId: Long, imagePath: String): String {
    val rawName = imagePath.substringAfterLast('/').substringAfterLast('\\')
    return "images/$logId-${sanitizeZipFileName(rawName)}"
}

internal fun sanitizeZipFileName(rawName: String): String {
    val safe = rawName
        .trim()
        .map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '-'
        }
        .joinToString("")
        .trim('-')
    return safe.ifBlank { "image.png" }
}

internal fun uniqueZipEntryName(preferred: String, usedNames: MutableSet<String>): String {
    if (usedNames.add(preferred)) return preferred
    val nameStart = preferred.lastIndexOf('/') + 1
    val dot = preferred.lastIndexOf('.').takeIf { it > nameStart }
    val stem = if (dot != null) preferred.substring(0, dot) else preferred
    val ext = if (dot != null) preferred.substring(dot) else ""
    var index = 2
    while (true) {
        val candidate = "$stem-$index$ext"
        if (usedNames.add(candidate)) return candidate
        index++
    }
}

private fun sharePlainText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.log_share_chooser))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun shareTextFile(context: Context, text: String) {
    val dir = File(context.cacheDir, "log_exports").apply { mkdirs() }
    val file = File(dir, "screen-translator-log-${System.currentTimeMillis()}.txt")
    file.writeText(text, Charsets.UTF_8)
    shareFile(context, file, mimeType = "text/plain")
}

private fun shareFile(context: Context, file: File, mimeType: String) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.logfileprovider",
        file
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.log_share_subject))
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.log_clipboard_label), uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.log_share_chooser))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(chooser)
}

private const val LOG_PREVIEW_MAX_CHARS = 2 * 1024
private const val LOG_PREVIEW_MAX_LINES = 80
private const val LOG_PAGE_SIZE = 50
private const val LOG_PAGE_PREFETCH_THRESHOLD = 6
private const val LOG_IMAGE_PREVIEW_MAX_DIMENSION = 720
private const val LOG_IMAGE_DIALOG_MAX_DIMENSION = 1600
private const val MAX_DIRECT_TEXT_SHARE_CHARS = 256 * 1024
private const val MAX_SAFE_CLIPBOARD_CHARS = 64 * 1024
