package com.example.workspacefilemanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.workspacefilemanager.ai.AIModels
import com.example.workspacefilemanager.ai.AiViewModel
import com.example.workspacefilemanager.network.NetworkViewModel
import com.example.workspacefilemanager.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun ToggleItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    title: String,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    trailing: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (tint == MaterialTheme.colorScheme.onSurface)
                MaterialTheme.colorScheme.primary else tint
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: Context,
    networkVm: NetworkViewModel,
    themeSelection: Int,
    onThemeSelected: (Int) -> Unit,
    aiVm: AiViewModel? = null
) {
    val ftpRunning by networkVm.ftpRunning.collectAsState()
    val ftpAddress by networkVm.ftpAddress.collectAsState()
    val ftpPort by networkVm.ftpPort.collectAsState()
    val modelPathFlow = remember(aiVm) {
        aiVm?.modelPath ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
    val aiModelPath by modelPathFlow.collectAsState()

    val prefs = remember(context) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var importedModel by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    // ── GGUF import ───────────────────────────────────────────────────────
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(context, uri)
                        ?: "imported-${System.currentTimeMillis()}.gguf"
                    val destDir = File(AIModels.getOrCreateModelsDir(context))
                    destDir.mkdirs()
                    val dest = File(destDir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Could not read that file")
                    dest.absolutePath
                }
            }
            result.onSuccess { path ->
                importedModel = path
                aiVm?.loadModelAtPath(path)
                prefs.edit().putString("gguf_model_path", path).apply()
                FileActions.toast(context, "Loaded ${File(path).name}")
            }.onFailure { t ->
                importError = t.message ?: "Import failed"
                FileActions.toast(context, importError!!)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // ── Network ──────────────────────────────────────────────────
            item { SectionHeader("Network Services") }
            item {
                ToggleItem(
                    icon = Icons.Default.Cloud,
                    title = "FTP Server",
                    description = if (ftpRunning)
                        "Serving on port $ftpPort"
                    else
                        "Share files over Wi-Fi with any FTP client",
                    checked = ftpRunning,
                    onToggle = { on -> if (on) networkVm.startFtp() else networkVm.stopFtp() }
                )
            }
            item {
                ActionItem(
                    icon = Icons.Default.Public,
                    title = "Server Address",
                    description = if (ftpRunning && ftpAddress != null)
                        "Tap to copy · ftp://$ftpAddress:$ftpPort"
                    else
                        "Not running — start the FTP server first",
                    trailing = if (ftpRunning) "Copy" else null,
                    onClick = {
                        if (ftpRunning && ftpAddress != null) {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("FTP address", "ftp://$ftpAddress:$ftpPort")
                            )
                            FileActions.toast(context, "Address copied")
                        } else {
                            FileActions.toast(context, "Start the FTP server first")
                        }
                    }
                )
            }
            item {
                ActionItem(
                    icon = Icons.Default.SettingsEthernet,
                    title = "FTP Port",
                    description = "Port $ftpPort · need internet permission on the client",
                    onClick = { showPortDialog = true }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            // ── Appearance ───────────────────────────────────────────────
            item { SectionHeader("Appearance") }
            item {
                ActionItem(
                    icon = Icons.Default.Palette,
                    title = "Theme",
                    description = "Applies immediately",
                    trailing = when (themeSelection) {
                        1 -> "Light"
                        2 -> "Dark"
                        else -> "System"
                    },
                    onClick = { showThemeDialog = true }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            // ── AI / storage ─────────────────────────────────────────────
            item { SectionHeader("On-device AI") }
            item {
                ActionItem(
                    icon = Icons.Default.FileUpload,
                    title = "Import .gguf model",
                    description = importedModel?.let { File(it).name }
                        ?: aiModelPath?.let { File(it).name }
                        ?: "Empty storage — pick a GGUF file from your device",
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                )
            }
            item {
                ActionItem(
                    icon = Icons.Default.Folder,
                    title = "Models folder",
                    description = AIModels.getOrCreateModelsDir(context),
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Models folder",
                                AIModels.getOrCreateModelsDir(context)
                            )
                        )
                        FileActions.toast(context, "Path copied")
                    }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            // ── Storage ──────────────────────────────────────────────────
            item { SectionHeader("Storage") }
            item {
                ActionItem(
                    icon = Icons.Default.DeleteOutline,
                    title = "Clear App Data",
                    description = "Reset settings, cached models and the workspace document",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { showClearDialog = true }
                )
            }
            item {
                ActionItem(
                    icon = Icons.Default.Storage,
                    title = "Storage Permissions",
                    description = if (FileActions.hasAllFilesAccess())
                        "All files access granted"
                    else
                        "Required to browse and delete files",
                    trailing = if (FileActions.hasAllFilesAccess()) "Granted" else "Grant",
                    onClick = { FileActions.requestAllFilesAccess(context) }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            // ── About ────────────────────────────────────────────────────
            item { SectionHeader("About") }
            item {
                ActionItem(
                    icon = Icons.Default.Info,
                    title = "App Version",
                    description = "WorkspaceFileManager v1.0",
                    onClick = { showAboutDialog = true }
                )
            }
            item {
                ActionItem(
                    icon = Icons.Default.BugReport,
                    title = "Report a Problem",
                    description = "Send feedback or report a bug",
                    onClick = {
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_SUBJECT, "WorkspaceFileManager feedback")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (t: Throwable) {
                            FileActions.toast(context, "No email app installed")
                        }
                    }
                )
            }
        }
    }

    // ── Theme dialog (selection applies instantly) ────────────────────────
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column {
                    listOf(
                        0 to "System default",
                        1 to "Light",
                        2 to "Dark"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeSelected(value)
                                    prefs.edit().putInt("theme_selection", value).apply()
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = themeSelection == value,
                                onClick = {
                                    onThemeSelected(value)
                                    prefs.edit().putInt("theme_selection", value).apply()
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Done") }
            }
        )
    }

    // ── Port dialog ───────────────────────────────────────────────────────
    if (showPortDialog) {
        var portInput by remember { mutableStateOf(ftpPort.toString()) }
        val valid = portInput.toIntOrNull()?.let { it in 1024..65535 } == true
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text("FTP Port") },
            text = {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port (1024–65535)") },
                    isError = !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val port = portInput.toInt()
                        prefs.edit().putInt("ftp_port", port).apply()
                        networkVm.setFtpPort(port)
                        showPortDialog = false
                        FileActions.toast(context, "Port set to $port")
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPortDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Clear data dialog ─────────────────────────────────────────────────
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear app data?") },
            text = {
                Text(
                    "This removes saved settings, the workspace document and downloaded model " +
                        "copies. Your own files on the device are not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().clear().apply()
                    File(context.filesDir, "workspace_doc.json").delete()
                    File(context.filesDir, "workspace_doc.json.tmp").delete()
                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    onThemeSelected(0)
                    showClearDialog = false
                    FileActions.toast(context, "App data cleared")
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── About ─────────────────────────────────────────────────────────────
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About WorkspaceFileManager") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Version 1.0", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "A local-first file manager with an on-device AI assistant, " +
                            "network transfer tools and a block-based workspace editor.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    listOf(
                        "Browse, move, rename and share files",
                        "Gallery-style media browser",
                        "PDF, DOCX and EPUB viewers",
                        "Storage analytics and duplicate finder",
                        "FTP server plus SFTP and SMB clients",
                        "llama.cpp inference with GGUF models"
                    ).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("Close") }
            }
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
    }
} catch (t: Throwable) {
    null
}
