package com.example.workspacefilemanager.network

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NetworkViewModel : ViewModel() {

    private lateinit var ctx: Context

    fun init(context: Context) {
        this.ctx = context
    }

    private val _ftpRunning = MutableStateFlow(false)
    private val _ftpAddress = MutableStateFlow<String?>(null)
    private val _ftpPort = MutableStateFlow(2221)
    private val _sftpEntries = MutableStateFlow<List<NetworkClientRepository.SftpEntry>>(emptyList())
    private val _sftpLoading = MutableStateFlow(false)
    private val _smbEntries = MutableStateFlow<List<SmbClient.SmbEntry>>(emptyList())
    private val _smbLoading = MutableStateFlow(false)
    private val _smbHost = MutableStateFlow("")
    private val _smbShare = MutableStateFlow("")
    private val _smbPath = MutableStateFlow("")
    private val _notificationMsg = MutableStateFlow<String?>(null)

    val ftpRunning:      StateFlow<Boolean>                         = _ftpRunning.asStateFlow()
    val ftpAddress:      StateFlow<String?>                         = _ftpAddress.asStateFlow()
    val ftpPort:         StateFlow<Int>                             = _ftpPort.asStateFlow()
    val sftpEntries:     StateFlow<List<NetworkClientRepository.SftpEntry>> = _sftpEntries.asStateFlow()
    val sftpLoading:     StateFlow<Boolean>                         = _sftpLoading.asStateFlow()
    val smbEntries:      StateFlow<List<SmbClient.SmbEntry>>        = _smbEntries.asStateFlow()
    val smbLoading:      StateFlow<Boolean>                         = _smbLoading.asStateFlow()
    val smbHost:         StateFlow<String>                          = _smbHost.asStateFlow()
    val smbShare:        StateFlow<String>                          = _smbShare.asStateFlow()
    val smbPath:         StateFlow<String>                          = _smbPath.asStateFlow()
    val notificationMsg: StateFlow<String?>                         = _notificationMsg.asStateFlow()

    private val sftpRepo by lazy { NetworkClientRepository(ctx) }
    private val smbClient by lazy { SmbClient(ctx) }

    fun startFtp() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = getLocalIp(ctx)
            val port = _ftpPort.value
            _ftpAddress.value = "ftp://$ip:$port"
            _ftpRunning.value = true
            _notificationMsg.value = "FTP started at $_ftpAddress"
            val intent = Intent(ctx, FtpServerService::class.java).apply {
                action = FtpServerService.ACTION_START_SERVER
            }
            ctx.startForegroundService(intent)
        }
    }

    fun stopFtp() {
        viewModelScope.launch(Dispatchers.IO) {
            _ftpRunning.value = false
            _ftpAddress.value = null
            val intent = Intent(ctx, FtpServerService::class.java).apply {
                action = FtpServerService.ACTION_STOP_SERVER
            }
            ctx.startService(intent)
        }
    }

    fun setFtpPort(port: Int) {
        _ftpPort.value = port
    }

    fun connectSftp(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = sftpRepo.connectSftp(host, port, user, pass)
            if (ok) {
                _notificationMsg.value = "Connected to SFTP: $host:$port"
            } else {
                _notificationMsg.value = "SFTP connection failed"
            }
        }
    }

    fun listSftpDir(path: String = "/") {
        viewModelScope.launch(Dispatchers.IO) {
            _sftpLoading.value = true
            _sftpEntries.value = sftpRepo.listRemoteDir(path)
            _sftpLoading.value = false
        }
    }

    fun disconnectSftp() {
        viewModelScope.launch { sftpRepo.disconnectSftp() }
    }

    fun connectSmb(host: String, share: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _smbHost.value = host
            _smbShare.value = share
            _smbPath.value = ""
            _smbLoading.value = true
            val entries = smbClient.browseShare(host, share, user, pass)
            _smbEntries.value = entries
            _smbLoading.value = false
            if (entries.isNotEmpty()) {
                _notificationMsg.value = "Connected to SMB: //$host/$share (${entries.size} items)"
            } else {
                _notificationMsg.value = "SMB share is empty or connection failed"
            }
        }
    }

    fun browseSmbPath(subPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val full = if (subPath.isEmpty()) "" else "/$subPath"
            _smbLoading.value = true
            val entries = smbClient.browsePath(_smbHost.value, _smbShare.value, full, "", "")
            _smbEntries.value = entries
            _smbPath.value = subPath
            _smbLoading.value = false
        }
    }

    fun smbNavigateUp() {
        val path = _smbPath.value
        val parent = if (path.contains("/")) path.substringBeforeLast("/") else ""
        browseSmbPath(parent)
    }

    fun clearNotification() { _notificationMsg.value = null }

    @Suppress("DEPRECATION")
    private fun getLocalIp(ctx: Context): String {
        val wm  = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip  = wm.connectionInfo.ipAddress
        return Formatter.formatIpAddress(ip)
    }
}
