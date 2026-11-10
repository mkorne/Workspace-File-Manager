package com.example.workspacefilemanager.ai

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

// ── ViewModel ─────────────────────────────────────────────────────────────

enum class ModelStatus { None, Loading, Ready, Failed }

enum class ChatRole { User, Assistant }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val meta: String? = null,
    val isError: Boolean = false,
)

class AiViewModel : ViewModel() {

    private var initialized = false

    private val _modelStatus = MutableStateFlow(ModelStatus.None)
    private val _modelPath = MutableStateFlow<String?>(null)
    private val _modelFileName = MutableStateFlow("")
    private val _loadElapsed = MutableStateFlow(0)
    private val _loadPercent = MutableStateFlow(0)
    private val _error = MutableStateFlow<String?>(null)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isGenerating = MutableStateFlow(false)
    private val _genElapsedMs = MutableStateFlow(0L)
    private val _genTokens = MutableStateFlow(0)
    private val _result = MutableStateFlow<String?>(null)
    private val _lastWasStopped = MutableStateFlow(false)

    private val _downloads = MutableStateFlow<Map<String, DownloadEvent>>(emptyMap())
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())

    private val _memoryText = MutableStateFlow("")
    private val _systemPrompt = MutableStateFlow("")
    private val _persona = MutableStateFlow("helpful assistant")

    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()
    val modelPath: StateFlow<String?> = _modelPath.asStateFlow()
    val modelFileName: StateFlow<String> = _modelFileName.asStateFlow()
    val loadElapsed: StateFlow<Int> = _loadElapsed.asStateFlow()
    val loadPercent: StateFlow<Int> = _loadPercent.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    val genElapsedMs: StateFlow<Long> = _genElapsedMs.asStateFlow()
    val genTokens: StateFlow<Int> = _genTokens.asStateFlow()
    val lastWasStopped: StateFlow<Boolean> = _lastWasStopped.asStateFlow()

    /** Kept for the Workspace "Summarise" action. */
    val result: StateFlow<String?> = _result.asStateFlow()

    val downloads: StateFlow<Map<String, DownloadEvent>> = _downloads.asStateFlow()
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    val memoryText: StateFlow<String> = _memoryText.asStateFlow()
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()
    val persona: StateFlow<String> = _persona.asStateFlow()

    internal lateinit var ctx: Context
    private lateinit var downloader: AIModelDownloader

    private var generateJob: Job? = null
    private var tickerJob: Job? = null
    private val downloadJobs = mutableMapOf<String, Job>()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        ctx = context.applicationContext
        downloader = AIModelDownloader(ctx)

        val prefs = ctx.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
        _modelPath.value = prefs.getString("last_model_path", null)
        _modelFileName.value = _modelPath.value?.substringAfterLast('/') ?: ""
        _memoryText.value = prefs.getString("ai_memory", "") ?: ""
        _systemPrompt.value = prefs.getString("ai_system_prompt", "") ?: ""
        _persona.value = prefs.getString("ai_persona", "helpful assistant") ?: "helpful assistant"
        // A saved model is NOT loaded here: loading 0.3–2 GB at app start makes
        // the whole app feel broken. It loads when the AI screen is opened.
    }

    /** Called when the AI screen becomes visible. */
    fun ensureModelLoaded() {
        if (_modelStatus.value == ModelStatus.Ready || _modelStatus.value == ModelStatus.Loading) return
        val path = _modelPath.value ?: return
        if (File(path).exists()) loadModelAtPath(path)
    }

    fun setModelPath(path: String) {
        _modelPath.value = path
        _modelFileName.value = path.substringAfterLast('/')
        savePref("last_model_path", path)
    }

    fun loadModel() {
        val path = _modelPath.value
        if (path == null) {
            _error.value = "No model selected. Download one below or browse for a .gguf file."
            return
        }
        loadModelAtPath(path)
    }

    fun loadModelAtPath(path: String) {
        setModelPath(path)
        _modelStatus.value = ModelStatus.Loading
        _loadElapsed.value = 0
        _loadPercent.value = 0
        _error.value = null
        startLoadTicker()

        viewModelScope.launch {
            val ok = OfflineAIEngine.load(path) { percent ->
                _loadPercent.value = percent
            }
            tickerJob?.cancel()
            if (ok) {
                _loadPercent.value = 100
                _modelStatus.value = ModelStatus.Ready
            } else {
                _modelStatus.value = ModelStatus.Failed
                _error.value =
                    "Could not load ${path.substringAfterLast('/')}. " +
                        "The file may be incomplete, or the model is not a valid GGUF."
            }
        }
    }

    private fun startLoadTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _loadElapsed.value = _loadElapsed.value + 1
            }
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _isGenerating.value) return
        if (_modelStatus.value != ModelStatus.Ready) {
            _error.value = "Load a model first."
            return
        }

        _error.value = null
        _result.value = null
        _lastWasStopped.value = false
        _genTokens.value = 0
        _genElapsedMs.value = 0

        // Bounded conversation memory: the native side takes one system + one
        // user string, so earlier turns are folded into the user prompt. Kept
        // short so it fits small models' context windows.
        val history = _messages.value.takeLast(6).joinToString("\n") { msg ->
            val who = if (msg.role == ChatRole.User) "User" else "Assistant"
            "$who: ${msg.text.take(300)}"
        }
        val prompt = if (history.isBlank()) {
            text
        } else {
            "Earlier in this conversation:\n$history\n\nUser: $text"
        }

        _messages.value = _messages.value + ChatMessage(ChatRole.User, text)
        _messages.value = _messages.value + ChatMessage(ChatRole.Assistant, "")

        _isGenerating.value = true
        val startedAt = System.currentTimeMillis()

        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                _genElapsedMs.value = System.currentTimeMillis() - startedAt
            }
        }

        val system = buildSystemPrompt()

        generateJob = viewModelScope.launch {
            val answer = OfflineAIEngine.generate(system, prompt, OfflineAIEngine.DEFAULT_MAX_TOKENS) { piece ->
                appendToLastAssistant(piece)
                _genTokens.value = _genTokens.value + 1
            }
            tickerJob?.cancel()
            _isGenerating.value = false

            val elapsed = System.currentTimeMillis() - startedAt
            _genElapsedMs.value = elapsed
            val stopped = _lastWasStopped.value

            val body = answer.trim()
            val cleaned = body.removeSuffix("<|end|>").trim()

            finalizeLastAssistant(
                finalText = if (stopped && cleaned.isEmpty()) "(stopped before any words)" else cleaned,
                meta = buildString {
                    append(if (stopped) "Stopped" else "Done")
                    append(" · ")
                    append("%.1fs".format(elapsed / 1000.0))
                    if (_genTokens.value > 0) {
                        append(" · ")
                        append("${_genTokens.value} tok")
                        if (elapsed > 0) {
                            val tps = _genTokens.value / (elapsed / 1000.0)
                            append(" · ")
                            append("%.1f tok/s".format(tps))
                        }
                    }
                },
                isError = cleaned.startsWith("[AI Error]"),
            )

            if (cleaned.isNotEmpty()) _result.value = cleaned
        }
    }

    fun stopGeneration() {
        if (!_isGenerating.value) return
        _lastWasStopped.value = true
        OfflineAIEngine.cancel()
    }

    private fun appendToLastAssistant(piece: String) {
        val list = _messages.value
        val last = list.lastOrNull() ?: return
        if (last.role != ChatRole.Assistant) return
        _messages.value = list.dropLast(1) + last.copy(text = last.text + piece)
    }

    private fun finalizeLastAssistant(finalText: String, meta: String, isError: Boolean) {
        val list = _messages.value
        val last = list.lastOrNull() ?: return
        if (last.role != ChatRole.Assistant) return
        _messages.value = list.dropLast(1) + last.copy(text = finalText, meta = meta, isError = isError)
    }

    fun clearConversation() {
        if (_isGenerating.value) stopGeneration()
        _messages.value = emptyList()
        _result.value = null
    }

    /** Used by the Workspace editor. */
    fun summarize(text: String) {
        if (_modelStatus.value != ModelStatus.Ready) {
            _error.value = "Load a model first."
            return
        }
        viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _result.value = OfflineAIEngine.generate(
                system = "You are a precise summariser. Reply with a short, clear summary only.",
                user = "Summarise the following in 3 to 5 sentences:\n\n" + text.take(6000),
            )
            _isGenerating.value = false
        }
    }

    private fun buildSystemPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are ").append(_persona.value.ifBlank { "a helpful assistant" })
        sb.append(". You run locally on an Android phone, so answer concisely.")
        if (_memoryText.value.isNotBlank()) {
            sb.append("\n\nFacts to remember about the user:\n").append(_memoryText.value)
        }
        if (_systemPrompt.value.isNotBlank()) {
            sb.append("\n\nExtra instructions:\n").append(_systemPrompt.value)
        }
        return sb.toString()
    }

    // ── Memory & customization ────────────────────────────────────────────

    fun setMemory(text: String) {
        _memoryText.value = text
        savePref("ai_memory", text)
    }

    fun setSystemPrompt(prompt: String) {
        _systemPrompt.value = prompt
        savePref("ai_system_prompt", prompt)
    }

    fun setPersona(name: String) {
        _persona.value = name
        savePref("ai_persona", name)
    }

    fun clearError() {
        _error.value = null
    }

    // ── Downloads ─────────────────────────────────────────────────────────

    fun startDownload(model: AIModels.Model) {
        if (model.id in _downloading.value) return
        _downloading.value = _downloading.value + model.id
        _downloads.value = _downloads.value + (model.id to DownloadEvent.Started(model.name, model.downloadUrl, model.sizeLabel))
        _error.value = null

        val job = viewModelScope.launch {
            downloader.download(model).collect { event ->
                _downloads.value = _downloads.value + (model.id to event)
                when (event) {
                    is DownloadEvent.Complete -> {
                        _downloading.value = _downloading.value - model.id
                        loadModelAtPath(event.path)
                    }
                    is DownloadEvent.Error -> {
                        _downloading.value = _downloading.value - model.id
                        _error.value = event.message
                    }
                    is DownloadEvent.AlreadyExists -> {
                        _downloading.value = _downloading.value - model.id
                        loadModelAtPath(event.path)
                    }
                    else -> Unit
                }
            }
        }
        downloadJobs[model.id] = job
    }

    fun cancelDownload(model: AIModels.Model) {
        downloadJobs.remove(model.id)?.cancel()
        downloader.cancel(model.id)
        _downloading.value = _downloading.value - model.id
        _downloads.value = _downloads.value - model.id
    }

    fun isDownloading(model: AIModels.Model): Boolean = model.id in _downloading.value
    fun isDownloaded(model: AIModels.Model): Boolean = downloader.isDownloaded(model)
    fun downloadEvent(model: AIModels.Model): DownloadEvent? = _downloads.value[model.id]
    fun localPath(model: AIModels.Model): String? = downloader.localPathIfExists(model)

    fun loadModel(model: AIModels.Model) {
        val path = localPath(model)
        if (path == null) {
            _error.value = "Model not downloaded yet."
            return
        }
        loadModelAtPath(path)
    }

    override fun onCleared() {
        super.onCleared()
        downloadJobs.values.forEach { it.cancel() }
        tickerJob?.cancel()
        generateJob?.cancel()
        viewModelScope.launch { OfflineAIEngine.release() }
    }

    private fun savePref(key: String, value: String) {
        try {
            ctx.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply()
        } catch (_: Exception) {
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPanel(
    viewModel: AiViewModel,
    prefillText: String = "",
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    val status by viewModel.modelStatus.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelPath by viewModel.modelPath.collectAsState()
    val modelFileName by viewModel.modelFileName.collectAsState()
    val loadElapsed by viewModel.loadElapsed.collectAsState()
    val loadPercent by viewModel.loadPercent.collectAsState()
    val genElapsed by viewModel.genElapsedMs.collectAsState()
    val genTokens by viewModel.genTokens.collectAsState()
    val wasStopped by viewModel.lastWasStopped.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val downloading by viewModel.downloading.collectAsState()
    val memoryText by viewModel.memoryText.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val persona by viewModel.persona.collectAsState()

    var input by remember { mutableStateOf("") }
    var showCatalog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Load lazily, only while this screen is on screen.
    LaunchedEffect(Unit) { viewModel.ensureModelLoaded() }

    // Keep the newest text in view while streaming.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex.coerceAtLeast(0))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        // ── Header: two rows so nothing is squeezed on a ~400dp screen ────
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Mini AI",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            StatusBadge(status)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showCatalog = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Models", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            OutlinedButton(
                onClick = { showSettings = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Setup", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
            OutlinedButton(
                onClick = { showFilePicker = true },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Browse", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }

        // ── Model state ──────────────────────────────────────────────────
        when (status) {
            ModelStatus.Loading -> LoadCard(modelFileName, loadElapsed, loadPercent)
            ModelStatus.Failed, ModelStatus.None -> ModelSummaryCard(
                status = status,
                fileName = modelFileName,
                onChoose = { showCatalog = true },
                onRetry = { viewModel.loadModel() },
                hasPath = modelPath != null,
            )
            ModelStatus.Ready -> ReadyStrip(fileName = modelFileName, onClear = { viewModel.clearConversation() })
        }

        // ── Active download ──────────────────────────────────────────────
        val activeId = downloading.firstOrNull()
        if (activeId != null) {
            val model = AIModels.available.firstOrNull { it.id == activeId }
            val event = downloads[activeId]
            if (model != null) {
                Spacer(Modifier.height(8.dp))
                DownloadCard(
                    model = model,
                    event = event,
                    onCancel = { viewModel.cancelDownload(model) }
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorCard(error!!, onDismiss = { viewModel.clearError() })
        }

        // ── Conversation ─────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (status == ModelStatus.Ready)
                                "Ask anything. Everything stays on this phone."
                            else
                                "Pick a model above to start. The small ones answer fastest.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(messages) { message ->
                MessageBubble(message)
            }

            if (isGenerating) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildString {
                                append("%.1fs".format(genElapsed / 1000.0))
                                if (genTokens > 0) {
                                    append(" · ")
                                    append(genTokens)
                                    append(" tok")
                                }
                                if (wasStopped) append(" · stopping…")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── Input ────────────────────────────────────────────────────────
        if (isGenerating) {
            FilledTonalButton(
                onClick = { viewModel.stopGeneration() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (wasStopped) "Stopping…" else "Stop generating")
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (status == ModelStatus.Ready) "Ask the AI…" else "Load a model first",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = status == ModelStatus.Ready && !isGenerating,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val text = input
                    if (text.isNotBlank()) {
                        viewModel.ask(text)
                        input = ""
                    }
                },
                enabled = status == ModelStatus.Ready && !isGenerating && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }

        if (prefillText.isNotBlank() && status == ModelStatus.Ready) {
            TextButton(onClick = { viewModel.summarize(prefillText) }, enabled = !isGenerating) {
                Icon(Icons.Default.Summarize, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Summarise the open document")
            }
        }
    }

    if (showCatalog) {
        ModelCatalog(
            viewModel = viewModel,
            currentPath = modelPath,
            onDismiss = { showCatalog = false }
        )
    }

    if (showSettings) {
        AiSettingsSheet(
            viewModel = viewModel,
            persona = persona,
            memoryText = memoryText,
            systemPrompt = systemPrompt,
            onDismiss = { showSettings = false }
        )
    }

    if (showFilePicker) {
        FilePickerSheet(
            onPick = { uri ->
                val path = resolvePickedUri(context, uri)
                if (path != null && File(path).exists()) {
                    viewModel.loadModelAtPath(path)
                } else {
                    viewModel.loadModel()
                }
                showFilePicker = false
            },
            onDismiss = { showFilePicker = false }
        )
    }
}

// ── Small UI pieces ───────────────────────────────────────────────────────

@Composable
private fun StatusBadge(status: ModelStatus) {
    val (text, container) = when (status) {
        ModelStatus.Ready -> "Ready" to MaterialTheme.colorScheme.primaryContainer
        ModelStatus.Loading -> "Loading" to MaterialTheme.colorScheme.secondaryContainer
        ModelStatus.Failed -> "Failed" to MaterialTheme.colorScheme.errorContainer
        ModelStatus.None -> "No model" to MaterialTheme.colorScheme.surfaceVariant
    }
    Badge(containerColor = container) {
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LoadCard(fileName: String, elapsedSec: Int, percent: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Loading model",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { percent.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$fileName · ${elapsedSec}s. Reading the file into memory — the first load is the slow one.",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModelSummaryCard(
    status: ModelStatus,
    fileName: String,
    onChoose: () -> Unit,
    onRetry: () -> Unit,
    hasPath: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (status == ModelStatus.Failed) "That model did not load" else "No model loaded",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (hasPath)
                    "$fileName — retry, or choose a smaller model."
                else
                    "Download a small model (270 MB is plenty to start) or browse for a .gguf file on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPath && status == ModelStatus.Failed) {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
                Button(onClick = onChoose) { Text("Choose model") }
            }
        }
    }
}

@Composable
private fun ReadyStrip(fileName: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            fileName.ifBlank { "Model loaded" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text("Clear", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DownloadCard(model: AIModels.Model, event: DownloadEvent?, onCancel: () -> Unit) {
    val progress = (event as? DownloadEvent.Progress)?.percent ?: 0
    val label = when (event) {
        is DownloadEvent.Progress -> "${event.readableProgress} / ${event.readableTotal}"
        is DownloadEvent.Started -> "Starting…"
        else -> "Downloading…"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                model.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (progress.coerceAtLeast(0)) / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = when {
                message.isError -> MaterialTheme.colorScheme.errorContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            },
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (message.text.isEmpty()) {
                    Text(
                        "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (message.meta != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message.meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

// ── Settings sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsSheet(
    viewModel: AiViewModel,
    persona: String,
    memoryText: String,
    systemPrompt: String,
    onDismiss: () -> Unit,
) {
    var personaInput by remember { mutableStateOf(persona) }
    var memoryInput by remember { mutableStateOf(memoryText) }
    var promptInput by remember { mutableStateOf(systemPrompt) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("AI setup", style = MaterialTheme.typography.titleMedium)

            Text("Persona", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = personaInput,
                onValueChange = { personaInput = it },
                placeholder = { Text("helpful assistant") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Remembered facts", style = MaterialTheme.typography.labelLarge)
            Text(
                "Anything the AI should always know about you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = memoryInput,
                onValueChange = { memoryInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 6
            )

            Text("Extra instructions", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    viewModel.setPersona(personaInput)
                    viewModel.setMemory(memoryInput)
                    viewModel.setSystemPrompt(promptInput)
                    onDismiss()
                }) { Text("Save") }
            }
        }
    }
}

// ── File picker ───────────────────────────────────────────────────────────

/** Turn a SAF/file Uri into a local path, copying into the app model folder if needed. */
private fun resolvePickedUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path

    val name = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "model.gguf"

    val destDir = AIModels.modelsDir(context)
    val dest = File(destDir, name)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        dest.absolutePath
    } catch (t: Exception) {
        android.widget.Toast.makeText(
            context, "Could not read that file: ${t.message}", android.widget.Toast.LENGTH_LONG
        ).show()
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerSheet(
    onPick: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showFileBrowser by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Choose a model file", style = MaterialTheme.typography.titleMedium)
            Text(
                "Only .gguf files work. Models downloaded here land in the app's own folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = { showFileBrowser = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Browse this phone")
            }

            Text(
                "Folders with models",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val locations = remember {
                listOf(
                    AIModels.getOrCreateModelsDir(context),
                    File(android.os.Environment.getExternalStorageDirectory(), "Download").absolutePath,
                    File(android.os.Environment.getExternalStorageDirectory(), "models").absolutePath,
                ).distinct()
            }

            locations.forEach { dirPath ->
                val dir = File(dirPath)
                val models = remember(dirPath) {
                    if (dir.isDirectory)
                        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
                            ?.sortedBy { it.name.lowercase() } ?: emptyList()
                    else emptyList()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = models.isNotEmpty()) {
                            models.firstOrNull()?.let { onPick(Uri.fromFile(it)) }
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        if (dirPath.endsWith("files/models")) "App models folder"
                        else dir.name.ifEmpty { "Internal storage" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (models.isEmpty()) "No .gguf files here"
                        else "${models.size} model(s): ${models.joinToString { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()
            }

            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }

    if (showFileBrowser) {
        GgufFileBrowser(
            onSelectFile = { uri ->
                onPick(uri)
                showFileBrowser = false
            },
            onDismiss = { showFileBrowser = false }
        )
    }
}

private data class FileEntry(
    val file: File,
    val name: String = file.name,
    val isDir: Boolean = file.isDirectory,
    val size: Long = if (file.isFile) file.length() else 0L,
    val ext: String = name.substringAfterLast('.', "").lowercase(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GgufFileBrowser(
    onSelectFile: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember {
        mutableStateOf(android.os.Environment.getExternalStorageDirectory().absolutePath)
    }
    var items by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath) {
        loading = true
        val dir = File(currentPath)
        items = if (dir.isDirectory) {
            (dir.listFiles()?.map { FileEntry(it) } ?: emptyList())
                .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        } else emptyList()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentPath.substringAfterLast('/').ifEmpty { "Internal storage" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = File(currentPath).parent
                        if (parent != null) currentPath = parent else onDismiss()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (items.isEmpty()) {
                        item {
                            Text(
                                "Nothing here",
                                modifier = Modifier.padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(items, key = { it.file.absolutePath }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        entry.isDir -> currentPath = entry.file.absolutePath
                                        entry.ext == "gguf" -> onSelectFile(Uri.fromFile(entry.file))
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when {
                                    entry.isDir -> Icons.Default.Folder
                                    entry.ext == "gguf" -> Icons.Default.AutoAwesome
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = null,
                                tint = if (entry.ext == "gguf")
                                    MaterialTheme.colorScheme.tertiary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (entry.isDir) "Folder"
                                    else AIModels.formatBytes(entry.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (entry.ext == "gguf") {
                                Badge(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                                    Text("GGUF", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ── Model catalog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelCatalog(
    viewModel: AiViewModel,
    currentPath: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Models",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "Downloaded from Hugging Face, stored on this phone. Smaller models answer noticeably faster.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(AIModels.available, key = { it.id }) { model ->
                    val downloaded = viewModel.isDownloaded(model)
                    val isDownloading = viewModel.isDownloading(model)
                    val isActive = currentPath?.endsWith(model.filename) == true
                    ModelRow(
                        model = model,
                        downloaded = downloaded,
                        isDownloading = isDownloading,
                        isActive = isActive,
                        event = viewModel.downloadEvent(model),
                        onLoad = { viewModel.loadModel(model) },
                        onDownload = { viewModel.startDownload(model) },
                        onCancel = { viewModel.cancelDownload(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: AIModels.Model,
    downloaded: Boolean,
    isDownloading: Boolean,
    isActive: Boolean,
    event: DownloadEvent?,
    onLoad: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val progress = (event as? DownloadEvent.Progress)?.percent ?: 0
    val progressLabel = when (event) {
        is DownloadEvent.Progress -> "${event.readableProgress} of ${event.readableTotal}"
        is DownloadEvent.Started -> "Starting…"
        else -> "Downloading…"
    }

    Surface(
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isActive) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                        Text("Loaded", style = MaterialTheme.typography.labelSmall)
                    }
                } else if (downloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${model.sizeLabel} · ${model.tags.joinToString(" · ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            when {
                isDownloading -> {
                    LinearProgressIndicator(
                        progress = { progress.coerceAtLeast(0) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            progressLabel,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                }

                downloaded -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledTonalButton(onClick = onLoad) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Load")
                        }
                    }
                }

                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}
