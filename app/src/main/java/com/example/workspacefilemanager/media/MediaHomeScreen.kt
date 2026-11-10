package com.example.workspacefilemanager.media

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ── Model ─────────────────────────────────────────────────────────────────

enum class Category(val label: String) {
    DOWNLOADS("Downloads"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documents"),
    APPS("Apps")
}

/** Where a category tap lands. */
sealed class HomeSection {
    data object Root : HomeSection()
    data class Files(val category: Category) : HomeSection()
    data object AppList : HomeSection()
    data object Starred : HomeSection()
    data object Safe : HomeSection()
}

data class AppUsage(val label: String, val packageName: String, val size: Long)

// ── ViewModel ─────────────────────────────────────────────────────────────

class MediaHomeViewModel : ViewModel() {

    private val _sizes = MutableStateFlow<Map<Category, Long>>(emptyMap())
    private val _counts = MutableStateFlow<Map<Category, Int>>(emptyMap())
    private val _recent = MutableStateFlow<List<MediaEntry>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val _internalFree = MutableStateFlow(0L)
    private val _internalTotal = MutableStateFlow(0L)
    private val _appSize = MutableStateFlow(0L)
    private val _starredCount = MutableStateFlow(0)
    private val _safeCount = MutableStateFlow(0)

    val sizes: StateFlow<Map<Category, Long>> = _sizes.asStateFlow()
    val counts: StateFlow<Map<Category, Int>> = _counts.asStateFlow()
    val recent: StateFlow<List<MediaEntry>> = _recent.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val internalFree: StateFlow<Long> = _internalFree.asStateFlow()
    val internalTotal: StateFlow<Long> = _internalTotal.asStateFlow()
    val appSize: StateFlow<Long> = _appSize.asStateFlow()
    val starredCount: StateFlow<Int> = _starredCount.asStateFlow()
    val safeCount: StateFlow<Int> = _safeCount.asStateFlow()

    private var loaded = false

