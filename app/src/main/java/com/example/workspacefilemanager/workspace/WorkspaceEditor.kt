package com.example.workspacefilemanager.workspace

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.workspacefilemanager.ai.AiViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ── Model ─────────────────────────────────────────────────────────────────

enum class BlockType(val label: String) {
    TEXT("Text"),
    H1("Heading 1"),
    H2("Heading 2"),
    H3("Heading 3"),
    BULLET("Bulleted list"),
    NUMBERED("Numbered list"),
    TODO("To-do"),
    QUOTE("Quote"),
    CODE("Code"),
    DIVIDER("Divider")
}

data class Block(
    val id: String = UUID.randomUUID().toString(),
    val type: BlockType = BlockType.TEXT,
    val text: String = "",
    val checked: Boolean = false
)

data class WorkspaceDoc(
    val title: String = "Untitled",
    val blocks: List<Block> = listOf(Block()),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceFile: String? = null
) {
    /** Blocks saved to disk; dividers carry no text. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("updatedAt", updatedAt)
        put("sourceFile", sourceFile ?: JSONObject.NULL)
        put("blocks", JSONArray().also { arr ->
            blocks.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("type", b.type.name)
                    put("text", b.text)
                    put("checked", b.checked)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): WorkspaceDoc {
            val arr = o.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                Block(
                    id = b.optString("id", UUID.randomUUID().toString()),
                    type = runCatching { BlockType.valueOf(b.optString("type", "TEXT")) }
                        .getOrDefault(BlockType.TEXT),
                    text = b.optString("text", ""),
                    checked = b.optBoolean("checked", false)
                )
            }.ifEmpty { listOf(Block()) }
            return WorkspaceDoc(
                title = o.optString("title", "Untitled"),
                blocks = blocks,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                sourceFile = if (o.isNull("sourceFile")) null else o.optString("sourceFile", "")
            )
        }
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────

class WorkspaceEditorViewModel(private val appContext: Context) : ViewModel() {

    private val storeFile = File(appContext.filesDir, "workspace_doc.json")

    private val _doc = MutableStateFlow(WorkspaceDoc())
    val doc: StateFlow<WorkspaceDoc> = _doc.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _lastSavedAt = MutableStateFlow<Long?>(null)
    val lastSavedAt: StateFlow<Long?> = _lastSavedAt.asStateFlow()

    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private var autosaveJob: Job? = null

    /**
     * Autosave: waits for 700 ms of quiet, then writes to disk.
     * Every edit calls this, so a note is never lost even if the app is killed.
     */
    private fun scheduleAutosave() {
        _hasUnsavedChanges.value = true
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(700)
            persist()
        }
    }

    private suspend fun persist() {
        val snapshot = _doc.value
        _saving.value = true
        withContext(Dispatchers.IO) {
            runCatching {
                storeFile.parentFile?.mkdirs()
                val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
                tmp.writeText(snapshot.toJson().toString())
                if (storeFile.exists()) storeFile.delete()
                tmp.renameTo(storeFile)
            }
        }
        _lastSavedAt.value = System.currentTimeMillis()
        _hasUnsavedChanges.value = false
        _saving.value = false
    }

    /** Flush immediately — used when the screen leaves the foreground. */
    fun saveNow() {
        autosaveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val restored = runCatching {
                if (storeFile.exists()) WorkspaceDoc.fromJson(JSONObject(storeFile.readText()))
                else null
            }.getOrNull()
            if (restored != null) {
                _doc.value = restored
                _lastSavedAt.value = restored.updatedAt
            }
        }
    }

    // ── Loading ───────────────────────────────────────────────────────────

    /** Load a plain-text / markdown file from disk into the editor as blocks. */
    fun loadFile(path: String) {
        viewModelScope.launch {
            val file = File(path)
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!file.exists()) return@runCatching null
                    val text = file.readText()
                    WorkspaceDoc(
                        title = file.nameWithoutExtension,
                        blocks = parseToBlocks(text),
                        updatedAt = System.currentTimeMillis(),
                        sourceFile = file.absolutePath
                    )
                }.getOrNull()
            }
            if (loaded != null) {
                _doc.value = loaded
                save()
            }
        }
    }

    private fun parseToBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var inCode = false
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCode) {
                        inCode = false
                    } else {
                        inCode = true
                        blocks += Block(type = BlockType.CODE)
                    }
                }
                inCode -> {
                    val code = blocks.lastOrNull()
                    if (code != null && code.type == BlockType.CODE) {
                        blocks[blocks.lastIndex] = code.copy(
                            text = if (code.text.isEmpty()) line else code.text + "\n" + line
                        )
                    }
                }
                line.startsWith("### ") ->
                    blocks += Block(type = BlockType.H3, text = line.removePrefix("### "))
                line.startsWith("## ") ->
                    blocks += Block(type = BlockType.H2, text = line.removePrefix("## "))
                line.startsWith("# ") ->
                    blocks += Block(type = BlockType.H1, text = line.removePrefix("# "))
                line.startsWith("> ") ->
                    blocks += Block(type = BlockType.QUOTE, text = line.removePrefix("> "))
                line.startsWith("- [ ] ") ->
                    blocks += Block(type = BlockType.TODO, text = line.removePrefix("- [ ] "))
                line.startsWith("- [x] ") || line.startsWith("- [X] ") ->
                    blocks += Block(type = BlockType.TODO, text = line.substring(6), checked = true)
                line.startsWith("- ") || line.startsWith("* ") ->
                    blocks += Block(type = BlockType.BULLET, text = line.substring(2))
                line.matches(Regex("""^\d+\.\s.*""")) ->
                    blocks += Block(type = BlockType.NUMBERED, text = line.substringAfter(". "))
                line.isBlank() -> Unit
                else -> blocks += Block(type = BlockType.TEXT, text = line)
            }
        }
        return blocks.ifEmpty { listOf(Block()) }
    }

    // ── Editing ───────────────────────────────────────────────────────────

    fun setTitle(title: String) {
        _doc.value = _doc.value.copy(title = title, updatedAt = System.currentTimeMillis())
        scheduleAutosave()
    }

    fun updateBlock(id: String, text: String) {
        _doc.value = _doc.value.copy(
            blocks = _doc.value.blocks.map { if (it.id == id) it.copy(text = text) else it },
            updatedAt = System.currentTimeMillis()
        )
        scheduleAutosave()
    }

    fun changeType(id: String, type: BlockType) {
        _doc.value = _doc.value.copy(
            blocks = _doc.value.blocks.map { if (it.id == id) it.copy(type = type) else it },
            updatedAt = System.currentTimeMillis()
        )
        scheduleAutosave()
    }

    fun toggleTodo(id: String) {
        _doc.value = _doc.value.copy(
            blocks = _doc.value.blocks.map {
                if (it.id == id) it.copy(checked = !it.checked) else it
            },
            updatedAt = System.currentTimeMillis()
        )
        scheduleAutosave()
    }

    /** Insert a block right after [afterId] (or at the end when null). */
    fun addBlock(afterId: String? = null, type: BlockType = BlockType.TEXT): Block {
        val block = Block(type = type)
        val blocks = _doc.value.blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == afterId }
        if (index >= 0) blocks.add(index + 1, block) else blocks.add(block)
        _doc.value = _doc.value.copy(blocks = blocks, updatedAt = System.currentTimeMillis())
        scheduleAutosave()
        return block
    }

    /** Insert a copy of [id] immediately after it. */
    fun duplicateBlock(id: String) {
        val blocks = _doc.value.blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return
        blocks.add(index + 1, blocks[index].copy(id = UUID.randomUUID().toString()))
        _doc.value = _doc.value.copy(blocks = blocks, updatedAt = System.currentTimeMillis())
        scheduleAutosave()
    }

    fun deleteBlock(id: String) {
        val blocks = _doc.value.blocks.filterNot { it.id == id }
        _doc.value = _doc.value.copy(
            blocks = blocks.ifEmpty { listOf(Block()) },
            updatedAt = System.currentTimeMillis()
        )
        scheduleAutosave()
    }

    fun moveBlock(id: String, up: Boolean) {
        val blocks = _doc.value.blocks.toMutableList()
        val i = blocks.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= blocks.size) return
        val tmp = blocks[i]
        blocks[i] = blocks[j]
        blocks[j] = tmp
        _doc.value = _doc.value.copy(blocks = blocks, updatedAt = System.currentTimeMillis())
        scheduleAutosave()
    }

    fun newPage() {
        _doc.value = WorkspaceDoc()
        save()
    }

    // ── Persistence ───────────────────────────────────────────────────────

    fun save() {
        autosaveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    override fun onCleared() {
        // Last-chance synchronous write so nothing typed is lost on process death.
        autosaveJob?.cancel()
        runCatching {
            storeFile.writeText(_doc.value.toJson().toString())
        }
        super.onCleared()
    }

    // ── AI ────────────────────────────────────────────────────────────────

    /** Summarize the whole document (or [text]) with the local model. */
    fun summarizeWithAi(aiVm: AiViewModel, text: String) {
        if (text.isBlank()) return
        _aiBusy.value = true
        aiVm.summarize(text)
    }

    fun clearAiBusy() {
        _aiBusy.value = false
    }

    companion object {
        fun Factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WorkspaceEditorViewModel(appContext) as T
            }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceEditorScreen(
    editorVm: WorkspaceEditorViewModel,
    aiVm: AiViewModel,
    onStudy: (title: String, text: String) -> Unit = { _, _ -> }
) {
    val doc by editorVm.doc.collectAsState()
    val saving by editorVm.saving.collectAsState()
    val lastSavedAt by editorVm.lastSavedAt.collectAsState()
    val aiResult by aiVm.result.collectAsState()
    val aiGenerating by aiVm.isGenerating.collectAsState()

    var showAiSheet by remember { mutableStateOf(false) }
    var typeMenuFor by remember { mutableStateOf<String?>(null) }

    // Flush to disk when the app leaves the foreground — same guarantee a
    // stock notes app gives you.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) editorVm.saveNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            editorVm.saveNow()
        }
    }

    LaunchedEffect(aiResult) {
        if (aiResult != null) {
            editorVm.clearAiBusy()
            showAiSheet = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    BasicTextField(
                        value = doc.title,
                        onValueChange = editorVm::setTitle,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        decorationBox = { inner ->
                            if (doc.title.isBlank()) {
                                Text(
                                    "Untitled",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Saving…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                Icons.Default.CloudDone,
                                contentDescription = "Saved",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                lastSavedAt?.let { "Saved ${formatRelativeTime(it)}" } ?: "Not saved yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { editorVm.save() }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { editorVm.newPage() }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New page")
                    }
                    IconButton(
                        onClick = {
                            val text = doc.blocks.joinToString("\n") { it.text }
                                .ifBlank { doc.title }
                            editorVm.summarizeWithAi(aiVm, text)
                        }
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Summarize with AI")
                    }
                    IconButton(
                        onClick = {
                            val text = doc.blocks.joinToString("\n") { it.text }.trim()
                            onStudy(doc.title.ifBlank { "Workspace" }, text)
                        }
                    ) {
                        Icon(Icons.Default.School, contentDescription = "Study this document")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                if (doc.sourceFile != null) {
                    Text(
                        "Editing ${File(doc.sourceFile!!).name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            itemsIndexed(doc.blocks, key = { _, b -> b.id }) { index, block ->
                BlockRow(
                    block = block,
                    listNumber = numberedPosition(doc.blocks, index),
                    onTextChange = { editorVm.updateBlock(block.id, it) },
                    onEnter = {
                        editorVm.addBlock(
                            afterId = block.id,
                            type = if (block.type == BlockType.BULLET ||
                                block.type == BlockType.NUMBERED ||
                                block.type == BlockType.TODO
                            ) block.type else BlockType.TEXT
                        )
                    },
                    onDuplicate = { editorVm.duplicateBlock(block.id) },
                    onDelete = { editorVm.deleteBlock(block.id) },
                    onToggle = { editorVm.toggleTodo(block.id) },
                    onTypeMenu = { typeMenuFor = block.id }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .clickable { editorVm.addBlock() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Add a block",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Block-type picker
    val menuTarget = typeMenuFor
    if (menuTarget != null) {
        AlertDialog(
            onDismissRequest = { typeMenuFor = null },
            title = { Text("Turn into") },
            text = {
                Column {
                    BlockType.values().forEach { type ->
                        Text(
                            type.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editorVm.changeType(menuTarget, type)
                                    typeMenuFor = null
                                }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { typeMenuFor = null }) { Text("Cancel") }
            }
        )
    }

    // AI result
    if (showAiSheet && aiResult != null) {
        AlertDialog(
            onDismissRequest = { showAiSheet = false },
            title = { Text("AI summary") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    if (aiGenerating) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                    }
                    BasicTextField(
                        value = aiResult ?: "",
                        onValueChange = {},
                        readOnly = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = aiResult
                    if (!text.isNullOrBlank()) {
                        val block = editorVm.addBlock(type = BlockType.QUOTE)
                        editorVm.updateBlock(block.id, "AI: $text")
                    }
                    showAiSheet = false
                }) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { showAiSheet = false }) { Text("Close") }
            }
        )
    }
}

// ── Block row ─────────────────────────────────────────────────────────────

/** 1-based position of [index] within the consecutive run of numbered blocks it belongs to. */
private fun numberedPosition(blocks: List<Block>, index: Int): Int {
    if (index !in blocks.indices || blocks[index].type != BlockType.NUMBERED) return 0
    var n = 1
    var i = index - 1
    while (i >= 0 && blocks[i].type == BlockType.NUMBERED) {
        n++
        i--
    }
    return n
}

@Composable
private fun BlockRow(
    block: Block,
    listNumber: Int,
    onTextChange: (String) -> Unit,
    onEnter: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onTypeMenu: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var text by remember(block.id) { mutableStateOf(block.text) }

    // Keep local state in sync when the model changes from outside (e.g. AI insert)
    LaunchedEffect(block.text) {
        if (block.text != text) text = block.text
    }

    val textStyle: TextStyle = when (block.type) {
        BlockType.H1     -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        BlockType.H2     -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
        BlockType.H3     -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        BlockType.CODE   -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        BlockType.QUOTE  -> MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else             -> MaterialTheme.typography.bodyLarge
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Leading marker / handle
        Box(
            modifier = Modifier
                .width(28.dp)
                .padding(top = 2.dp)
        ) {
            when (block.type) {
                BlockType.BULLET -> Text(
                    "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BlockType.NUMBERED -> Text(
                    "${if (listNumber > 0) listNumber else 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                BlockType.TODO -> Checkbox(
                    checked = block.checked,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.size(20.dp)
                )
                else -> IconButton(
                    onClick = onTypeMenu,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.DragIndicator,
                        contentDescription = "Block options",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        if (block.type == BlockType.DIVIDER) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        } else {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                modifier = Modifier.weight(1f),
                textStyle = textStyle.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (block.type == BlockType.TODO && block.checked)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else null
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            placeholderFor(block.type),
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    inner()
                }
            )
        }

        if (block.type == BlockType.CODE) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Block actions",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Turn into…") },
                    onClick = {
                        menuOpen = false
                        onTypeMenu()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duplicate") },
                    onClick = {
                        menuOpen = false
                        onDuplicate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete block") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

private fun placeholderFor(type: BlockType): String = when (type) {
    BlockType.H1 -> "Heading 1"
    BlockType.H2 -> "Heading 2"
    BlockType.H3 -> "Heading 3"
    BlockType.QUOTE -> "Quote"
    BlockType.CODE -> "Code"
    BlockType.TODO -> "To-do"
    BlockType.BULLET -> "List item"
    BlockType.NUMBERED -> "List item"
    else -> "Type something…"
}

fun formatRelativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 10_000      -> "just now"
        diff < 60_000      -> "${diff / 1000}s ago"
        diff < 3_600_000   -> "${diff / 60_000}m ago"
        diff < 86_400_000  -> "${diff / 3_600_000}h ago"
        else               -> "${diff / 86_400_000}d ago"
    }
}
