package com.example.workspacefilemanager.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// ── WiFi FTP setup instructions ───────────────────────────────────────────

private val FTP_INSTRUCTIONS = listOf(
    "Connect your phone and computer to the same Wi-Fi network",
    "Toggle the FTP server switch ON",
    "Note the FTP address shown (e.g. ftp://192.168.1.5:2221)",
    "Use any FTP client (FileZilla, Explorer, etc.) to connect"
)

// ── Screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(networkVm: NetworkViewModel) {
    val context = LocalContext.current
    val vm = networkVm

    val ftpRunning     by vm.ftpRunning.collectAsState()
    val ftpAddress     by vm.ftpAddress.collectAsState()
    val sftpEntries    by vm.sftpEntries.collectAsState()
    val sftpLoading    by vm.sftpLoading.collectAsState()
    val smbEntries     by vm.smbEntries.collectAsState()
    val smbLoading     by vm.smbLoading.collectAsState()
    val smbPath        by vm.smbPath.collectAsState()
    val notification   by vm.notificationMsg.collectAsState()

    var showSftpDialog by remember { mutableStateOf(false) }
    var showSmbDialog  by remember { mutableStateOf(false) }
    var showSftpBrowse by remember { mutableStateOf(false) }
    var showSmbBrowse  by remember { mutableStateOf(false) }

    LaunchedEffect(notification) {
        notification?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearNotification()
        }
    }

    if (showSftpDialog) SftpConnectDialog(
        onDismiss = { showSftpDialog = false },
        onConnect = { host, port, user, pass ->
            vm.connectSftp(host, port, user, pass)
            showSftpDialog = false
            showSftpBrowse = true
        }
    )
    if (showSmbDialog) SmbConnectDialog(
        onDismiss = { showSmbDialog = false },
        onConnect = { host, share, user, pass ->
            vm.connectSmb(host, share, user, pass)
            showSmbDialog = false
            showSmbBrowse = true
        }
    )
    if (showSftpBrowse) SftpBrowseDialog(
        vm = vm,
        onDismiss = { showSftpBrowse = false; vm.disconnectSftp() }
    )
    if (showSmbBrowse) SmbBrowseDialog(
        vm = vm,
        onDismiss = { showSmbBrowse = false }
    )

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Network Hub") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── FTP server card ───────────────────────────────────────────
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Wifi, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Embedded FTP Server", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = ftpRunning,
                                onCheckedChange = { if (it) vm.startFtp() else vm.stopFtp() }
                            )
                        }

                        AnimatedVisibility(visible = ftpRunning && ftpAddress != null) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedCard {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Link, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            ftpAddress!!,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(top = 6.dp)
                                ) { Text("Server running  •  Port 2221") }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Setup", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        FTP_INSTRUCTIONS.forEachIndexed { i, step ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text("${i + 1}.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(20.dp))
                                Text(step, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // ── SFTP client card ──────────────────────────────────────────
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SFTP Client (JSch)", style = MaterialTheme.typography.titleMedium)
                            Text("Secure file transfer over SSH",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { showSftpDialog = true }) {
                            Text("Connect")
                        }
                    }
                }
            }

            // ── SMB client card ───────────────────────────────────────────
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Computer, contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SMB / Samba Client (jcifs-ng)", style = MaterialTheme.typography.titleMedium)
                            Text("Browse Windows / NAS shares (SMBv2/v3)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { showSmbDialog = true }) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

// ── Connection dialogs ────────────────────────────────────────────────────

@Composable
private fun SftpConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, port: Int, user: String, pass: String) -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect via SFTP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("Host") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(port, { port = it }, label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                OutlinedTextField(user, { user = it }, label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isNotBlank() && port.isNotBlank()) {
                    onConnect(host, port.toIntOrNull() ?: 22, user, pass)
                }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SftpBrowseDialog(vm: NetworkViewModel, onDismiss: () -> Unit) {
    var remotePath by remember { mutableStateOf("/") }
    var pathInput by remember { mutableStateOf("/") }
    val entries by vm.sftpEntries.collectAsState()
    val loading by vm.sftpLoading.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SFTP Browser", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.disconnectSftp(); onDismiss() }) {
                    Text("Disconnect")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                OutlinedTextField(value = pathInput, onValueChange = { pathInput = it },
                    modifier = Modifier.weight(1f), label = { Text("Path") },
                    singleLine = true)
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    remotePath = pathInput
                    vm.listSftpDir(pathInput)
                }) { Text("Go") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn {
                    items(entries) { entry ->
                        SftpEntryRow(entry = entry, onOpen = {
                            if (entry.isDirectory) {
                                remotePath = remotePath.removeSuffix("/") + "/" + entry.name
                                vm.listSftpDir(remotePath)
                            }
                        })
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SftpEntryRow(entry: NetworkClientRepository.SftpEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Text("${if (entry.isDirectory) "folder" else "${entry.size} B"}  •  $entry.permissions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmbConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String, share: String, user: String, pass: String) -> Unit
) {
    var host  by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var user  by remember { mutableStateOf("") }
    var pass  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to SMB Share") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host  = it }, label = { Text("Host / IP") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(share, { share = it }, label = { Text("Share name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(user, { user  = it }, label = { Text("Username (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(pass, { pass  = it }, label = { Text("Password (optional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("Tip: Leave username/password blank for anonymous shares.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (host.isNotBlank() && share.isNotBlank()) {
                    onConnect(host, share, user, pass)
                }
            }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbBrowseDialog(vm: NetworkViewModel, onDismiss: () -> Unit) {
    val entries by vm.smbEntries.collectAsState()
    val loading by vm.smbLoading.collectAsState()
    val path by vm.smbPath.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SMB Browser", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (path.isNotEmpty()) {
                Text("Path: $path", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp))
                Row {
                    IconButton(onClick = { vm.smbNavigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                LazyColumn {
                    items(entries) { entry ->
                        SmbEntryRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) vm.browseSmbPath(
                                    if (path.isEmpty()) entry.name else "$path/${entry.name}"
                                )
                            }
                        )
                    }
                }
            }
            if (!loading && entries.isEmpty()) {
                Text("No items found", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SmbEntryRow(entry: SmbClient.SmbEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (entry.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            if (!entry.isDirectory) {
                Text(formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024          -> "$bytes B"
    bytes < 1_048_576      -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824  -> "%.1f MB".format(bytes / 1_048_576.0)
    else                   -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
