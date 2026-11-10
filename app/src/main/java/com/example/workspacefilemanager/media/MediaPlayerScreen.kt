package com.example.workspacefilemanager.media

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.workspacefilemanager.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Model ─────────────────────────────────────────────────────────────────

enum class MediaKind { IMAGE, VIDEO, AUDIO }

data class MediaEntry(
    val id: Long,
    val uri: Uri,
    val path: String,
    val name: String,
    val size: Long,
    val date: Long,
    val kind: MediaKind,
    val bucket: String
)

enum class MediaTab(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), AUDIO("Audio")
}

// ── ViewModel ─────────────────────────────────────────────────────────────

class MediaPlayerViewModel : ViewModel() {

    private var appContext: android.content.Context? = null

    private val _entries = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _tab = MutableStateFlow(MediaTab.ALL)
    private val _query = MutableStateFlow("")
    private val _selection = MutableStateFlow<Set<String>>(emptySet())

    val entries: StateFlow<List<MediaEntry>> = _entries.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val tab: StateFlow<MediaTab> = _tab.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** What the grid should render after tab + search filtering. */
    val visible: StateFlow<List<MediaEntry>> get() = _visible
    private val _visible = MutableStateFlow<List<MediaEntry>>(emptyList())

    fun init(context: android.content.Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            refresh()
        }
    }

    fun setTab(tab: MediaTab) {
        _tab.value = tab
        applyFilter()
    }

    fun setQuery(q: String) {
        _query.value = q
        applyFilter()
    }

    fun toggleSelection(path: String) {
        _selection.value = _selection.value.let { if (path in it) it - path else it + path }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun deleteSelected() {
        val ctx = appContext ?: return
        val paths = _selection.value
        viewModelScope.launch(Dispatchers.IO) {
            var freed = 0L
            var count = 0
            paths.forEach { p ->
                val f = File(p)
                val size = f.length()
                if (FileActions.delete(ctx, f).success) {
                    count++
                    freed += size
                }
            }
            _entries.value = _entries.value.filterNot { it.path in paths }
            _selection.value = emptySet()
            applyFilter()
            withContext(Dispatchers.Main) {
                FileActions.toast(
                    ctx,
                    if (count > 0) "Deleted $count item(s) · ${FileActions.formatBytes(freed)} freed"
                    else "Nothing could be deleted"
                )
            }
        }
    }

    fun shareSelected() {
        val ctx = appContext ?: return
        val files = _selection.value.map { File(it) }.filter { it.exists() }
        if (files.isNotEmpty()) FileActions.share(ctx, files)
    }

    fun refresh() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _error.value = null
            val found = mutableListOf<MediaEntry>()
            fun query(collection: Uri, kind: MediaKind) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
                )
                try {
                    ctx.contentResolver.query(
                        collection,
                        projection,
                        null,
                        null,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        val bucketCol =
                            cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val path = cursor.getString(dataCol) ?: continue
                            found += MediaEntry(
                                id = id,
                                uri = ContentUris.withAppendedId(collection, id),
                                path = path,
                                name = cursor.getString(nameCol) ?: File(path).name,
                                size = cursor.getLong(sizeCol),
                                date = cursor.getLong(dateCol) * 1000L,
                                kind = kind,
                                bucket = cursor.getString(bucketCol) ?: "Other"
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // A single broken collection should not kill the whole scan
                }
            }
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaKind.IMAGE)
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaKind.VIDEO)
            query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaKind.AUDIO)

            _entries.value = found.sortedByDescending { it.date }
            if (found.isEmpty()) _error.value = "No media indexed yet"
            applyFilter()
            _loading.value = false
        }
    }

    private fun applyFilter() {
        val q = _query.value.trim().lowercase()
        _visible.value = _entries.value.filter { e ->
            val tabOk = when (_tab.value) {
                MediaTab.ALL -> true
                MediaTab.PHOTOS -> e.kind == MediaKind.IMAGE
                MediaTab.VIDEOS -> e.kind == MediaKind.VIDEO
                MediaTab.AUDIO -> e.kind == MediaKind.AUDIO
            }
            val queryOk = q.isEmpty() || e.name.lowercase().contains(q) || e.bucket.lowercase().contains(q)
            tabOk && queryOk
        }
    }
}

// ── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    initialPath: String? = null,
    onOpenImage: (String) -> Unit = {},
    onOpenFile: (String) -> Unit = {},
    onOpenStorage: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val vm: MediaPlayerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    LaunchedEffect(Unit) { vm.init(context) }

    val visible by vm.visible.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val tab by vm.tab.collectAsState()
    val query by vm.query.collectAsState()
    val selection by vm.selection.collectAsState()

    var playPath by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // The Media tab opens on the Files-by-Google style Browse home; drilling into
    // a category switches to the gallery/list below.
    var showHome by remember { mutableStateOf(initialPath.isNullOrBlank()) }

    LaunchedEffect(initialPath) {
        if (!initialPath.isNullOrBlank() && File(initialPath).exists() &&
            FileActions.isImage(initialPath)
        ) {
            onOpenImage(initialPath)
        } else if (!initialPath.isNullOrBlank()) {
            playPath = initialPath
        }
    }

    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(exoPlayer, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                exoPlayer.pause()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                exoPlayer.release()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    LaunchedEffect(playPath, exoPlayer) {
        val path = playPath ?: return@LaunchedEffect
        val file = File(path)
        if (!file.exists()) return@LaunchedEffect
        exoPlayer.setMediaItem(MediaItem.fromUri(FileActions.uriFor(context, file)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = !showHome && playPath == null) { showHome = true }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete ${selection.size} item(s)?") },
            text = { Text("They will be permanently removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSelected()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showHome && playPath == null) {
        MediaHomeScreen(
            onOpenGallery = { tab ->
                vm.setTab(tab)
                showHome = false
            },
            onOpenFile = onOpenFile,
            onOpenStorage = onOpenStorage
        )
        return
    }

    if (playPath != null) {
        val playlist = visible.filter { it.kind != MediaKind.IMAGE }
        PlayerScreen(
            exoPlayer = exoPlayer,
            playPath = playPath!!,
            mediaFiles = playlist,
            onBack = {
                exoPlayer.pause()
                playPath = null
            },
            onPlayNext = {
                val idx = playlist.indexOfFirst { it.path == playPath }
                if (idx >= 0 && idx < playlist.size - 1) playPath = playlist[idx + 1].path
            },
            onPlayPrev = {
                val idx = playlist.indexOfFirst { it.path == playPath }
                if (idx > 0) playPath = playlist[idx - 1].path
            }
        )
        return
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.shareSelected() }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                )
            } else if (searchOpen) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text("Search photos, videos, audio") },
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
                            vm.setQuery("")
                            searchOpen = false
                        }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(tab.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${visible.size} items",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { showHome = true }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back to Browse")
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (selection.isEmpty()) {
                MediaTabs(current = tab, onSelect = vm::setTab)
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
                visible.isEmpty() -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (query.isNotBlank()) "No results for \"$query\"" else "No media found",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Photos and videos appear here once Android has indexed them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.refresh() }) { Text("Rescan") }
                }
                tab == MediaTab.AUDIO -> AudioList(
                    entries = visible,
                    selected = selection,
                    onLongPress = { vm.toggleSelection(it.path) },
                    onClick = { playPath = it.path }
                )
                else -> MediaGrid(
                    entries = visible,
                    selected = selection,
                    onLongPress = { vm.toggleSelection(it.path) },
                    onClick = { entry ->
                        if (selection.isNotEmpty()) vm.toggleSelection(entry.path)
                        else if (entry.kind == MediaKind.IMAGE) onOpenImage(entry.path)
                        else playPath = entry.path
                    }
                )
            }
        }
    }
}

// ── Gallery pieces ────────────────────────────────────────────────────────

@Composable
private fun MediaTabs(current: MediaTab, onSelect: (MediaTab) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.navigationBarsPadding()
        ) {
            items(MediaTab.values()) { t ->
                FilterChip(
                    selected = t == current,
                    onClick = { onSelect(t) },
                    label = { Text(t.label) },
                    leadingIcon = if (t == current) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    entries: List<MediaEntry>,
    selected: Set<String>,
    onLongPress: (MediaEntry) -> Unit,
    onClick: (MediaEntry) -> Unit
) {
    // Group by day, newest first, like the stock gallery.
    val sections = remember(entries) {
        entries.groupBy { dayLabel(it.date) }
            .entries
            .sortedByDescending { it.value.maxOfOrNull { e -> e.date } ?: 0L }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        sections.forEach { (label, dayEntries) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "header-$label") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${dayEntries.size} · ${FileActions.formatBytes(dayEntries.sumOf { it.size })}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(dayEntries, key = { it.path }) { entry ->
                MediaTile(
                    entry = entry,
                    isSelected = entry.path in selected,
                    onClick = { onClick(entry) },
                    onLongClick = { onLongPress(entry) }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    entry: MediaEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (entry.kind == MediaKind.IMAGE) {
            AsyncImage(
                model = entry.uri,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Video: MediaStore provides a thumbnail for free.
            AsyncImage(
                model = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, entry.id
                ),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Per-tile size badge (the reference gallery shows this on every tile).
        Text(
            FileActions.formatBytes(entry.size),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
            )
        }
    }
}

@Composable
private fun AudioList(
    entries: List<MediaEntry>,
    selected: Set<String>,
    onLongPress: (MediaEntry) -> Unit,
    onClick: (MediaEntry) -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(entry) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    AsyncImage(
                        model = entry.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${entry.bucket} · ${FileActions.formatBytes(entry.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.path in selected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

private fun dayLabel(ms: Long): String {
    if (ms <= 0) return "Unknown date"
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Today"
        sameYear && dayDiff == 1 -> "Yesterday"
        sameYear -> SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

// ── Player ────────────────────────────────────────────────────────────────

@Composable
private fun PlayerScreen(
    exoPlayer: ExoPlayer,
    playPath: String,
    mediaFiles: List<MediaEntry>,
    onBack: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPrev: () -> Unit
) {
    val context = LocalContext.current
    val file = File(playPath)
    val isVideo = FileActions.isVideo(playPath)
    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }

    LaunchedEffect(exoPlayer) {
        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        })
    }

    val displayName = playPath.substringAfterLast('/')
    val artistName = if (isVideo) "Video" else file.parentFile?.name ?: "Audio"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { FileActions.share(context, file) }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = true
                    }
                },
                update = { playerView -> playerView.player = exoPlayer },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(200.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(80.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    artistName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(24.dp))
                val duration = exoPlayer.duration
                if (duration > 0) {
                    var position by remember { mutableStateOf(0L) }
                    LaunchedEffect(exoPlayer) {
                        while (true) {
                            position = exoPlayer.currentPosition
                            kotlinx.coroutines.delay(500)
                        }
                    }
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.toFloat()),
                        valueRange = 0f..duration.toFloat(),
                        onValueChange = { exoPlayer.seekTo(it.toLong()) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            position.formatMs(),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            duration.formatMs(),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayPrev) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            }) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            IconButton(onClick = onPlayNext) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White.copy(alpha = 0.8f)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun Long.formatMs(): String {
    val totalSeconds = this / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
