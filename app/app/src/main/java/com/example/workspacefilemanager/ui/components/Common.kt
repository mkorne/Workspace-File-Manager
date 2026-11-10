package com.example.workspacefilemanager.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── File kinds ────────────────────────────────────────────────────────────

enum class FileKind(val label: String, val icon: ImageVector) {
    FOLDER("Folder", Icons.Default.Folder),
    IMAGE("Image", Icons.Default.Image),
    VIDEO("Video", Icons.Default.Movie),
    AUDIO("Audio", Icons.Default.MusicNote),
    PDF("PDF", Icons.Default.PictureAsPdf),
    WORD("Document", Icons.Default.Description),
    SHEET("Spreadsheet", Icons.Default.TableChart),
    SLIDE("Slides", Icons.Default.Article),
    ARCHIVE("Archive", Icons.Default.InsertDriveFile),
    CODE("Code", Icons.Default.Code),
    TEXT("Text", Icons.Default.Article),
    APK("Package", Icons.Default.Android),
    OTHER("File", Icons.Default.InsertDriveFile)
}

fun fileKind(name: String, isDir: Boolean): FileKind {
    if (isDir) return FileKind.FOLDER
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif" -> FileKind.IMAGE
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts" -> FileKind.VIDEO
        "mp3", "aac", "flac", "wav", "ogg", "oga", "m4a", "opus", "wma" -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        "doc", "docx", "odt", "rtf", "pages" -> FileKind.WORD
        "xls", "xlsx", "ods", "csv", "tsv" -> FileKind.SHEET
        "ppt", "pptx", "odp", "key" -> FileKind.SLIDE
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso" -> FileKind.ARCHIVE
        "kt", "kts", "java", "c", "cpp", "h", "hpp", "cs", "py", "js", "ts",
        "jsx", "tsx", "go", "rs", "rb", "sh", "bat", "json", "xml", "yaml",
        "yml", "toml", "html", "css", "sql" -> FileKind.CODE
        "txt", "md", "markdown", "log", "ini", "cfg", "conf" -> FileKind.TEXT
        "apk", "xapk", "apks" -> FileKind.APK
        else -> FileKind.OTHER
    }
}

@Composable
fun FileKind.tint(): Color = when (this) {
    FileKind.FOLDER -> MaterialTheme.colorScheme.primary
    FileKind.IMAGE -> Color(0xFF2E9E6B)
    FileKind.VIDEO -> Color(0xFF7C5CE6)
    FileKind.AUDIO -> Color(0xFFE0713F)
    FileKind.PDF -> Color(0xFFD64545)
    FileKind.WORD -> Color(0xFF2F6FD0)
    FileKind.SHEET -> Color(0xFF1E9B57)
    FileKind.SLIDE -> Color(0xFFD9822B)
    FileKind.ARCHIVE -> Color(0xFF95702F)
    FileKind.CODE -> Color(0xFF0E8B8B)
    FileKind.TEXT -> MaterialTheme.colorScheme.onSurfaceVariant
    FileKind.APK -> Color(0xFF3E9B4F)
    FileKind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Rounded icon tile used across browsable file lists (Files-by-Google style). */
@Composable
fun FileTypeTile(
    name: String,
    isDir: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp
) {
    val kind = fileKind(name, isDir)
    val tint = kind.tint()
    Box(
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = kind.label,
            tint = tint,
            modifier = Modifier.size(size * 0.52f)
        )
    }
}

// ── Layout helpers ────────────────────────────────────────────────────────

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable Column.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp
    ) {
        Column(content = { content() })
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(18.dp)
                    .size(38.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun DividerRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) { Spacer(modifier = Modifier.width(0.dp)) }
}
