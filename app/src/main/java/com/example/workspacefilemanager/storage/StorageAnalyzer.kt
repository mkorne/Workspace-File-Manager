package com.example.workspacefilemanager.storage

import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.MessageDigest
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

/**
 * Analyzes storage usage and finds duplicate files.
 * Uses Kotlin Coroutines for background processing to avoid blocking the main thread.
 */
class StorageAnalyzer {

    companion object {
        private val MD5 = "MD5"
        private val SHA_256 = "SHA-256"
    }

    /**
     * Recursively scans a directory and returns a flow of folder sizes.
     * Emits multiple times during scanning for progress updates.
     *
     * @param rootDir The directory to scan (typically external storage root)
     * @return Flow of Map[folder path, size in bytes]
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun scanFolderSizes(rootDir: File): Flow<Map<String, Long>> = flow {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            emit(emptyMap())
            return@flow
        }

        // Use a HashMap to accumulate sizes
        val folderSizes = HashMap<String, Long>()
        val visited = HashSet<File>()

        // Process directories in breadth-first manner to emit updates regularly
        val queue = ArrayDeque<File>()
        queue.add(rootDir)

        while (queue.isNotEmpty()) {
            val currentDir = queue.removeFirst()
            if (!visited.add(currentDir)) continue  // Skip if already visited

            var dirSize = 0L
            val files = currentDir.listFiles() ?: continue

            for (file in files) {
                if (file.isDirectory) {
                    queue.add(file)
                } else {
                    try {
                        val size = file.length()
                        dirSize += size
                        // Propagate size to parent folders
                        var parent = file.parentFile
                        while (parent != null && parent != rootDir.parentFile) {
                            val parentPath = parent.absolutePath
                            folderSizes[parentPath] = folderSizes.getOrDefault(parentPath, 0L) + size
                            parent = parent.parentFile
                        }
                    } catch (e: Exception) {
                        // Unable to read file size, skip
                    }
                }
            }

            // Update size for current directory
            folderSizes[currentDir.absolutePath] = dirSize

            // Emit progress every 50 directories processed or at the end
            if (folderSizes.size % 50 == 0 || queue.isEmpty()) {
                emit(HashMap(folderSizes))
            }

            // Yield control to maintain responsiveness
            if (Thread.currentThread().isInterrupted) return@flow
        }

        // Final emission
        emit(HashMap(folderSizes))
    }

    /**
     * Finds duplicate files by first grouping by size, then hashing only those with identical sizes.
     * Returns a flow of groups of duplicate files (each group has at least 2 files).
     *
     * @param rootDir The directory to scan for duplicates
     * @param hashAlgorithm Either "MD5" or "SHA-256"
     * @return Flow of List[File] where each list contains duplicate files
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun findDuplicateFiles(
        rootDir: File,
        hashAlgorithm: String = SHA_256
    ): Flow<List<List<File>>> = flow {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            emit(emptyList())
            return@flow
        }

        // Step 1: Group files by size
        val sizeGroups = HashMap<Long, MutableList<File>>()
        val filesToProcess = ArrayDeque<File>()
        filesToProcess.add(rootDir)

        while (filesToProcess.isNotEmpty()) {
            val current = filesToProcess.removeFirst()
            if (current.isDirectory) {
                val children = current.listFiles() ?: continue
                filesToProcess.addAll(children)
            } else {
                try {
                    val size = current.length()
                    sizeGroups.getOrPut(size) { mutableListOf() }.add(current)
                } catch (e: Exception) {
                    // Unable to read file size, skip
                }
            }
            // Yield occasionally
            if (filesToProcess.size % 1000 == 0) {
                // Emit intermediate progress? Not required for duplicates
            }
        }

        // Step 2: For each size group with >1 file, hash files to find true duplicates
        val duplicateGroups = mutableListOf<List<File>>()
        val digest = MessageDigest.getInstance(hashAlgorithm)

        sizeGroups.forEach { size, files ->
            if (files.size < 2) return@forEach  // No possibility of duplicates

            // Group by hash
            val hashGroups = HashMap<String, MutableList<File>>()
            files.forEach { file ->
                try {
                    val hash = file.inputStream().use { digest.digest(it.readBytes()) }
                        .joinToString("") { "%02x".format(it) }
                    hashGroups.getOrPut(hash) { mutableListOf() }.add(file)
                } catch (e: Exception) {
                    // Unable to read file, skip
                }
            }

            // Collect groups with >=2 files (actual duplicates)
            hashGroups.forEach { hash, dupFiles ->
                if (dupFiles.size >= 2) {
                    duplicateGroups.add(dupFiles.toList())
                }
            }
        }

        emit(duplicateGroups)
    }

    /**
     * Gets the total size of a directory recursively.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun getDirectorySize(dir: File): Long {
        return dir.walk()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /**
     * Gets a list of all root directories available for scanning.
     * Returns a list of roots including primary external storage and SD cards if available.
     */
    fun getStorageRoots(): List<File> {
        val roots = mutableListOf<File>()

        // Primary external storage
        val externalStorage = Environment.getExternalStorageDirectory()
        if (externalStorage.exists()) {
            roots.add(externalStorage)
        }

        // On Android KitKat and above, we can try to get secondary storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val storageVolumeFactory = StorageVolumeFactory()
            val storageVolumes = storageVolumeFactory.getStorageVolumes()
            storageVolumes.forEach { volume ->
                val file = File(volume.path)
                if (file.exists() && file.isDirectory && !roots.contains(file)) {
                    roots.add(file)
                }
            }
        }

        return roots.distinct()
    }

    /**
     * Helper class to access StorageManager and get storage volumes.
     * Requires appropriate permissions.
     */
    private class StorageVolumeFactory {
        @Suppress("DEPRECATION")
        fun getStorageVolumes(): List<StorageVolume> {
            val storageVolumes = mutableListOf<StorageVolume>()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val storageManager =
                        java.lang.Class.forName("android.os.storage.StorageManager")
                            .getConstructor()
                            .newInstance()
                    val getStorageVolumesMethod =
                        storageManager.javaClass.getMethod("getStorageVolumes")
                    @Suppress("UNCHECKED_CAST")
                    val volumes = getStorageVolumesMethod.invoke(storageManager) as Array<out Any>
                    volumes.forEach { vol ->
                        val pathMethod = vol.javaClass.getMethod("getPath")
                        val path = pathMethod.invoke(vol) as? String ?: return@forEach
                        val isPrimaryMethod = vol.javaClass.getMethod("isPrimary")
                        val isPrimary = isPrimaryMethod.invoke(vol) as? Boolean ?: return@forEach
                        storageVolumes.add(StorageVolume(path, isPrimary))
                    }
                } else {
                    // Pre-N: fallback to environment directory
                    val externalStorage = Environment.getExternalStorageDirectory()
                    storageVolumes.add(StorageVolume(externalStorage.absolutePath, true))
                }
            } catch (e: Exception) {
                // Fallback to primary external storage
                val externalStorage = Environment.getExternalStorageDirectory()
                storageVolumes.add(StorageVolume(externalStorage.absolutePath, true))
            }
            return storageVolumes
        }
    }

    data class StorageVolume(val path: String, val isPrimary: Boolean)
}