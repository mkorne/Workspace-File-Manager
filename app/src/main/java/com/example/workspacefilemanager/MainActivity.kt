package com.example.workspacefilemanager

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.workspacefilemanager.ai.AiPanel
import com.example.workspacefilemanager.ai.AiViewModel
import com.example.workspacefilemanager.files.DocxViewerScreen
import com.example.workspacefilemanager.files.EpubViewerScreen
import com.example.workspacefilemanager.files.FileBrowserScreen
import com.example.workspacefilemanager.files.FileBrowserViewModel
import com.example.workspacefilemanager.files.ImageViewerScreen
import com.example.workspacefilemanager.files.PdfViewerScreen
import com.example.workspacefilemanager.media.MediaPlayerScreen
import com.example.workspacefilemanager.network.NetworkScreen
import com.example.workspacefilemanager.network.NetworkViewModel
import com.example.workspacefilemanager.storage.StorageAnalyticsScreen
import com.example.workspacefilemanager.study.StudyScreen
import com.example.workspacefilemanager.ui.SettingsScreen
import com.example.workspacefilemanager.ui.theme.ThemeMode
import com.example.workspacefilemanager.ui.theme.WorkspaceTheme
import com.example.workspacefilemanager.util.FileActions
import com.example.workspacefilemanager.workspace.WorkspaceEditorScreen
import com.example.workspacefilemanager.workspace.WorkspaceEditorViewModel

// ── Nav destinations ──────────────────────────────────────────────────────

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Files : Screen("files", "Files", Icons.Default.Folder)
    object Workspace : Screen("workspace", "Workspace", Icons.Default.Edit)
    object Storage : Screen("storage", "Storage", Icons.Default.Analytics)
    object Network : Screen("network", "Network", Icons.Default.Wifi)
    object Media : Screen("media", "Media", Icons.Default.PlayCircle)
    object AI : Screen("ai", "AI", Icons.Default.AutoAwesome)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

private const val ROUTE_PDF = "pdf_viewer"
private const val ROUTE_DOCX = "docx_viewer"
private const val ROUTE_EPUB = "epub_viewer"
private const val ROUTE_IMAGE = "image_viewer"
private const val ROUTE_STUDY = "study"

private val bottomNavItems = listOf(
    Screen.Files, Screen.Workspace, Screen.Storage,
    Screen.Network, Screen.Media, Screen.AI, Screen.Settings
)

private val fullScreenRoutes = setOf(ROUTE_PDF, ROUTE_DOCX, ROUTE_EPUB, ROUTE_IMAGE, ROUTE_STUDY)

// ── Activity ──────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    /** READ/WRITE_EXTERNAL_STORAGE runtime prompt for API 26–29 (pre "All files access"). */
    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                storagePermissionLauncher.launch(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    )
                )
            }
        } else if (
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            legacyStorageLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        setContent {
            val prefs = remember { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
            var themeSelection by remember { mutableIntStateOf(prefs.getInt("theme_selection", 0)) }
            val themeMode = when (themeSelection) {
                1 -> ThemeMode.Light
                2 -> ThemeMode.Dark
                else -> ThemeMode.System
            }
            WorkspaceTheme(themeMode = themeMode) {
                WorkspaceApp(
                    themeSelection = themeSelection,
                    onThemeSelected = { themeSelection = it }
                )
            }
        }
    }
}

