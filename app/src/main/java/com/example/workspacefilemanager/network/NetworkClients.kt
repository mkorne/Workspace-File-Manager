package com.example.workspacefilemanager.network

import android.content.Context
import android.util.Log
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Vector

/**
 * SFTP client wrapper around JSch.
 * Provides connect, disconnect, list, download, upload, and delete operations.
 */
class NetworkClientRepository(private val context: Context) {

    companion object {
        private const val TAG = "NetworkClientRepo"
    }

    private var jsch: com.jcraft.jsch.JSch? = null
    private var session: com.jcraft.jsch.Session? = null
    private var sftpChannel: com.jcraft.jsch.ChannelSftp? = null

    /** Connect to an SFTP server. Returns true on success. */
    suspend fun connectSftp(host: String, port: Int, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                disconnectSftp()
                jsch = com.jcraft.jsch.JSch()
                session = jsch!!.getSession(username, host, port)
                session?.setPassword(password)
                session?.setConfig("StrictHostKeyChecking", "no")
                session?.connect(10_000)
                sftpChannel = session?.openChannel("sftp") as? com.jcraft.jsch.ChannelSftp
                sftpChannel?.connect(5_000)
                Log.i(TAG, "SFTP connected to $host:$port as $username")
                true
            } catch (e: Exception) {
                Log.e(TAG, "SFTP connect failed: ${e.message}")
                disconnectSftp()
                false
            }
        }

    fun disconnectSftp() {
        try { sftpChannel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        sftpChannel = null
        session = null
        jsch = null
    }

    /** List remote directory contents. Returns list of SftpEntry. */
    suspend fun listRemoteDir(remotePath: String): List<SftpEntry> = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext emptyList()
        try {
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(remotePath) as Vector<com.jcraft.jsch.ChannelSftp.LsEntry>
            entries.map { entry ->
                val attrs = entry.attrs
                SftpEntry(
                    name = entry.filename,
                    isDirectory = attrs.isDir,
                    size = attrs.size,
                    permissions = attrs.permissions.toString(16)
                )
            }.filter { it.name != "." && it.name != ".." }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list $remotePath: ${e.message}")
            emptyList()
        }
    }

    /** Download a remote file to local destination. Returns the local file path on success. */
    suspend fun downloadFile(remotePath: String, localPath: String): String? = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext null
        try {
            val localFile = File(localPath)
            localFile.parentFile?.mkdirs()
            channel.get(remotePath, localPath)
            Log.i(TAG, "Downloaded $remotePath -> $localPath")
            localPath
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }

    /** Upload a local file to remote path. Returns true on success. */
    suspend fun uploadFile(localPath: String, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        try {
            channel.put(localPath, remotePath)
            Log.i(TAG, "Uploaded $localPath -> $remotePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            false
        }
    }

    /** Delete a remote file or directory. */
    suspend fun deleteRemote(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val channel = sftpChannel ?: return@withContext false
        try {
            channel.rm(remotePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}")
            false
        }
    }

    data class SftpEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val permissions: String
    )
}

/**
 * SMB client wrapper around jcifs-ng.
 * Provides browse, download, upload, and delete operations over SMB/CIFS shares.
 */
class SmbClient(private val context: Context) {

    companion object {
        private const val TAG = "SmbClient"
        private val anonymousCtx = SingletonContext.getInstance()
    }

    private fun ctx(user: String, pass: String): jcifs.CIFSContext {
        return if (user.isNotEmpty()) {
            anonymousCtx.withCredentials(NtlmPasswordAuthenticator("", user, pass))
        } else {
            anonymousCtx
        }
    }

    /**
     * Browse an SMB share.
     * @param host   Server hostname or IP
     * @param share  Share name (e.g. "shared")
     * @param user   Username (may be empty for anonymous)
     * @param pass   Password
     * @return List of file/directory names in the share root
     */
    suspend fun browseShare(
        host: String,
        share: String,
        user: String = "",
        pass: String = ""
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        try {
            val url = "smb://$host/$share/"
            val smbFile = SmbFile(url, ctx(user, pass))
            smbFile.listFiles()
                ?.map { f ->
                    SmbEntry(
                        name = f.name,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }
                ?.filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "SMB browse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Browse a specific path within an SMB share.
     */
    suspend fun browsePath(
        host: String,
        share: String,
        path: String,
        user: String = "",
        pass: String = ""
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (path.startsWith("/")) "smb://$host/$share$path"
                           else "smb://$host/$share/$path"
            val smbFile = SmbFile(fullPath, ctx(user, pass))
            smbFile.listFiles()
                ?.map { f ->
                    SmbEntry(
                        name = f.name,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.length() else 0L,
                        lastModified = f.lastModified()
                    )
                }
                ?.filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "SMB browse failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Download a file from SMB share to local path.
     */
    suspend fun downloadFile(
        host: String,
        share: String,
        remotePath: String,
        localPath: String,
        user: String = "",
        pass: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "smb://$host/$share/$remotePath"
            val smbFile = SmbFile(url, ctx(user, pass))
            val out = File(localPath).outputStream()
            smbFile.getInputStream().use { inp -> inp.copyTo(out) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMB download failed: ${e.message}")
            false
        }
    }

    /**
     * Upload a local file to SMB share.
     */
    suspend fun uploadFile(
        host: String,
        share: String,
        remotePath: String,
        localPath: String,
        user: String = "",
        pass: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "smb://$host/$share/$remotePath"
            val smbFile = SmbFile(url, ctx(user, pass))
            File(localPath).inputStream().use { inp ->
                smbFile.getOutputStream().use { out -> inp.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMB upload failed: ${e.message}")
            false
        }
    }

    /**
     * Delete a file or directory on the SMB share.
     */
    suspend fun deleteRemote(
        host: String,
        share: String,
        remotePath: String,
        user: String = "",
        pass: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "smb://$host/$share/$remotePath"
            val smbFile = SmbFile(url, ctx(user, pass))
            smbFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMB delete failed: ${e.message}")
            false
        }
    }

    data class SmbEntry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )
}
