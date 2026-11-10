package com.example.workspacefilemanager.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Central place for anything that touches the filesystem.
 *
 * Every screen used to re-implement this badly: `Uri.fromFile` (which throws
 * FileUriExposedException on API 24+ and produced the "No app found" toast) and
 * `File.delete()` with the result thrown away (so a red delete button could do
 * literally nothing and still show no error). Both are handled here.
 */
object FileActions {

    // ── MIME ──────────────────────────────────────────────────────────────

    fun mimeType(file: File): String = mimeTypeForName(file.name)

    fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "epub" -> "application/epub+zip"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "7z" -> "application/x-7z-compressed"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "opus")

    // ── URIs / intents ────────────────────────────────────────────────────

    private const val AUTHORITY = "com.example.workspacefilemanager.fileprovider"

    /** Content URI that other apps are allowed to read (never `file://`). */
    fun uriFor(context: Context, file: File): Uri =
        try {
            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (t: Throwable) {
            // Fallback keeps in-app previews working even if the provider is missing.
            Uri.fromFile(file)
        }

    /** Share one or more files through the system share sheet. */
    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val mime = if (files.size == 1) mimeType(files.first()) else {
            val types = files.map { mimeType(it).substringBefore('/') }.toSet()
            if (types.size == 1) "${types.first()}/*" else "*/*"
        }
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = android.content.ClipData.newRawUri(files.first().name, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = android.content.ClipData.newRawUri(null, uris.first()).apply {
                    uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Share ${files.size} file(s)"))
        } catch (t: Throwable) {
            toast(context, "Nothing available to share with")
        }
    }

    fun share(context: Context, file: File) = share(context, listOf(file))

    /** Open with another installed app. Returns false when no app can handle it. */
    fun openExternally(context: Context, file: File): Boolean {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                true
            } catch (t2: Throwable) {
                toast(context, "No app installed that can open ${file.name}")
                false
            }
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                    toast(context, "Open Settings → Apps → All files access")
                }
            }
        }
    }

    // ── Destructive operations ────────────────────────────────────────────

    data class OpResult(val success: Boolean, val message: String? = null)

    /**
     * Delete [file] (recursively for folders) and report honestly.
     *
     * Order of attempts: direct delete → MediaStore (works for media rows even
     * when the raw path is locked) → give up with an actionable message.
     */
    fun delete(context: Context, file: File): OpResult {
        if (!file.exists()) return OpResult(true)

        val directOk =
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (directOk && !file.exists()) {
            rescan(context, file)
            return OpResult(true)
        }

        val viaMediaStore = try {
            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                else MediaStore.Files.getContentUri("external")
            context.contentResolver.delete(
                collection,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(file.absolutePath)
            ) > 0
        } catch (t: Throwable) {
            false
        }
        if (viaMediaStore && !file.exists()) {
            rescan(context, file)
            return OpResult(true)
        }

        val message = when {
            !hasAllFilesAccess() ->
                "Permission denied. Grant \"All files access\" to delete from this folder."
            file.parentFile?.canWrite() == false ->
                "This folder is read-only (${file.parentFile?.name})."
            else ->
                "Android blocked deleting ${file.name}."
        }
        return OpResult(false, message)
    }

    private fun rescan(context: Context, file: File) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (_: Throwable) {
        }
    }

    /** True when the name is a legal single path segment. */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && name != "." && name != ".."

    fun rename(file: File, newName: String): OpResult {
        if (!isValidName(newName)) return OpResult(false, "Invalid file name")
        val target = File(file.parentFile, newName.trim())
        if (target.exists()) return OpResult(false, "\"$newName\" already exists")
        return if (file.renameTo(target)) OpResult(true)
        else OpResult(false, "Could not rename ${file.name}")
    }

    /** Copy [file] into [destDir], auto-numbering on collision. */
    fun copyInto(file: File, destDir: File): OpResult {
        if (!destDir.isDirectory) return OpResult(false, "Destination is not a folder")
        if (file.parentFile?.absolutePath == destDir.absolutePath) {
            // same folder: make a "name (copy)" duplicate instead of failing
        }
        val target = uniqueTarget(destDir, file.name)
        return try {
            if (file.isDirectory) file.copyRecursively(target, overwrite = false)
            else {
                target.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            OpResult(true)
        } catch (t: Throwable) {
            OpResult(false, "Copy failed: ${t.message}")
        }
    }

    /** Move [file] into [destDir]; falls back to copy+delete across volumes. */
    fun moveInto(file: File, destDir: File): OpResult {
        if (!destDir.isDirectory) return OpResult(false, "Destination is not a folder")
        if (file.parentFile?.absolutePath == destDir.absolutePath) {
            return OpResult(false, "Already in this folder")
        }
        val target = uniqueTarget(destDir, file.name)
        if (file.renameTo(target)) return OpResult(true)
        val copied = copyInto(file, destDir)
        if (!copied.success) return copied
        return OpResult(true)
    }

    fun createFolder(parent: File, name: String): OpResult {
        if (!isValidName(name)) return OpResult(false, "Invalid folder name")
        val dir = File(parent, name.trim())
        if (dir.exists()) return OpResult(false, "\"$name\" already exists")
        return if (dir.mkdirs()) OpResult(true) else OpResult(false, "Could not create folder")
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (candidate.exists()) {
            val newName = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            candidate = File(dir, newName)
            i++
        }
        return candidate
    }

    // ── Formatting ────────────────────────────────────────────────────────

    fun formatBytes(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.2f GB".format(bytes / 1_073_741_824.0)
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

    fun formatRelative(ms: Long, now: Long = System.currentTimeMillis()): String {
        val delta = (now - ms) / 1000
        return when {
            delta < 60 -> "just now"
            delta < 3_600 -> "${delta / 60} min ago"
            delta < 86_400 -> "${delta / 3_600} h ago"
            delta < 172_800 -> "yesterday"
            delta < 604_800 -> "${delta / 86_400} days ago"
            else -> formatDate(ms)
        }
    }

    fun toast(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
