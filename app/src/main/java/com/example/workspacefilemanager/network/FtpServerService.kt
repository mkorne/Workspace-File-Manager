package com.example.workspacefilemanager.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import java.io.File
import java.io.FileWriter
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Foreground service that runs an embedded FTP server using Apache MINA.
 * Maps to the device's root storage directory.
 */
class FtpServerService : Service() {

    private var server: FtpServer? = null
    private lateinit var propertiesFactory: PropertiesUserManagerFactory
    private val TAG = "FtpServerService"
    private val CHANNEL_ID = "ftp_server_channel"
    private val NOTIFICATION_ID = 1001

    companion object {
        const val ACTION_START_SERVER = "com.example.workspacefilemanager.action.START_SERVER"
        const val ACTION_STOP_SERVER  = "com.example.workspacefilemanager.action.STOP_SERVER"
        const val EXTRA_IP_ADDRESS    = "com.example.workspacefilemanager.extra.IP_ADDRESS"
        const val EXTRA_PORT          = "com.example.workspacefilemanager.extra.PORT"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FTP Server Service created")
        createNotificationChannel()
        propertiesFactory = PropertiesUserManagerFactory()
        // Write users properties file in app's private filesDir
        val propsFile = File(filesDir, "ftpusers.properties")
        try {
            propsFile.writeText("""
                # FTP Users
                anonymous.homeDirectory=/sdcard
                anonymous.enable=true
                anonymous.writePermission=true
                anonymous.resolveUserDirectoryLinks=true
            """.trimIndent())
            propertiesFactory.file = propsFile
            Log.d(TAG, "Properties file written to ${propsFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write properties file: ${e.message}")
            propertiesFactory.file = propsFile
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> startFtpServer()
            ACTION_STOP_SERVER  -> stopFtpServer()
        }
        return START_STICKY
    }

    private fun startFtpServer() {
        Log.d(TAG, "Starting FTP server...")
        try {
            stopFtpServerInternal()

            val port = findAvailablePort(2221)
            val rootDir = getExternalStorageRootDirectory()
            Log.d(TAG, "Using root directory: ${rootDir?.absolutePath}")

            // Build user manager with anonymous user
            val userManager = propertiesFactory.createUserManager()
            val user = BaseUser()
            user.name = "anonymous"
            user.homeDirectory = rootDir?.absolutePath ?: filesDir.absolutePath
            user.enabled = true
            user.authorities = listOf(WritePermission())
            userManager.save(user)
            Log.d(TAG, "User 'anonymous' configured")

            val listenerFactory = ListenerFactory()
            listenerFactory.setPort(port)
            listenerFactory.setIdleTimeout(600)

            val factory = FtpServerFactory()
            factory.addListener("default", listenerFactory.createListener())
            factory.userManager = userManager

            server = factory.createServer()
            server?.start()

            val ip = getLocalIpAddress()
            Log.d(TAG, "FTP server started on port $port at ftp://$ip:$port")
            val address = "ftp://$ip:$port"
            updateNotification("FTP Server Running", address)
            startForeground(NOTIFICATION_ID, createNotification("FTP Server Running", address))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FTP server", e)
            updateNotification("FTP Error", e.message ?: "Unknown error")
            try {
                startForeground(NOTIFICATION_ID, createNotification("FTP Error", e.message ?: "Failed to start"))
            } catch (_: Exception) {}
        }
    }

    private fun stopFtpServer() {
        Log.d(TAG, "Stopping FTP server...")
        stopFtpServerInternal()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopFtpServerInternal() {
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
        server = null
        Log.d(TAG, "FTP server stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FTP Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FTP server foreground service notifications"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopFtpServerInternal()
        Log.d(TAG, "FTP Server Service destroyed")
    }

    private fun findAvailablePort(startPort: Int): Int {
        var port = startPort
        repeat(100) {
            try {
                ServerSocket().use { sock ->
                    sock.bind(InetSocketAddress(port))
                    return port
                }
            } catch (_: Exception) {
                port++
            }
        }
        return startPort + 100
    }

    private fun getLocalIpAddress(): String {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: return "127.0.0.1"
            return String.format("%d.%d.%d.%d",
                ip and 0xFF, ip shr 8 and 0xFF,
                ip shr 16 and 0xFF, ip shr 24 and 0xFF)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP", e)
            return "127.0.0.1"
        }
    }

    private fun getExternalStorageRootDirectory(): File? {
        val external = android.os.Environment.getExternalStorageDirectory()
        if (external.exists() && external.canRead() && external.canWrite()) {
            return external
        }
        Log.w(TAG, "External storage not fully accessible, using filesDir")
        return filesDir
    }
}
