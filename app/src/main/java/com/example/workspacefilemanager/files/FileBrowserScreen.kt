package com.example.workspacefilemanager.files

import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.example.workspacefilemanager.util.FileActions
import com.example.workspacefilemanager.util.SafeFolder
import com.example.workspacefilemanager.util.Starred
import com.example.workspacefilemanager.util.VideoThumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Model ─────────────────────────────────────────────────────────────────

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDir: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val modified: Long = file.lastModified()
) {
    val ext: String = name.substringAfterLast('.', "").lowercase()
    val isMedia: Boolean get() = FileActions.isVideo(name) || FileActions.isAudio(name)
    val isImage: Boolean get() = FileActions.isImage(name)
    val isDocument: Boolean get() = ext in DOCUMENT_EXTS
    val isCode: Boolean get() = ext in CODE_EXTS
    val isOpenable: Boolean get() = isMedia || isImage || isDocument || isCode
}

private val DOCUMENT_EXTS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
    "txt", "rtf", "csv", "epub"
)
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "c", "cpp", "cc", "h", "hpp", "cs", "py", "js", "ts",
    "jsx", "tsx", "go", "rs", "rb", "sh", "bat", "json", "xml", "yaml", "yml",
    "toml", "md", "log"
)

enum class SortMode(val label: String) {
    NAME("Name"), SIZE("Size"), MODIFIED("Date modified"), TYPE("Type")
}

data class ClipboardEntry(val file: File, val move: Boolean)

// ── ViewModel ─────────────────────────────────────────────────────────────

class FileBrowserViewModel : ViewModel() {

    private val _currentPath = MutableStateFlow(
        Environment.getExternalStorageDirectory().absolutePath
    )
    private val _items = MutableStateFlow<List<FileItem>>(emptyList())
    private val _allItems = MutableStateFlow<List<FileItem>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    private val _sortMode = MutableStateFlow(SortMode.NAME)
    private val _ascending = MutableStateFlow(true)
    private val _clipboard = MutableStateFlow<ClipboardEntry?>(null)

    val currentPath: StateFlow<String> = _currentPath.asStateFlow()
    val items: StateFlow<List<FileItem>> = _items.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()
    val ascending: StateFlow<Boolean> = _ascending.asStateFlow()
    val clipboard: StateFlow<ClipboardEntry?> = _clipboard.asStateFlow()

    init {
        browse(_currentPath.value)
    }

