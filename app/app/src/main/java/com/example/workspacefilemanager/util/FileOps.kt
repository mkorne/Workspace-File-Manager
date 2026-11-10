package com.example.workspacefilemanager.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central place for filesystem access, permission checks, MIME routing and
 * deletion. Everything that touches storage from the UI goes through here so
 * behaviour (and failures) are consistent across screens.
 */
object FileOps {

    // ── Permissions ──────────────────────────────────────────────────────

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun canReadMedia(context: Context): Boolean {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                android.Manifest.permission.READ_MEDIA_IMAGES
            else -> android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun openAllFilesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    // ── Storage roots ────────────────────────────────────────────────────

    /** Primary external storage plus any SD card / USB volumes that exist. */
    fun storageRoots(context: Context): List<File> {
        val roots = linkedSetOf(Environment.getExternalStorageDirectory())
        runCatching {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            manager.storageVolumes.forEach { volume ->
                @Suppress("DEPRECATION")
                val dir = volume.directory
                if (dir != null && dir.exists()) roots.add(dir)
            }
        }
        context.getExternalFilesDirs(null).forEach { dir ->
            if (dir != null) {
                // /storage/XXXX-XXXX/Android/data/<pkg>/files -> /storage/XXXX-XXXX
                val root = generateSequence(dir) { it.parentFile }.firstOrNull {
                    it.parentFile?.absolutePath == "/storage"
                }
                if (root != null && root.exists()) roots.add(root)
            }
        }
        return roots.filter { it.exists() && it.canRead() && it.absolutePath != "/storage" }
    }

    /** Well-known folders that exist on this device. */
    fun quickFolders(context: Context): List<Pair<String, File>> {
        val root = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            "Downloads" to File(root, Environment.DIRECTORY_DOWNLOADS),
            "Documents" to File(root, Environment.DIRECTORY_DOCUMENTS),
            "Pictures" to File(root, Environment.DIRECTORY_PICTURES),
            "DCIM" to File(root, Environment.DIRECTORY_DCIM),
            "Music" to File(root, Environment.DIRECTORY_MUSIC),
            "Movies" to File(root, Environment.DIRECTORY_MOVIES)
        )
        return candidates.filter { it.second.exists() }
    }

    // ── MIME / routing ───────────────────────────────────────────────────

    fun guessMime(extOrName: String): String {
        val ext = extOrName.substringAfterLast('.', extOrName).lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "epub" -> "application/epub+zip"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS
    fun isMedia(name: String) = isImage(name) || isVideo(name) || isAudio(name)

    fun isTextViewable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTS
    }

    // ── Sharing / external open ──────────────────────────────────────────

    fun uriFor(context: Context, file: File): Uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrElse { Uri.fromFile(file) }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val mime = if (files.size > 1) "*/*" else guessMime(files.first().name)
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_TITLE, files.first().name)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Share ${files.size} file(s)"))
        }
    }

    /** Open a file with an installed third-party app. Returns false if none. */
    fun openExternally(context: Context, file: File): Boolean {
        val mime = guessMime(file.name)
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
        return runCatching { context.startActivity(intent); true }.getOrElse { false }
    }

    // ── Deletion ─────────────────────────────────────────────────────────

    /**
     * Delete a file, trying MediaStore first and then a direct unlink.
     * Returns true when the file no longer exists afterwards.
     */
    fun deleteFile(context: Context, file: File): Boolean {
        if (!file.exists()) return true

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val collections = buildList {
            when {
                ext in IMAGE_EXTS -> add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                ext in VIDEO_EXTS -> add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                ext in AUDIO_EXTS -> add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                else -> {
                    add(MediaStore.Files.getContentUri("external"))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
                    }
                }
            }
        }
        for (collection in collections) {
            runCatching {
                val rows = context.contentResolver.delete(
                    collection,
                    "${MediaStore.MediaColumns.DATA} = ?",
                    arrayOf(file.absolutePath)
                )
                if (rows > 0 && !file.exists()) return true
            }
        }

        val deleted = runCatching {
            if (!file.canWrite()) file.setWritable(true)
            file.delete()
        }.getOrDefault(false)

        if (!deleted && file.exists()) {
            // Some volumes allow deletion through the SAF-style resolver only.
            runCatching {
                val uri = uriFor(context, file)
                context.contentResolver.delete(uri, null, null)
            }
        }

        runCatching {
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
        }
        return !file.exists()
    }

    fun deleteRecursively(context: Context, file: File): Int {
        if (!file.exists()) return 0
        if (file.isFile) return if (deleteFile(context, file)) 1 else 0
        var count = 0
        file.listFiles()?.forEach { child ->
            count += if (child.isDirectory) deleteRecursively(context, child)
            else if (deleteFile(context, child)) 1 else 0
        }
        runCatching { file.delete() }
        return count
    }

    // ── Rename / duplicate ───────────────────────────────────────────────

    fun rename(file: File, newName: String): File? {
        val safe = newName.trim().ifEmpty { file.name }
        val target = File(file.parentFile ?: return null, safe)
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun uniqueTarget(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$base ($i)$suffix")
            i++
        }
        return candidate
    }
}

// ── Formatting helpers ────────────────────────────────────────────────────

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

fun formatDate(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

fun formatDateTime(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")
private val AUDIO_EXTS = setOf("mp3", "aac", "flac", "wav", "ogg", "oga", "m4a", "opus", "wma")
private val TEXT_EXTS = setOf(
    "txt", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml",
    "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "c",
    "cpp", "h", "hpp", "cs", "go", "rs", "rb", "sh", "bat", "py", "toml", "ini",
    "cfg", "conf", "properties", "sql", "gradle", "gitignore", "env"
)