// ── Root composable ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceApp(
    themeSelection: Int = 0,
    onThemeSelected: (Int) -> Unit = {}
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val aiVm: AiViewModel = viewModel()
    val browserVm: FileBrowserViewModel = viewModel()

    // Material handed to the Study screen by the Workspace editor.
    var studySource by remember { mutableStateOf<Pair<String, String>?>(null) }
    val editorVm: WorkspaceEditorViewModel = viewModel(
        factory = WorkspaceEditorViewModel.Factory(context.applicationContext)
    )
    val networkVm: NetworkViewModel = viewModel()

    LaunchedEffect(Unit) {
        aiVm.init(context.applicationContext)
        networkVm.init(context.applicationContext)
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val inFullScreen = fullScreenRoutes.any { route ->
        currentRoute?.startsWith(route) == true
    }

    val openFile: (String) -> Unit = { path ->
        openFileWithAppropriateHandler(context, path, navController, editorVm)
    }

    Scaffold(
        bottomBar = {
            if (!inFullScreen) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            // Seven tabs on a ~400dp phone leaves ~57dp per
                            // label; 12sp truncates "Workspace".
                            label = {
                                Text(
                                    screen.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            // Compare by prefix so routes with optional args
                            // (media?path=…) still highlight their tab.
                            selected = backStackEntry?.destination?.hierarchy
                                ?.any { it.route?.substringBefore('?') == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Files.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Files.route) {
                FileBrowserScreen(viewModel = browserVm, onOpenFile = openFile)
            }
            composable(Screen.Workspace.route) {
                WorkspaceEditorScreen(
                    editorVm = editorVm,
                    aiVm = aiVm,
                    onStudy = { title, text ->
                        studySource = title to text
                        navController.navigate(ROUTE_STUDY)
                    }
                )
            }
            composable(ROUTE_STUDY) {
                StudyScreen(
                    initialTitle = studySource?.first,
                    initialText = studySource?.second,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Storage.route) {
                StorageAnalyticsScreen()
            }
            composable(Screen.Network.route) {
                NetworkScreen(networkVm = networkVm)
            }
            composable("${Screen.Media.route}?path={path}") { entry ->
                val path = entry.arguments?.getString("path")?.let { Uri.decode(it) }
                MediaPlayerScreen(
                    initialPath = path,
                    onOpenImage = { imagePath ->
                        navController.navigate("$ROUTE_IMAGE?path=${Uri.encode(imagePath)}")
                    },
                    onOpenFile = openFile,
                    onOpenStorage = {
                        navController.navigate(Screen.Storage.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.AI.route) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AiPanel(viewModel = aiVm)
                }
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    context = context,
                    networkVm = networkVm,
                    themeSelection = themeSelection,
                    onThemeSelected = onThemeSelected,
                    aiVm = aiVm
                )
            }
            composable("$ROUTE_PDF?path={path}") { entry ->
                val path = entry.arguments?.getString("path")?.let { Uri.decode(it) }
                if (path != null) {
                    PdfViewerScreen(
                        filePath = path,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("$ROUTE_DOCX?path={path}") { entry ->
                val path = entry.arguments?.getString("path")?.let { Uri.decode(it) }
                if (path != null) {
                    DocxViewerScreen(
                        filePath = path,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("$ROUTE_EPUB?path={path}") { entry ->
                val path = entry.arguments?.getString("path")?.let { Uri.decode(it) }
                if (path != null) {
                    EpubViewerScreen(
                        filePath = path,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            composable("$ROUTE_IMAGE?path={path}") { entry ->
                val path = entry.arguments?.getString("path")?.let { Uri.decode(it) }
                if (path != null) {
                    ImageViewerScreen(
                        filePath = path,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/** Route a file to the right in-app viewer, falling back to another installed app. */
private fun openFileWithAppropriateHandler(
    context: Context,
    path: String,
    navController: androidx.navigation.NavHostController,
    editorVm: WorkspaceEditorViewModel
) {
    val file = java.io.File(path)
    if (!file.exists()) {
        FileActions.toast(context, "File no longer exists")
        return
    }
    val ext = file.name.lowercase().substringAfterLast('.', "")

    when {
        // Images used to be pushed through Uri.fromFile(), which throws
        // FileUriExposedException on API 24+ — hence "No app found to open".
        FileActions.isImage(file.name) ->
            navController.navigate("$ROUTE_IMAGE?path=${Uri.encode(path)}")

        ext == "pdf" -> navController.navigate("$ROUTE_PDF?path=${Uri.encode(path)}")
        ext == "docx" || ext == "doc" -> navController.navigate("$ROUTE_DOCX?path=${Uri.encode(path)}")
        ext == "epub" -> navController.navigate("$ROUTE_EPUB?path=${Uri.encode(path)}")

        FileActions.isVideo(file.name) || FileActions.isAudio(file.name) ->
            navController.navigate("${Screen.Media.route}?path=${Uri.encode(path)}")

        ext in setOf(
            "txt", "md", "log", "csv", "json", "xml", "yaml", "yml",
            "py", "js", "ts", "jsx", "tsx", "java", "kt", "kts", "c", "cpp", "h",
            "hpp", "cs", "go", "rs", "rb", "sh", "bat", "toml"
        ) -> {
            editorVm.loadFile(path)
            navController.navigate(Screen.Workspace.route)
        }

        else -> FileActions.openExternally(context, file)
    }
}