    fun browse(path: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            val result = withContext(Dispatchers.IO) {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) return@withContext null
                dir.listFiles()
                    ?.filterNot { it.name.startsWith(".trash") }
                    ?.map { FileItem(it) }
                    ?: emptyList()
            }
            if (result == null) {
                _error.value = "Cannot open: $path"
            } else {
                _currentPath.value = path
                _allItems.value = result
                applyFilter()
            }
            _loading.value = false
        }
    }

    fun refresh() = browse(_currentPath.value)

    fun navigateUp(): Boolean {
        val parent = File(_currentPath.value).parent ?: return false
        browse(parent)
        return true
    }

    fun setQuery(q: String) {
        _query.value = q
        applyFilter()
    }

    fun setSort(mode: SortMode) {
        if (_sortMode.value == mode) _ascending.value = !_ascending.value
        else {
            _sortMode.value = mode
            _ascending.value = true
        }
        applyFilter()
    }

    fun setClipboard(file: File, move: Boolean) {
        _clipboard.value = ClipboardEntry(file, move)
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    /** Filter by the current query, then sort folders before files. */
    private fun applyFilter() {
        val q = _query.value.trim().lowercase()
        val filtered = if (q.isEmpty()) _allItems.value
        else _allItems.value.filter { it.name.lowercase().contains(q) }

        val sorted = when (_sortMode.value) {
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortMode.SIZE -> filtered.sortedBy { it.size }
            SortMode.MODIFIED -> filtered.sortedBy { it.modified }
            SortMode.TYPE -> filtered.sortedWith(compareBy({ it.ext }, { it.name.lowercase() }))
        }.let { if (_ascending.value) it else it.reversed() }

        // Folders always stay grouped at the top.
        _items.value = sorted.filter { it.isDir } + sorted.filterNot { it.isDir }
    }

    /** Free space on the volume that holds [path]. */
    fun freeSpace(path: String): Long = try {
        val stat = StatFs(path)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (t: Throwable) {
        0L
    }
}

// ── Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onOpenFile: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val path by viewModel.currentPath.collectAsState()
    val items by viewModel.items.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val query by viewModel.query.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val ascending by viewModel.ascending.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()

    var searchOpen by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileItem?>(null) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<FileItem?>(null) }
    var moveCopyTarget by remember { mutableStateOf<FileItem?>(null) }
    var detailsTarget by remember { mutableStateOf<FileItem?>(null) }

    // Hardware back navigates up through folders before leaving the app.
    BackHandler(enabled = File(path).parent != null) { viewModel.navigateUp() }

    Scaffold(
        topBar = {
            if (searchOpen) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text("Search in this folder") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.setQuery("")
                            searchOpen = false
                        }) { Icon(Icons.Default.Close, "Close search") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                File(path).name.ifEmpty { "Internal storage" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${items.count { it.isDir }} folders · ${items.count { !it.isDir }} files",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Up one level")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(mode.label)
                                                if (mode == sortMode) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Icon(
                                                        if (ascending) Icons.Default.ArrowUpward
                                                        else Icons.Default.ArrowDownward,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSort(mode)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showNewFolder = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (clipboard != null) {
                ExtendedFloatingActionButton(
                    text = { Text("Paste ${clipboard!!.file.name}", maxLines = 1) },
                    icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    onClick = {
                        val entry = clipboard!!
                        val dest = File(path)
                        val result = if (entry.move) {
                            FileActions.moveInto(entry.file, dest)
                        } else {
                            FileActions.copyInto(entry.file, dest)
                        }
                        FileActions.toast(
                            context,
                            result.message ?: if (entry.move) "Moved here" else "Copied here"
                        )
                        viewModel.clearClipboard()
                        viewModel.refresh()
                    }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error!!,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.refresh() }) { Text("Retry") }
                    if (!FileActions.hasAllFilesAccess()) {
                        TextButton(onClick = { FileActions.requestAllFilesAccess(context) }) {
                            Text("Grant storage access")
                        }
                    }
                }
                items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        if (query.isNotBlank()) "No matches for \"$query\"" else "This folder is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.file.absolutePath }) { item ->
                        FileRow(
                            item = item,
                            onClick = {
                                if (item.isDir) viewModel.browse(item.file.absolutePath)
                                else onOpenFile(item.file.absolutePath)
                            },
                            onLongClick = { selected = item }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    item {
                        Spacer(Modifier.height(88.dp))
                    }
                }
            }
        }
    }

    // ── Action sheet ──────────────────────────────────────────────────────
    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileLeadingIcon(item = item, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (item.isDir) "Folder"
                            else "${FileActions.formatBytes(item.size)} · ${FileActions.formatDate(item.modified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                if (!item.isDir) {
                    SheetAction(Icons.Default.OpenInNew, "Open") {
                        selected = null
                        onOpenFile(item.file.absolutePath)
                    }
                    SheetAction(Icons.Default.Share, "Share") {
                        selected = null
                        FileActions.share(context, item.file)
                    }
                }
                SheetAction(Icons.Default.ContentCopy, "Copy") {
                    viewModel.setClipboard(item.file, move = false)
                    selected = null
                    FileActions.toast(context, "Copied — open a folder and paste")
                }
                SheetAction(Icons.Default.DriveFileMove, "Move") {
                    moveCopyTarget = item
                    selected = null
                }
                SheetAction(
                    if (Starred.isStarred(context, item.file)) Icons.Default.Star else Icons.Default.StarBorder,
                    if (Starred.isStarred(context, item.file)) "Remove from Starred" else "Star"
                ) {
                    val nowStarred = Starred.toggle(context, item.file)
                    FileActions.toast(
                        context,
                        if (nowStarred) "Added to Starred" else "Removed from Starred"
                    )
                    selected = null
                }
                if (!item.isDir) {
                    SheetAction(Icons.Default.Lock, "Move to Safe folder") {
                        val moved = SafeFolder.put(context, item.file)
                        if (moved != null) {
                            FileActions.toast(context, "Moved to Safe folder")
                            viewModel.refresh()
                        } else {
                            FileActions.toast(context, "Could not move that file")
                        }
                        selected = null
                    }
                }
                SheetAction(Icons.Default.Edit, "Rename") {
                    renameTarget = item
                    selected = null
                }
                SheetAction(Icons.Default.Info, "Details") {
                    detailsTarget = item
                    selected = null
                }
                SheetAction(
                    Icons.Default.Delete,
                    "Delete",
                    tint = MaterialTheme.colorScheme.error
                ) {
                    deleteTarget = item
                    selected = null
                }
            }
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    renameTarget?.let { item ->
        var nameInput by remember(item) { mutableStateOf(item.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("New name") },
                    singleLine = true,
                    isError = nameInput.isBlank() || nameInput.contains('/'),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = FileActions.isValidName(nameInput),
                    onClick = {
                        val result = FileActions.rename(item.file, nameInput.trim())
                        FileActions.toast(context, result.message ?: "Renamed to ${nameInput.trim()}")
                        renameTarget = null
                        viewModel.refresh()
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ── Delete confirm ────────────────────────────────────────────────────
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete \"${item.name}\"?") },
            text = {
                Text(
                    if (item.isDir) "This folder and everything inside it will be permanently deleted."
                    else "This file (${FileActions.formatBytes(item.size)}) will be permanently deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = FileActions.delete(context, item.file)
                    FileActions.toast(
                        context,
                        result.message ?: "Deleted ${item.name}"
                    )
                    if (!result.success && !FileActions.hasAllFilesAccess()) {
                        FileActions.requestAllFilesAccess(context)
                    }
                    deleteTarget = null
                    viewModel.refresh()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ── Move destination picker ───────────────────────────────────────────
    moveCopyTarget?.let { item ->
        FolderPickerDialog(
            startDir = File(path),
            title = "Move \"${item.name}\" to",
            onDismiss = { moveCopyTarget = null },
            onPick = { dest ->
                val result = FileActions.moveInto(item.file, dest)
                FileActions.toast(context, result.message ?: "Moved to ${dest.name}")
                moveCopyTarget = null
                viewModel.refresh()
            }
        )
    }

    // ── Details dialog ────────────────────────────────────────────────────
    detailsTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { detailsTarget = null },
            title = { Text("Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow("Name", item.name)
                    DetailRow("Path", item.file.parent ?: "/")
                    DetailRow("Type", if (item.isDir) "Folder" else item.ext.uppercase().ifEmpty { "File" })
                    if (!item.isDir) DetailRow("Size", FileActions.formatBytes(item.size))
                    DetailRow("Modified", FileActions.formatDate(item.modified))
                    DetailRow("Readable", if (item.file.canRead()) "Yes" else "No")
                    DetailRow("Writable", if (item.file.canWrite()) "Yes" else "No")
                }
            },
            confirmButton = {
                TextButton(onClick = { detailsTarget = null }) { Text("Close") }
            }
        )
    }

    // ── New folder dialog ─────────────────────────────────────────────────
    if (showNewFolder) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = FileActions.isValidName(nameInput),
                    onClick = {
                        val result = FileActions.createFolder(File(path), nameInput.trim())
                        FileActions.toast(
                            context,
                            result.message ?: "Created ${nameInput.trim()}"
                        )
                        showNewFolder = false
                        viewModel.refresh()
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            }
        )
    }
}

