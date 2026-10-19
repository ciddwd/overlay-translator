package com.gameocr.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.gameocr.app.R
import com.gameocr.app.gallery.GalleryAlbum
import com.gameocr.app.gallery.GalleryImageDecoder
import com.gameocr.app.gallery.GalleryImportKind
import com.gameocr.app.gallery.GalleryImportPolicy
import com.gameocr.app.gallery.GalleryImportSource
import com.gameocr.app.gallery.GalleryMediaSources
import com.gameocr.app.gallery.GallerySplitMode
import com.gameocr.app.gallery.GalleryTranslationWorkPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class GalleryPhotoAccess { FULL, PARTIAL, NONE }

/** Album tiles are about half the screen wide; decode covers at that size, not list-thumbnail size. */
private const val ALBUM_COVER_DIMENSION = 480

/** Permissions that list whole albums. Android 14 adds the "selected photos only" grant. */
internal fun galleryPhotoPermissions(sdk: Int): Array<String> = when {
    sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun galleryPhotoAccess(sdk: Int, granted: (String) -> Boolean): GalleryPhotoAccess = when {
    sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryPhotoAccess.FULL
    sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryPhotoAccess.PARTIAL
    sdk < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryPhotoAccess.FULL
    else -> GalleryPhotoAccess.NONE
}

internal fun currentGalleryPhotoAccess(context: Context): GalleryPhotoAccess =
    galleryPhotoAccess(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@HiltViewModel
class GalleryImportViewModel @Inject constructor(
    val mediaSources: GalleryMediaSources,
    val imageDecoder: GalleryImageDecoder,
) : ViewModel()

/** Pending album or folder that is over the task limit and waits for the split choice. */
internal data class GallerySplitRequest(val source: GalleryImportSource)

@Stable
internal class GalleryImportState {
    var sourceSheet by mutableStateOf(false)
    var accessExplainer by mutableStateOf(false)
    var readingFolder by mutableStateOf(false)
    var split by mutableStateOf<GallerySplitRequest?>(null)
    /** Set after the last photo-access request was refused; the album row then says so. */
    var albumDenied by mutableStateOf(false)
    fun open() { sourceSheet = true }
}

/**
 * The one "New task" entry used by the home gallery card and the task list:
 * Pick images, Choose album, Choose folder. Albums and folders over 100 images ask how to split.
 */
@Composable
internal fun GalleryImportHost(
    state: GalleryImportState,
    onReady: (GalleryImportSource, List<String>) -> Unit,
    onOpenAlbums: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: GalleryImportViewModel = hiltViewModel()
    val folderEmpty = stringResource(R.string.gallery_import_folder_empty)
    val accessDenied = stringResource(R.string.gallery_access_denied)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK)
    ) { uris ->
        if (uris.isNotEmpty()) onReady(GalleryImportSource(GalleryImportKind.PICKED), uris.map(Uri::toString))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        state.readingFolder = true
        scope.launch {
            val (name, entries) = try {
                viewModel.mediaSources.folder(tree)
            } finally {
                state.readingFolder = false
            }
            val source = GalleryImportSource(GalleryImportKind.FOLDER, name, entries)
            when {
                entries.isEmpty() -> onMessage(folderEmpty)
                entries.size > GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK -> state.split = GallerySplitRequest(source)
                else -> onReady(source, entries.map { it.uri })
            }
        }
    }
    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val denied = currentGalleryPhotoAccess(context) == GalleryPhotoAccess.NONE
        state.albumDenied = denied
        if (denied) onMessage(accessDenied) else onOpenAlbums()
    }

    if (state.sourceSheet) {
        GalleryImportSourceSheet(
            albumDenied = state.albumDenied,
            onPickImages = {
                state.sourceSheet = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChooseAlbum = {
                state.sourceSheet = false
                if (currentGalleryPhotoAccess(context) == GalleryPhotoAccess.NONE) {
                    state.accessExplainer = true
                } else {
                    onOpenAlbums()
                }
            },
            onChooseFolder = {
                state.sourceSheet = false
                folderPicker.launch(null)
            },
            onDismiss = { state.sourceSheet = false },
        )
    }
    if (state.accessExplainer) {
        GalleryPhotoAccessSheet(
            onContinue = {
                state.accessExplainer = false
                permissionRequest.launch(galleryPhotoPermissions(Build.VERSION.SDK_INT))
            },
            onDismiss = { state.accessExplainer = false },
        )
    }
    if (state.readingFolder) {
        GalleryReadingDialog()
    }
    state.split?.let { request ->
        GallerySplitSheet(
            source = request.source,
            onContinue = { mode ->
                state.split = null
                val chosen = request.source.copy(splitMode = mode)
                onReady(chosen, chosen.entries.map { it.uri })
            },
            onDismiss = { state.split = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryImportSourceSheet(
    albumDenied: Boolean,
    onPickImages: () -> Unit,
    onChooseAlbum: () -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.gallery_import_title),
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.gallery_import_subtitle),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HomeListRow(
                icon = Icons.Outlined.PhotoLibrary,
                title = stringResource(R.string.gallery_import_pick),
                subtitle = stringResource(R.string.gallery_import_pick_sub),
                trailing = HomeRowTrailing.Chevron,
                onClick = onPickImages,
            )
            HomeListRow(
                icon = Icons.Outlined.PhotoAlbum,
                title = stringResource(R.string.gallery_import_album),
                subtitle = stringResource(
                    if (albumDenied) R.string.gallery_import_album_sub_denied else R.string.gallery_import_album_sub
                ),
                trailing = HomeRowTrailing.Chevron,
                onClick = onChooseAlbum,
            )
            HomeListRow(
                icon = Icons.Outlined.Folder,
                title = stringResource(R.string.gallery_import_folder),
                subtitle = stringResource(R.string.gallery_import_folder_sub),
                trailing = HomeRowTrailing.Chevron,
                onClick = onChooseFolder,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryPhotoAccessSheet(onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HomeIconTile(Icons.Outlined.PhotoAlbum)
            Text(stringResource(R.string.gallery_access_title), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.gallery_access_message), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.gallery_access_alt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = onContinue) {
                Text(stringResource(R.string.gallery_access_continue))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onDismiss) {
                Text(stringResource(R.string.gallery_access_not_now))
            }
        }
    }
}

@Composable
private fun GalleryReadingDialog() {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 3.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(stringResource(R.string.gallery_import_reading), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.gallery_import_reading_sub), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GallerySplitSheet(
    source: GalleryImportSource,
    onContinue: (GallerySplitMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(source) { mutableStateOf(GallerySplitMode.SPLIT) }
    val total = source.entries.size
    val parts = (total + GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK - 1) / GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.gallery_split_title, total, source.name),
                modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.gallery_split_message), modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SplitOption(
                selected = mode == GallerySplitMode.SPLIT,
                title = stringResource(R.string.gallery_split_split, parts),
                subtitle = stringResource(R.string.gallery_split_split_sub, source.name, parts),
                onClick = { mode = GallerySplitMode.SPLIT },
            )
            SplitOption(
                selected = mode == GallerySplitMode.FIRST_ONLY,
                title = stringResource(R.string.gallery_split_first),
                subtitle = stringResource(R.string.gallery_split_first_sub, source.name),
                onClick = { mode = GallerySplitMode.FIRST_ONLY },
            )
            Button(modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp), onClick = { onContinue(mode) }) {
                Text(stringResource(R.string.gallery_split_continue))
            }
        }
    }
}