    fun load(context: android.content.Context) {
        if (loaded) {
            refreshCounters(context)
            return
        }
        loaded = true
        val app = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true

            val sizes = mutableMapOf<Category, Long>()
            val counts = mutableMapOf<Category, Int>()

            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Category.IMAGES,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Category.VIDEOS,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Category.AUDIO
            ).forEach { (uri, category) ->
                var total = 0L
                var count = 0
                try {
                    app.contentResolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.SIZE),
                        null, null, null
                    )?.use { c ->
                        val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                        while (c.moveToNext()) {
                            total += c.getLong(idx)
                            count++
                        }
                    }
                } catch (_: Throwable) {
                }
                sizes[category] = total
                counts[category] = count
            }

            // Downloads + Documents are folder scans.
            val root = Environment.getExternalStorageDirectory()
            val downloadDir = File(root, "Download")
            val docsDirs = listOf(
                File(root, "Documents"),
                downloadDir,
                File(root, "Books")
            )

            val downloads = mutableListOf<File>()
            val documents = mutableListOf<File>()
            downloadDir.walkTopDown().maxDepth(4).forEach { f ->
                if (f.isFile) downloads.add(f)
            }
            docsDirs.forEach { dir ->
                dir.walkTopDown().maxDepth(4).forEach { f ->
                    if (f.isFile && isDocument(f.name)) documents.add(f)
                }
            }
            sizes[Category.DOWNLOADS] = downloads.sumOf { it.length() }
            counts[Category.DOWNLOADS] = downloads.size
            sizes[Category.DOCUMENTS] = documents.sumOf { it.length() }
            counts[Category.DOCUMENTS] = documents.size

            // Installed apps
            var appsSize = 0L
            var appCount = 0
            try {
                val pm = app.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
                    val apk = File(info.sourceDir)
                    if (apk.exists()) {
                        appsSize += apk.length()
                        appCount++
                    }
                }
            } catch (_: Throwable) {
            }
            sizes[Category.APPS] = appsSize
            counts[Category.APPS] = appCount

            // Storage + app footprint
            val stat = try {
                StatFs(root.absolutePath)
            } catch (t: Throwable) {
                null
            }
            _internalFree.value = stat?.availableBytes ?: 0L
            _internalTotal.value = (stat?.blockCountLong ?: 0L) * (stat?.blockSizeLong ?: 1L)
            _appSize.value = appFootprint(app)

            _sizes.value = sizes
            _counts.value = counts
            _recent.value = recentMedia(app)
            refreshCounters(app)
            _loading.value = false
        }
    }

    fun refreshCounters(context: android.content.Context) {
        _starredCount.value = Starred.paths(context).size
        _safeCount.value = SafeFolder.files(context).size
    }

    private fun appFootprint(context: android.content.Context): Long {
        fun sizeOf(dir: File?): Long {
            if (dir == null || !dir.exists()) return 0L
            return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val apk = context.applicationInfo?.sourceDir?.let { File(it) }?.length() ?: 0L
        return sizeOf(context.filesDir) + sizeOf(context.cacheDir) + apk
    }

    suspend fun filesFor(category: Category): List<File> = withContext(Dispatchers.IO) {
        val root = Environment.getExternalStorageDirectory()
        when (category) {
            Category.DOWNLOADS ->
                File(root, "Download").walkTopDown().maxDepth(4)
                    .filter { it.isFile && it.name !in setOf(".nomedia") }
                    .sortedByDescending { it.lastModified() }
                    .toList()

            Category.DOCUMENTS -> listOf(
                File(root, "Documents"),
                File(root, "Download"),
                File(root, "Books")
            ).flatMap { dir ->
                dir.walkTopDown().maxDepth(4).filter { it.isFile && isDocument(it.name) }
            }.sortedByDescending { it.lastModified() }

            else -> emptyList()
        }
    }

    suspend fun apps(): List<AppUsage> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext emptyList()
        try {
            ctx.packageManager.getInstalledApplications(0)
                .mapNotNull { info ->
                    val apk = File(info.sourceDir)
                    if (!apk.exists()) null
                    else AppUsage(
                        label = ctx.packageManager.getApplicationLabel(info).toString(),
                        packageName = info.packageName,
                        size = apk.length()
                    )
                }
                .sortedByDescending { it.size }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private var appContext: android.content.Context? = null

    fun setContext(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private fun recentMedia(context: android.content.Context): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaKind.IMAGE,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaKind.VIDEO
        ).forEach { (collection, kind) ->
            try {
                context.contentResolver.query(
                    collection,
                    arrayOf(
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.DATA
                    ),
                    null, null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    var guard = 0
                    while (c.moveToNext() && guard < 12) {
                        guard++
                        val id = c.getLong(idCol)
                        val path = c.getString(dataCol) ?: continue
                        out += MediaEntry(
                            id = id,
                            uri = android.content.ContentUris.withAppendedId(collection, id),
                            path = path,
                            name = c.getString(nameCol) ?: File(path).name,
                            size = c.getLong(sizeCol),
                            date = c.getLong(dateCol) * 1000L,
                            kind = kind,
                            bucket = ""
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return out.sortedByDescending { it.date }.take(12)
    }

    private fun isDocument(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
            "odp", "epub", "txt", "csv", "rtf"
        )
}

// ── Home screen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaHomeScreen(
    onOpenGallery: (MediaTab) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenStorage: () -> Unit
) {
    val context = LocalContext.current
    val vm: MediaHomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) {
        vm.setContext(context)
        vm.load(context)
    }

    val sizes by vm.sizes.collectAsState()
    val counts by vm.counts.collectAsState()
    val recent by vm.recent.collectAsState()
    val loading by vm.loading.collectAsState()
    val free by vm.internalFree.collectAsState()
    val total by vm.internalTotal.collectAsState()
    val appSize by vm.appSize.collectAsState()
    val starredCount by vm.starredCount.collectAsState()
    val safeCount by vm.safeCount.collectAsState()

    var section by remember { mutableStateOf<HomeSection>(HomeSection.Root) }
    var showCacheDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    when (val current = section) {
        is HomeSection.Root -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Browse", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Tap a category to jump straight in",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { onOpenGallery(MediaTab.ALL) }) {
                                Icon(Icons.Default.Search, contentDescription = "Search media")
                            }
                            IconButton(onClick = { vm.load(context) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh sizes")
                            }
                        }
                    )
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // ── Recent ───────────────────────────────────────────
                    if (recent.isNotEmpty()) {
                        item {
                            SectionRow(
                                title = "Recent",
                                action = "See all",
                                onAction = { onOpenGallery(MediaTab.ALL) }
                            )
                        }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(recent, key = { it.path }) { entry ->
                                    RecentCard(entry = entry, onClick = { onOpenFile(entry.path) })
                                }
                            }
                        }
                    }

                    // ── Categories ───────────────────────────────────────
                    item { SectionRow(title = "Categories") }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            val rows = listOf(
                                listOf(Category.DOWNLOADS, Category.IMAGES),
                                listOf(Category.VIDEOS, Category.AUDIO),
                                listOf(Category.DOCUMENTS, Category.APPS)
                            )
                            rows.forEach { pair ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    pair.forEach { cat ->
                                        CategoryCard(
                                            category = cat,
                                            size = sizes[cat] ?: 0L,
                                            count = counts[cat] ?: 0,
                                            loading = loading,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                when (cat) {
                                                    Category.IMAGES -> onOpenGallery(MediaTab.PHOTOS)
                                                    Category.VIDEOS -> onOpenGallery(MediaTab.VIDEOS)
                                                    Category.AUDIO -> onOpenGallery(MediaTab.AUDIO)
                                                    Category.APPS -> section = HomeSection.AppList
                                                    else -> section = HomeSection.Files(cat)
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }

                    // ── Collections ──────────────────────────────────────
                    item { SectionRow(title = "Collections") }
                    item {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CollectionCard(
                                icon = Icons.Default.Star,
                                title = "Starred",
                                subtitle = if (starredCount == 0) "Nothing yet"
                                else "$starredCount item(s)",
                                modifier = Modifier.weight(1f),
                                onClick = { section = HomeSection.Starred }
                            )
                            CollectionCard(
                                icon = Icons.Default.Lock,
                                title = "Safe folder",
                                subtitle = if (safeCount == 0) "Empty"
                                else "$safeCount item(s)",
                                modifier = Modifier.weight(1f),
                                onClick = { section = HomeSection.Safe }
                            )
                        }
                    }

                    // ── All storage ──────────────────────────────────────
                    item { SectionRow(title = "All storage") }
                    item {
                        Column(
                            Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StorageCard(
                                icon = Icons.Default.PhoneAndroid,
                                title = "Internal storage",
                                subtitle = "${FileActions.formatBytes(free)} free of " +
                                    FileActions.formatBytes(total),
                                fraction = if (total > 0) {
                                    ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else 0f,
                                onClick = onOpenStorage
                            )
                            StorageCard(
                                icon = Icons.Default.Apps,
                                title = "App & cache",
                                subtitle = FileActions.formatBytes(appSize) + " used by this app",
                                fraction = null,
                                onClick = { showCacheDialog = true }
                            )
                        }
                    }
                }
            }
        }

        is HomeSection.Files -> FileCategoryScreen(
            category = current.category,
            onBack = { section = HomeSection.Root },
            onOpenFile = onOpenFile,
            onChanged = { vm.refreshCounters(context) },
            loadFiles = { vm.filesFor(current.category) }
        )

        HomeSection.AppList -> AppsScreen(onBack = { section = HomeSection.Root }) {
            vm.apps()
        }

        is HomeSection.Starred -> StarredScreen(
            onBack = { section = HomeSection.Root },
            onOpenFile = onOpenFile,
            onChanged = { vm.refreshCounters(context) }
        )

        is HomeSection.Safe -> SafeFolderScreen(
            onBack = { section = HomeSection.Root },
            onOpenFile = onOpenFile,
            onChanged = { vm.refreshCounters(context) }
        )
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
            title = { Text("App & cache") },
            text = {
                Text(
                    "This app stores ${FileActions.formatBytes(appSize)} of models, caches and " +
                        "workspace data. Clearing the cache only removes temporary files."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                        showCacheDialog = false
                        FileActions.toast(context, "Cache cleared")
                        vm.load(context)
                    }
                }) { Text("Clear cache") }
            },
            dismissButton = {
                TextButton(onClick = { showCacheDialog = false }) { Text("Close") }
            }
        )
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionRow(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RecentCard(entry: MediaEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 108.dp, height = 148.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = if (entry.kind == MediaKind.VIDEO)
                android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, entry.id
                )
            else entry.uri,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (entry.kind == MediaKind.VIDEO) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            )
        }
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
    }
}