// ── Pieces ────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Leading icon: image thumbnail, video frame, or a tinted vector icon. */
@Composable
private fun FileLeadingIcon(item: FileItem, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val isThumbnailable = !item.isDir && (item.isImage || item.isMedia)
    if (isThumbnailable) {
        val thumb by produceState<android.graphics.Bitmap?>(
            initialValue = VideoThumbs.cached(item.file),
            item.file.absolutePath
        ) {
            if (value == null && item.isMedia) value = VideoThumbs.load(item.file)
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (item.isImage) {
                AsyncImage(
                    model = item.file,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (thumb != null) {
                ComposeImage(
                    bitmap = thumb!!.asImageBitmap(),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                )
            } else {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    } else {
    val (icon, tint) = when {
        item.isDir -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
        item.ext == "apk" -> Icons.Default.Android to Color(0xFF3DDC84)
        item.ext == "pdf" -> Icons.Default.PictureAsPdf to MaterialTheme.colorScheme.error
        item.ext in setOf("zip", "rar", "7z", "tar", "gz") ->
            Icons.Default.FolderZip to MaterialTheme.colorScheme.tertiary
        item.isCode -> Icons.Default.Code to MaterialTheme.colorScheme.tertiary
        item.isDocument -> Icons.Default.Description to MaterialTheme.colorScheme.secondary
        else -> Icons.Default.InsertDriveFile to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val childCount = remember(item.file.absolutePath) {
        if (item.isDir) (item.file.listFiles()?.size ?: 0) else 0
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileLeadingIcon(item = item)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.isDir) "$childCount items"
                else "${FileActions.formatBytes(item.size)} · ${FileActions.formatDate(item.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Icon(
            if (item.isDir) Icons.Default.ChevronRight else Icons.Default.MoreVert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Minimal folder chooser used by Move. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    startDir: File,
    title: String,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    var dir by remember { mutableStateOf(startDir) }
    var folders by remember(dir) {
        mutableStateOf(
            dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        )
    }
    val root = remember { Environment.getExternalStorageDirectory() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { dir.parentFile?.let { dir = it } },
                        enabled = dir.absolutePath != root.absolutePath
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up")
                    }
                    Text(
                        dir.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(folders, key = { it.absolutePath }) { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { dir = f }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (folders.isEmpty()) {
                        item {
                            Text(
                                "No sub-folders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(dir) }) { Text("Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
