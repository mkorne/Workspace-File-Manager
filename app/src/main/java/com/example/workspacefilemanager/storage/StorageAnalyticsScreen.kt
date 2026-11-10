package com.example.workspacefilemanager.storage

import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.workspacefilemanager.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

// ── ViewModel ─────────────────────────────────────────────────────────────

class StorageAnalyticsViewModel : ViewModel() {

    private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val _folderSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _duplicates = MutableStateFlow<List<List<File>>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    private val _scanningDupes = MutableStateFlow(false)
    private val _deletedCount = MutableStateFlow(0)
    private val _freedBytes = MutableStateFlow(0L)
    private val _largeFiles = MutableStateFlow<List<FileSummary>>(emptyList())
    private val _scanningLarge = MutableStateFlow(false)
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    private val _aiSuggestion = MutableStateFlow<String?>(null)
    private val _aiLoading = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)

    val folderSizes: StateFlow<Map<String, Long>> = _folderSizes.asStateFlow()
    val duplicates: StateFlow<List<List<File>>> = _duplicates.asStateFlow()
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    val scanningDupes: StateFlow<Boolean> = _scanningDupes.asStateFlow()
    val deletedCount: StateFlow<Int> = _deletedCount.asStateFlow()
    val freedBytes: StateFlow<Long> = _freedBytes.asStateFlow()
    val largeFiles: StateFlow<List<FileSummary>> = _largeFiles.asStateFlow()
    val scanningLarge: StateFlow<Boolean> = _scanningLarge.asStateFlow()
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()
    val aiSuggestion: StateFlow<String?> = _aiSuggestion.asStateFlow()
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun toggleSelection(path: String) {
        _selected.value = _selected.value.let {
            if (it.contains(path)) it - path else it + path
        }
    }

    fun selectAllLarge() {
        _selected.value = _largeFiles.value.map { it.path }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun scanStorage() {
        viewModelScope.launch(Dispatchers.Default) {
            _scanning.value = true
            val root = Environment.getExternalStorageDirectory()
            val map = mutableMapOf<String, Long>()
            scanDir(root, map, depth = 0)
            _folderSizes.value = map.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .take(30)
                .associate { it.key to it.value }
            _scanning.value = false
        }
    }

    fun findDuplicates() {
        viewModelScope.launch(Dispatchers.Default) {
            _scanningDupes.value = true
            val root = Environment.getExternalStorageDirectory()
            val bySize = mutableMapOf<Long, MutableList<File>>()
            root.walkTopDown()
                .onEnter { it.name != "Android" }
                .filter { it.isFile && it.length() > 0 }
                .forEach { f -> bySize.getOrPut(f.length()) { mutableListOf() }.add(f) }

            val digest = MessageDigest.getInstance("SHA-256")
            val byHash = mutableMapOf<String, MutableList<File>>()
            bySize.values.filter { it.size > 1 }.forEach { group ->
                group.forEach { f ->
                    try {
                        digest.reset()
                        val hash = f.inputStream().use { stream ->
                            val buf = ByteArray(8192)
                            var read: Int
                            while (stream.read(buf).also { read = it } != -1) {
                                digest.update(buf, 0, read)
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        }
                        byHash.getOrPut(hash) { mutableListOf() }.add(f)
                    } catch (_: Exception) {
                    }
                }
            }
            _duplicates.value = byHash.values.filter { it.size > 1 }
            _scanningDupes.value = false
        }
    }

    fun scanLargeFiles(thresholdMB: Long = 50L) {
        viewModelScope.launch(Dispatchers.Default) {
            _scanningLarge.value = true
            val root = Environment.getExternalStorageDirectory()
            val threshold = thresholdMB * 1024 * 1024
            val files = root.walkTopDown()
                .onEnter { it.name != "Android" }
                .filter { it.isFile && it.length() >= threshold }
                .map { FileSummary(it.absolutePath, it.length(), it.name) }
                .sortedByDescending { it.size }
                .take(100)
                .toList()
            _largeFiles.value = files
            _selected.value = emptySet()
            _scanningLarge.value = false
        }
    }

    fun askAIForCleanup(prompt: String) {
        viewModelScope.launch {
            _aiLoading.value = true
            val dupes = _duplicates.value.sumOf { it.size - 1 }
            val largeTotal = _largeFiles.value.sumOf { it.size }
            _aiSuggestion.value = when {
                prompt.contains("duplicate", true) && dupes > 0 ->
                    "$dupes duplicate copies found. Deleting the extra copies frees " +
                        "${FileActions.formatBytes(_duplicates.value.sumOf { g -> g.drop(1).sumOf { it.length() } })} " +
                        "and keeps one copy of every file."
                prompt.contains("large", true) && _largeFiles.value.isNotEmpty() ->
                    "${_largeFiles.value.size} files over 50 MB use ${FileActions.formatBytes(largeTotal)}. " +
                        "The biggest is ${_largeFiles.value.first().name}. Select the ones you no longer need below."
                prompt.contains("cache", true) ->
                    "App caches live in Android/data and Android/obb. They are regenerated on demand, " +
                        "so clearing them is safe — open the Files tab and browse there."
                _folderSizes.value.isNotEmpty() ->
                    "Your largest folder is ${_folderSizes.value.entries.first().key.substringAfterLast('/')} " +
                        "at ${FileActions.formatBytes(_folderSizes.value.entries.first().value)}."
                else -> "Run Scan on Largest Folders and Large Files first, then I can suggest what to remove."
            }
            _aiLoading.value = false
        }
    }

    /** Delete exactly what the user ticked. */
    fun deleteSelected() {
        val ctx = appContext ?: return
        val paths = _selected.value
        if (paths.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            var freed = 0L
            var failure: String? = null
            paths.forEach { path ->
                val file = File(path)
                val size = file.length()
                val result = FileActions.delete(ctx, file)
                if (result.success) {
                    count++
                    freed += size
                } else if (failure == null) {
                    failure = result.message
                }
            }
            _largeFiles.value = _largeFiles.value.filterNot { it.path in paths }
            _selected.value = emptySet()
            _deletedCount.value += count
            _freedBytes.value += freed
            _message.value = if (count > 0) {
                "Deleted $count file(s) · freed ${FileActions.formatBytes(freed)}"
            } else {
                failure ?: "Nothing was deleted"
            }
        }
    }

    fun deleteLargeFile(summary: FileSummary) {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = FileActions.delete(ctx, File(summary.path))
            if (result.success) {
                _largeFiles.value = _largeFiles.value.filterNot { it.path == summary.path }
                _selected.value = _selected.value - summary.path
                _deletedCount.value += 1
                _freedBytes.value += summary.size
                _message.value = "Deleted ${summary.name} · freed ${FileActions.formatBytes(summary.size)}"
            } else {
                _message.value = result.message ?: "Could not delete ${summary.name}"
            }
        }
    }

    fun deleteDuplicateGroup(index: Int) {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val group = _duplicates.value.getOrNull(index) ?: return@launch
            var count = 0
            var freed = 0L
            group.drop(1).forEach { file ->
                val size = file.length()
                if (FileActions.delete(ctx, file).success) {
                    count++
                    freed += size
                }
            }
            _deletedCount.value += count
            _freedBytes.value += freed
            _duplicates.value = _duplicates.value.toMutableList().apply { removeAt(index) }
            _message.value = "Removed $count duplicate(s) · freed ${FileActions.formatBytes(freed)}"
        }
    }

    fun destroyDuplicates() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            var freed = 0L
            _duplicates.value.forEach { group ->
                group.drop(1).forEach { file ->
                    val size = file.length()
                    if (FileActions.delete(ctx, file).success) {
                        count++
                        freed += size
                    }
                }
            }
            _deletedCount.value = count
            _freedBytes.value = freed
            _duplicates.value = emptyList()
            _message.value = if (count > 0)
                "Removed $count duplicate(s) · freed ${FileActions.formatBytes(freed)}"
            else "No duplicates could be deleted"
        }
    }

    fun deleteSingleDuplicate(path: String) {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = FileActions.delete(ctx, File(path))
            if (result.success) {
                _duplicates.value = _duplicates.value
                    .map { g -> g.filterNot { it.absolutePath == path } }
                    .filter { it.isNotEmpty() }
                _deletedCount.value += 1
                _message.value = "Deleted ${path.substringAfterLast('/')}"
            } else {
                _message.value = result.message ?: "Could not delete that file"
            }
        }
    }

    private fun scanDir(dir: File, map: MutableMap<String, Long>, depth: Int): Long {
        var total = 0L
        dir.listFiles()?.forEach { child ->
            total += if (child.isDirectory) scanDir(child, map, depth + 1) else child.length()
        }
        // Keep nested folders visible, but stop at a sane depth to bound memory.
        if (total > 0 && depth <= 3) map[dir.absolutePath] = total
        return total
    }

    fun freeSpace(): Long = try {
        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        stat.availableBytes
    } catch (t: Throwable) {
        0L
    }
}