@Composable
private fun CategoryCard(
    category: Category,
    size: Long,
    count: Int,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val icon: ImageVector = when (category) {
        Category.DOWNLOADS -> Icons.Default.Download
        Category.IMAGES -> Icons.Default.Image
        Category.VIDEOS -> Icons.Default.Movie
        Category.AUDIO -> Icons.Default.MusicNote
        Category.DOCUMENTS -> Icons.Default.Description
        Category.APPS -> Icons.Default.Apps
    }
    val tint = when (category) {
        Category.DOWNLOADS -> MaterialTheme.colorScheme.primary
        Category.IMAGES -> MaterialTheme.colorScheme.secondary
        Category.VIDEOS -> MaterialTheme.colorScheme.tertiary
        Category.AUDIO -> MaterialTheme.colorScheme.primary
        Category.DOCUMENTS -> MaterialTheme.colorScheme.secondary
        Category.APPS -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(category.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        loading -> "…"
                        size > 0 -> FileActions.formatBytes(size)
                        else -> "Empty"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (count > 0) {
                    Text(
                        "$count file(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StorageCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    fraction: Float?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

// ── Section screens ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileCategoryScreen(
    category: Category,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onChanged: () -> Unit,
    loadFiles: suspend () -> List<File>
) {
    val context = LocalContext.current
    var files by remember(category) { mutableStateOf<List<File>?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(category, reload) {
        files = null
        files = loadFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(category.label)
                        val list = files
                        if (list != null) {
                            Text(
                                "${list.size} item(s) · ${FileActions.formatBytes(list.sumOf { it.length() })}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val list = files
        if (list == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else if (list.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                icon = Icons.Default.FolderOpen,
                title = "Nothing in ${category.label}",
                subtitle = "Files you download or save here will appear in this category."
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.absolutePath }) { f ->
                    FileRowWithActions(
                        file = f,
                        onClick = { onOpenFile(f.absolutePath) },
                        onDeleted = { reload++; onChanged() },
                        context = context
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsScreen(onBack: () -> Unit, loadApps: suspend () -> List<AppUsage>) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppUsage>?>(null) }
    LaunchedEffect(Unit) { apps = loadApps() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Apps")
                        apps?.let {
                            Text(
                                "${it.size} installed · ${FileActions.formatBytes(it.sumOf { a -> a.size })}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val list = apps
        if (list == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(list, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${app.packageName}")
                                        )
                                    )
                                } catch (t: Throwable) {
                                    FileActions.toast(context, "Cannot open app info")
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            FileActions.formatBytes(app.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarredScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(Starred.paths(context)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { files = Starred.paths(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Starred") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                icon = Icons.Default.StarBorder,
                title = "No starred items",
                subtitle = "Long-press a file in the Files tab and choose \"Star\" to keep it here."
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(files, key = { it.absolutePath }) { f ->
                    FileRowWithActions(
                        file = f,
                        onClick = { onOpenFile(f.absolutePath) },
                        onDeleted = {
                            reload++
                            onChanged()
                        },
                        context = context,
                        starred = true,
                        onUnstar = {
                            Starred.toggle(context, f)
                            reload++
                            onChanged()
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafeFolderScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var files by remember { mutableStateOf(SafeFolder.files(context)) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { files = SafeFolder.files(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Safe folder")
                        Text(
                            "${files.size} item(s) · ${FileActions.formatBytes(files.sumOf { it.length() })}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (files.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                icon = Icons.Default.Lock,
                title = "Safe folder is empty",
                subtitle = "In the Files tab, long-press a file and choose \"Move to Safe folder\". " +
                    "It moves into app-private storage other apps cannot read."
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(files, key = { it.absolutePath }) { f ->
                    FileRowWithActions(
                        file = f,
                        onClick = { onOpenFile(f.absolutePath) },
                        onDeleted = {
                            reload++
                            onChanged()
                        },
                        context = context
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileRowWithActions(
    file: File,
    context: android.content.Context,
    onClick: () -> Unit,
    onDeleted: () -> Unit,
    starred: Boolean = false,
    onUnstar: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (FileActions.isImage(file.name)) Icons.Default.Image
            else if (FileActions.isVideo(file.name)) Icons.Default.Movie
            else if (FileActions.isAudio(file.name)) Icons.Default.MusicNote
            else Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${FileActions.formatBytes(file.length())} · ${FileActions.formatDate(file.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Open") },
                    onClick = {
                        menuOpen = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        menuOpen = false
                        FileActions.share(context, file)
                    }
                )
                if (onUnstar != null) {
                    DropdownMenuItem(
                        text = { Text(if (starred) "Remove star" else "Star") },
                        onClick = {
                            menuOpen = false
                            onUnstar()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${file.name}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val result = FileActions.delete(context, file)
                    FileActions.toast(context, result.message ?: "Deleted ${file.name}")
                    confirmDelete = false
                    onDeleted()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