@Composable
private fun SplitOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            Modifier
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryAlbumPickerScreen(
    onBack: () -> Unit,
    onSelected: (GalleryImportSource, List<String>) -> Unit,
    viewModel: GalleryImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(currentGalleryPhotoAccess(context)) }
    var refresh by remember { mutableStateOf(0) }
    var split by remember { mutableStateOf<GalleryImportSource?>(null) }
    var loadingAlbum by remember { mutableStateOf(false) }
    var albums by remember { mutableStateOf<List<GalleryAlbum>?>(null) }
    LaunchedEffect(refresh) {
        albums = viewModel.mediaSources.albums()
    }
    val manageAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = currentGalleryPhotoAccess(context)
        refresh++
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_albums_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            AnimatedVisibility(visible = access == GalleryPhotoAccess.PARTIAL) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .background(HomeStatusColors.warnContainer(), RoundedCornerShape(14.dp))
                        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.gallery_albums_partial),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeStatusColors.warn(),
                    )
                    TextButton(onClick = { manageAccess.launch(galleryPhotoPermissions(Build.VERSION.SDK_INT)) }) {
                        Text(stringResource(R.string.gallery_albums_manage), color = HomeStatusColors.warn(),
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
            when (val list = albums) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                emptyList<GalleryAlbum>() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gallery_albums_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list, key = GalleryAlbum::bucketId) { album ->
                        AlbumTile(album, viewModel.imageDecoder, enabled = !loadingAlbum) {
                            loadingAlbum = true
                            scope.launch {
                                val entries = try {
                                    viewModel.mediaSources.albumEntries(album.bucketId)
                                } finally {
                                    loadingAlbum = false
                                }
                                if (entries.isEmpty()) return@launch
                                val source = GalleryImportSource(GalleryImportKind.ALBUM, album.name, entries)
                                if (entries.size > GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK) {
                                    split = source
                                } else {
                                    onSelected(source, entries.map { it.uri })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (loadingAlbum) GalleryReadingDialog()
    split?.let { source ->
        GallerySplitSheet(
            source = source,
            onContinue = { mode ->
                split = null
                val chosen = source.copy(splitMode = mode)
                onSelected(chosen, chosen.entries.map { it.uri })
            },
            onDismiss = { split = null },
        )
    }
}

@Composable
private fun AlbumTile(album: GalleryAlbum, decoder: GalleryImageDecoder, enabled: Boolean, onClick: () -> Unit) {
    var cover by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(album.coverUri) {
        cover = withContext(Dispatchers.IO) {
            runCatching { decoder.decodeThumbnail(album.coverUri, "", maxDimension = ALBUM_COVER_DIMENSION) }.getOrNull()
        }
    }
    Column(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            androidx.compose.animation.AnimatedVisibility(visible = cover != null, enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.96f)) {
                cover?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Text(
                "%,d".format(album.count),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
        Column {
            Text(album.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (album.count > GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK) {
                    stringResource(R.string.gallery_albums_over_limit)
                } else {
                    stringResource(R.string.gallery_albums_count, album.count)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** Editable task title on the create screen. Empty falls back to [defaultTitle]. */
@Composable
internal fun GalleryTaskTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    defaultTitle: String,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.gallery_confirm_title_label)) },
            placeholder = { Text(defaultTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = if (title.isNotEmpty() && enabled) {
                {
                    IconButton(onClick = { onTitleChange("") }) {
                        Icon(Icons.Outlined.Cancel, stringResource(R.string.gallery_confirm_title_clear))
                    }
                }
            } else {
                null
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                if (title.isBlank()) {
                    stringResource(R.string.gallery_confirm_title_empty_default, defaultTitle)
                } else {
                    stringResource(R.string.gallery_confirm_title_helper)
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${title.length} / ${GalleryImportPolicy.MAX_TITLE_LENGTH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the images came from, with a way to choose again; explains splitting when over 100. */
@Composable
internal fun GallerySourceCard(
    source: GalleryImportSource,
    imageCount: Int,
    parts: Int,
    enabled: Boolean,
    onChange: () -> Unit,
) {
    HomeSectionCard(horizontalInset = 0.dp, contentPadding = PaddingValues(8.dp)) {
        Row(Modifier.heightIn(min = 56.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeIconTile(
                when (source.kind) {
                    GalleryImportKind.PICKED -> Icons.Outlined.PhotoLibrary
                    GalleryImportKind.ALBUM -> Icons.Outlined.PhotoAlbum
                    GalleryImportKind.FOLDER -> Icons.Outlined.Folder
                }
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    when (source.kind) {
                        GalleryImportKind.PICKED -> stringResource(R.string.gallery_confirm_source_picked)
                        GalleryImportKind.ALBUM -> stringResource(R.string.gallery_confirm_source_album, source.name)
                        GalleryImportKind.FOLDER -> stringResource(R.string.gallery_confirm_source_folder, source.name)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.gallery_confirm_source_picked_sub, imageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChange, enabled = enabled) {
                Text(stringResource(R.string.gallery_confirm_source_change))
            }
        }
        val note = when {
            parts > 1 -> stringResource(R.string.gallery_confirm_split_note, parts)
            source.kind != GalleryImportKind.PICKED &&
                imageCount > GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK ->
                stringResource(R.string.gallery_confirm_first_note, imageCount)
            else -> null
        }
        AnimatedVisibility(visible = note != null) {
            Text(
                note.orEmpty(),
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** Rename from the task results header. Empty restores the creation date. */
@Composable
internal fun GalleryRenameDialog(
    currentTitle: String,
    defaultTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(currentTitle) {
        mutableStateOf(TextFieldValue(currentTitle, selection = TextRange(currentTitle.length)))
    }
    val value = field.text
    CatalystAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_task_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { next ->
                        field = if (next.text.length <= GalleryImportPolicy.MAX_TITLE_LENGTH) next else field
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.gallery_confirm_title_label)) },
                    placeholder = { Text(defaultTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                Text(
                    if (value.isBlank()) {
                        stringResource(R.string.gallery_task_rename_empty)
                    } else {
                        "${value.length} / ${GalleryImportPolicy.MAX_TITLE_LENGTH}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(GalleryImportPolicy.normalizeTitle(value)) }) {
                Text(stringResource(R.string.gallery_task_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