data class FileSummary(val path: String, val size: Long, val name: String)

// ── Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyticsScreen() {
    val context = LocalContext.current
    val vm: StorageAnalyticsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) { vm.init(context) }

    val folderSizes by vm.folderSizes.collectAsState()
    val duplicates by vm.duplicates.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanningDupes by vm.scanningDupes.collectAsState()
    val deletedCount by vm.deletedCount.collectAsState()
    val freedBytes by vm.freedBytes.collectAsState()
    val largeFiles by vm.largeFiles.collectAsState()
    val scanningLarge by vm.scanningLarge.collectAsState()
    val selected by vm.selected.collectAsState()
    val aiSuggestion by vm.aiSuggestion.collectAsState()
    val aiLoading by vm.aiLoading.collectAsState()
    val message by vm.message.collectAsState()

    var showDupConfirm by remember { mutableStateOf(false) }
    var showSelectedConfirm by remember { mutableStateOf(false) }
    var expandedAi by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface every operation's result instead of failing silently.
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessage()
        }
    }

    if (showDupConfirm) {
        AlertDialog(
            onDismissRequest = { showDupConfirm = false },
            title = { Text("Remove duplicates?") },
            text = {
                Text(
                    "${duplicates.sumOf { it.size - 1 }} extra copies will be deleted.\n" +
                        "One copy of each file is always kept."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.destroyDuplicates(); showDupConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDupConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showSelectedConfirm) {
        val total = largeFiles.filter { it.path in selected }.sumOf { it.size }
        AlertDialog(
            onDismissRequest = { showSelectedConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Delete ${selected.size} selected file(s)?") },
            text = {
                Text(
                    "${FileActions.formatBytes(total)} will be permanently deleted. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteSelected(); showSelectedConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelectedConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Storage Analytics", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${FileActions.formatBytes(vm.freeSpace())} free",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // ── Result summary ───────────────────────────────────────────
            if (deletedCount > 0) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "$deletedCount file(s) deleted · ${FileActions.formatBytes(freedBytes)} freed",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // ── AI assistant ─────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedAi = !expandedAi }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("AI Cleanup Assistant", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (expandedAi) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                        AnimatedVisibility(visible = expandedAi) {
                            Column(
                                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val prompts = buildList {
                                    if (duplicates.isNotEmpty()) add("Which duplicates can I delete safely?")
                                    if (largeFiles.isNotEmpty()) add("What are my large files costing me?")
                                    add("Can I clear app caches?")
                                }
                                prompts.forEach { prompt ->
                                    OutlinedButton(
                                        onClick = { vm.askAIForCleanup(prompt) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                if (aiLoading) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                aiSuggestion?.let {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Largest folders ──────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Analytics,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Largest Folders", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { vm.scanStorage() }, enabled = !scanning) {
                                if (scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else Text("Scan")
                            }
                        }
                        if (folderSizes.isEmpty()) {
                            Text(
                                if (scanning) "Scanning…" else "Tap Scan to analyse storage usage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        } else {
                            val max = folderSizes.values.maxOrNull() ?: 1L
                            Spacer(Modifier.height(12.dp))
                            folderSizes.entries.take(8).forEach { (path, size) ->
                                FolderBar(
                                    label = path.removePrefix(
                                        Environment.getExternalStorageDirectory().absolutePath
                                    ).ifEmpty { "/" },
                                    size = size,
                                    fraction = (size.toFloat() / max.toFloat()).coerceIn(0.02f, 1f)
                                )
                            }
                        }
                    }
                }
            }

            // ── Duplicates ───────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Duplicate Destructor", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = { vm.findDuplicates() },
                                enabled = !scanningDupes
                            ) {
                                if (scanningDupes) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else Text("Find")
                            }
                            if (duplicates.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { showDupConfirm = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text("Clean") }
                            }
                        }
                        Text(
                            if (duplicates.isEmpty())
                                "Hashes files with SHA-256 to find byte-identical copies."
                            else
                                "${duplicates.sumOf { it.size - 1 }} extra copies · " +
                                    FileActions.formatBytes(
                                        duplicates.sumOf { g -> g.drop(1).sumOf { it.length() } }
                                    ) + " reclaimable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            items(duplicates) { group ->
                val index = duplicates.indexOf(group)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${group.size} identical · keep 1",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.deleteDuplicateGroup(index) }) {
                                Text("Delete ${group.size - 1} copies")
                            }
                        }
                        group.forEach { file ->
                            Row(
                                Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    file.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { vm.deleteSingleDuplicate(file.absolutePath) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete this copy",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Large files ──────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Large Files (≥50 MB)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = { vm.scanLargeFiles() }, enabled = !scanningLarge) {
                                if (scanningLarge) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else Text("Scan")
                            }
                        }
                        if (largeFiles.isEmpty()) {
                            Text(
                                if (scanningLarge) "Scanning…" else "Tap Scan to find the biggest files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Row(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${largeFiles.size} files · ${FileActions.formatBytes(largeFiles.sumOf { it.size })}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.selectAllLarge() }) { Text("Select all") }
                            }
                        }
                    }
                }
            }

            items(largeFiles, key = { it.path }) { summary ->
                val checked = summary.path in selected
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleSelection(summary.path) },
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (checked)
                            MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { vm.toggleSelection(summary.path) })
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                summary.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                summary.path
                                    .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                                    .substringBeforeLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            FileActions.formatBytes(summary.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { vm.deleteLargeFile(summary) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete ${summary.name}",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Sticky delete bar for the selected rows.
        if (selected.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selected.size} selected · " +
                            FileActions.formatBytes(
                                largeFiles.filter { it.path in selected }.sumOf { it.size }
                            ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { showSelectedConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun FolderBar(label: String, size: Long, fraction: Float) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                FileActions.formatBytes(size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
