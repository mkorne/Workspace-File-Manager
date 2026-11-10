package com.example.workspacefilemanager.util

import android.content.Context
import java.io.File

/** Starred files, persisted as a set of absolute paths. */
object Starred {

    private const val PREFS = "starred_files"
    private const val KEY = "paths"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun paths(context: Context): List<File> =
        prefs(context).getStringSet(KEY, emptySet())
            .orEmpty()
            .map { File(it) }
            .filter { it.exists() }
            .sortedByDescending { it.lastModified() }

    fun isStarred(context: Context, file: File): Boolean =
        prefs(context).getStringSet(KEY, emptySet())
            .orEmpty()
            .contains(file.absolutePath)

    fun toggle(context: Context, file: File): Boolean {
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val nowStarred = if (current.contains(file.absolutePath)) {
            current.remove(file.absolutePath)
            false
        } else {
            current.add(file.absolutePath)
            true
        }
        prefs(context).edit().putStringSet(KEY, current).apply()
        return nowStarred
    }

    fun removeMissing(context: Context): Int {
        val current = prefs(context).getStringSet(KEY, emptySet()).orEmpty()
        val alive = current.filter { File(it).exists() }.toSet()
        val removed = current.size - alive.size
        if (removed > 0) prefs(context).edit().putStringSet(KEY, alive).apply()
        return removed
    }
}

/**
 * A private folder inside the app sandbox. Files moved here are no longer
 * visible to other apps or the gallery — a real (non-encrypted) safe folder.
 */
object SafeFolder {

    fun dir(context: Context): File =
        File(context.filesDir, "safe_folder").apply { if (!exists()) mkdirs() }

    fun files(context: Context): List<File> =
        dir(context).listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun totalSize(context: Context): Long = files(context).sumOf { it.length() }

    /** Move a file into the safe folder; returns the new location on success. */
    fun put(context: Context, file: File): File? {
        val result = FileActions.moveInto(file, dir(context))
        if (!result.success) return null
        val base = file.name.substringBeforeLast('.', file.name)
        // moveInto auto-numbers on collision, so find the newest match.
        return dir(context).listFiles()
            ?.filter { it.name.startsWith(base) }
            ?.maxByOrNull { it.lastModified() }
    }
}
