package com.example.workspacefilemanager.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.method.LinkMovementMethod
import android.util.LruCache
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.workspacefilemanager.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

// ── PDF Viewer ──────────────────────────────────────────────────────────────

/**
 * Renders PDF pages on demand.
 *
 * The previous version rasterised *every* page into an ARGB_8888 bitmap before
 * showing anything — a 40-page A4 document costs ~110 MB and stalls for seconds.
 * Here pages are rendered one at a time, at screen width, through a single
 * PdfRenderer guarded by a mutex, with a small LRU so scrolling back is instant.
 */
class PdfViewerViewModel(private val context: Context) : ViewModel() {

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private val renderMutex = Mutex()

    /**
     * Keyed by "page@width" so a rotation or zoom re-renders at the new width.
     *
     * Sized by bytes, not by entry count: at 4x zoom a single page bitmap is
     * ~10 MB, so a fixed 8-entry cache could hold ~80 MB and risk an OOM.
     */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val _totalPages = MutableStateFlow(0)
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _fileName = MutableStateFlow("")
    private val _fileSize = MutableStateFlow(0L)

    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()
    val fileName: StateFlow<String> = _fileName.asStateFlow()
    val fileSize: StateFlow<Long> = _fileSize.asStateFlow()

    fun open(path: String) {
        if (_fileName.value == File(path).name && _totalPages.value > 0) return
        closeRenderer()
        val file = File(path)
        if (!file.exists()) {
            _error.value = "File not found"
            return
        }
        _fileName.value = file.name
        _fileSize.value = file.length()
        _loading.value = true
        _error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                descriptor = pfd
                val r = PdfRenderer(pfd)
                renderer = r
                _totalPages.value = r.pageCount
            } catch (t: Throwable) {
                _error.value = "Cannot open PDF: ${t.message ?: "corrupt or password protected"}"
            }
            _loading.value = false
        }
    }

    /** Render [index] at [widthPx]; null when the page cannot be drawn. */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        if (widthPx <= 0) return null
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return renderMutex.withLock {
            cache.get(key)?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val r = renderer ?: return@withContext null
                try {
                    r.openPage(index).use { page ->
                        val scale = widthPx.toFloat() / page.width
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(
                            widthPx.coerceAtLeast(1),
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        val matrix = android.graphics.Matrix().apply { setScale(scale, scale) }
                        page.render(
                            bmp,
                            null,
                            matrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        cache.put(key, bmp)
                        bmp
                    }
                } catch (t: Throwable) {
                    // PAGE_COUNT / opened-elsewhere are recoverable per page
                    null
                }
            }
        }
    }

    private fun closeRenderer() {
        try {
            renderer?.close()
        } catch (_: Throwable) {
        }
        renderer = null
        try {
            descriptor?.close()
        } catch (_: Throwable) {
        }
        descriptor = null
    }

    override fun onCleared() {
        closeRenderer()
        cache.evictAll()
        super.onCleared()
    }

    companion object {
        fun Factory(appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PdfViewerViewModel(appContext) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    filePath: String,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val vm: PdfViewerViewModel = viewModel(
        factory = PdfViewerViewModel.Factory(context.applicationContext)
    )

    LaunchedEffect(filePath) { vm.open(filePath) }

    val totalPages by vm.totalPages.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val fileName by vm.fileName.collectAsState()

    val listState = rememberLazyListState()
    val currentPage = (listState.firstVisibleItemIndex + 1).coerceAtMost(totalPages.coerceAtLeast(1))

    // Zoom: 1x fits the page to the screen, up to 4x for reading fine print.
    // Pages are re-rendered at the zoomed width, so detail is real, not a
    // stretched bitmap.
    var zoom by remember(filePath) { mutableFloatStateOf(1f) }
    val horizontalScroll = rememberScrollState()

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val baseWidthPx = with(density) {
        (configuration.screenWidthDp.dp.toPx() - 16.dp.toPx()).toInt().coerceAtLeast(320)
    }
    val pageWidthPx = (baseWidthPx * zoom).toInt().coerceAtLeast(320)
    val pageWidthDp = with(density) { pageWidthPx.toDp() }

    // Remember where each page was reached so zooming does not jump around.
    LaunchedEffect(zoom) {
        if (zoom <= 1f) horizontalScroll.scrollTo(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        if (totalPages > 0) {
                            Text(
                                "Page $currentPage of $totalPages",
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
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, File(filePath)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { FileActions.openExternally(context, File(filePath)) }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = "Open with")
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
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { FileActions.openExternally(context, File(filePath)) }) {
                        Text("Open in another app")
                    }
                }
                totalPages == 0 -> Text(
                    "This PDF has no pages",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .pinchToZoom(
                            zoom = zoom,
                            onZoom = { z ->
                                zoom = z.coerceIn(1f, 4f)
                            }
                        )
                ) {
                    // One horizontal scroller for the whole document so every
                    // page pans together; the LazyColumn keeps vertical flow.
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .horizontalScroll(horizontalScroll)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .width(pageWidthDp)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(totalPages) { index ->
                                PdfPage(
                                    vm = vm,
                                    index = index,
                                    widthPx = pageWidthPx
                                )
                            }
                        }
                    }

                    // Zoom controls: reliable, thumb-reachable, and they tell
                    // you the current level.
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 3.dp
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) },
                                enabled = zoom > 1f
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Zoom out")
                            }
                            Text(
                                text = "${(zoom * 100).toInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.widthIn(min = 44.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            IconButton(
                                onClick = { zoom = (zoom + 0.5f).coerceAtMost(4f) },
                                enabled = zoom < 4f
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Zoom in")
                            }
                            if (zoom > 1f) {
                                TextButton(onClick = {
                                    zoom = 1f
                                }) {
                                    Text("Fit")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(vm: PdfViewerViewModel, index: Int, widthPx: Int) {
    val bitmap by produceState<Bitmap?>(initialValue = null, index, widthPx) {
        value = vm.renderPage(index, widthPx)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * Two-finger pinch that only consumes the event while a zoom is happening, so
 * vertical scrolling of the document keeps working normally.
 */
@Composable
private fun Modifier.pinchToZoom(zoom: Float, onZoom: (Float) -> Unit): Modifier {
    val current by rememberUpdatedState(zoom)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val zoomChange = event.calculateZoom()
                if (zoomChange != 1f) {
                    onZoom(current * zoomChange)
                    event.changes.forEach { it.consume() }
                }
                if (event.changes.all { !it.pressed }) break
            }
        }
    }
}

// ── DOCX Viewer ─────────────────────────────────────────────────────────────

class DocxViewerViewModel(private val context: Context) : ViewModel() {

    private val _text = MutableStateFlow("")
    private val _title = MutableStateFlow("")
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val text: StateFlow<String> = _text.asStateFlow()
    val title: StateFlow<String> = _title.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadDocx(path: String) {
        val file = File(path)
        if (!file.exists()) {
            _error.value = "File not found"
            return
        }
        _title.value = file.name
        _loading.value = true
        _error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _text.value = extractDocxText(file)
            } catch (e: Exception) {
                _error.value = "Failed to load DOCX: ${e.message}"
            }
            _loading.value = false
        }
    }

    private fun extractDocxText(file: File): String {
        val sb = StringBuilder()
        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = String(zis.readBytes())
                    // Walk paragraph by paragraph so line breaks survive.
                    xml.split("</w:p>").forEach { para ->
                        val line = StringBuilder()
                        Regex("""<w:t[^>]*>([^<]*)</w:t>""").findAll(para).forEach { m ->
                            line.append(
                                m.groupValues[1]
                                    .replace("&amp;", "&")
                                    .replace("&lt;", "<")
                                    .replace("&gt;", ">")
                                    .replace("&quot;", "\"")
                                    .replace("&apos;", "'")
                            )
                        }
                        if (line.isNotBlank()) sb.append(line).append('\n')
                    }
                }
                entry = zis.nextEntry
            }
        }
        return sb.toString().trim()
    }

    companion object {
        fun Factory(appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DocxViewerViewModel(appContext) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocxViewerScreen(filePath: String, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: DocxViewerViewModel = viewModel(
        factory = DocxViewerViewModel.Factory(context.applicationContext)
    )
    LaunchedEffect(filePath) { vm.loadDocx(filePath) }

    val text by vm.text.collectAsState()
    val title by vm.title.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, File(filePath)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
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
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
                text.isNotBlank() -> MarkdownPreviewPane(markdown = text)
                else -> Text(
                    "No text content found",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── EPUB Viewer ─────────────────────────────────────────────────────────────

class EpubViewerViewModel(private val context: Context) : ViewModel() {

    private val _text = MutableStateFlow("")
    private val _title = MutableStateFlow("")
    private val _loading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val text: StateFlow<String> = _text.asStateFlow()
    val title: StateFlow<String> = _title.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadEpub(path: String) {
        val file = File(path)
        if (!file.exists()) {
            _error.value = "File not found"
            return
        }
        _title.value = file.name
        _loading.value = true
        _error.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _text.value = extractEpubText(file)
            } catch (e: Exception) {
                _error.value = "Failed to load EPUB: ${e.message}"
            }
            _loading.value = false
        }
    }

    private fun extractEpubText(file: File): String {
        val sb = StringBuilder()
        val chapters = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(file.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (entry.name.endsWith(".xhtml") || entry.name.endsWith(".html"))) {
                    chapters.add(entry.name to zis.readBytes())
                }
                entry = zis.nextEntry
            }
        }
        chapters.sortedBy { it.first }.forEach { (_, data) ->
            val html = String(data)
            val body = html.substringAfter("<body", html)
            val cleaned = body
                .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?i)</p>|<br\\s*/?>|</div>|</h[1-6]>"), "\n\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            if (cleaned.isNotBlank()) sb.append(cleaned).append("\n\n")
        }
        return sb.toString().trim()
    }

    companion object {
        fun Factory(appContext: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EpubViewerViewModel(appContext) as T
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubViewerScreen(filePath: String, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: EpubViewerViewModel = viewModel(
        factory = EpubViewerViewModel.Factory(context.applicationContext)
    )
    LaunchedEffect(filePath) { vm.loadEpub(filePath) }

    val text by vm.text.collectAsState()
    val title by vm.title.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, File(filePath)) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
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
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                error != null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
                text.isNotBlank() -> MarkdownPreviewPane(markdown = text)
                else -> Text(
                    "No text content found",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Shared text preview ─────────────────────────────────────────────────────

@Composable
private fun MarkdownPreviewPane(markdown: String) {
    val context = LocalContext.current
    val markwon = remember(context) {
        io.noties.markwon.Markwon.builder(context).build()
    }
    AndroidView(
        factory = {
            TextView(it).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setPadding(40, 32, 40, 32)
                setTextIsSelectable(true)
            }
        },
        update = { tv -> markwon.setMarkdown(tv, markdown) },
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    )
}
