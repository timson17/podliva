package com.example.finnalyuvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import android.net.Uri

class AndroidVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private var socket: Socket? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_service_channel"
        private const val CHANNEL_NAME = "VPN Service"
        private const val TAG = "OpenVPN"
        private const val MAX_PACKET_SIZE = 32767
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification()

        // Упрощенный запуск foreground service без указания типа
        startForeground(NOTIFICATION_ID, notification)

        if (!isRunning.get()) {
            intent?.data?.let { configUri ->
                Thread {
                    try {
                        val configText = readConfigFile(configUri)
                        val config = parseOpenVpnConfig(configText)
                        startVPN(config)
                        isRunning.set(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting VPN", e)
                        stopSelf()
                    }
                }.start()
            } ?: run {
                Log.e(TAG, "No config URI provided")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private data class OpenVpnConfig(
        val isClient: Boolean,
        val devType: String,
        val protocol: String,
        val remoteAddress: String,
        val remotePort: Int,
        val resolvRetry: Boolean,
        val nobind: Boolean,
        val persistKey: Boolean,
        val persistTun: Boolean,
        val remoteCertTls: Boolean,
        val compress: String?,
        val mssfix: Int?,
        val dataCiphers: List<String>,
        val keepalive: Pair<Int, Int>,
        val verbosity: Int = 1,
        val caCert: String,
        val clientCert: String,
        val clientKey: String,
        val tlsCrypt: String?
    )

    private fun readConfigFile(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        } ?: throw Exception("Cannot read config file")
    }

    private fun parseOpenVpnConfig(configText: String): OpenVpnConfig {
        val lines = configText.split("\n")
        val configMap = mutableMapOf<String, String>()
        var currentSection: String? = null
        val sectionContent = mutableMapOf<String, StringBuilder>()

        for (line in lines) {
            val trimmedLine = line.trim()
            when {
                trimmedLine.startsWith("<") && trimmedLine.endsWith(">") -> {
                    currentSection = trimmedLine.removeSurrounding("<", ">")
                    sectionContent[currentSection] = StringBuilder()
                }
                currentSection != null -> {
                    sectionContent[currentSection]?.appendLine(trimmedLine)
                }
                trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#") -> {
                    val parts = trimmedLine.split(" ", limit = 2)
                    if (parts.size == 2) {
                        configMap[parts[0]] = parts[1]
                    }
                }
            }
        }

        val remoteParts = configMap["remote"]?.split(" ") ?: listOf()
        val keepaliveParts = configMap["keepalive"]?.split(" ") ?: listOf()
        val dataCiphers = configMap["data-ciphers"]?.split(":") ?: listOf()

        return OpenVpnConfig(
            isClient = configMap["client"] != null,
            devType = configMap["dev"] ?: "tun",
            protocol = configMap["proto"] ?: "udp",
            remoteAddress = remoteParts.getOrElse(0) { "" },
            remotePort = remoteParts.getOrElse(1) { "1194" }.toInt(),
            resolvRetry = configMap["resolv-retry"] == "infinite",
            nobind = configMap["nobind"] != null,
            persistKey = configMap["persist-key"] != null,
            persistTun = configMap["persist-tun"] != null,
            remoteCertTls = configMap["remote-cert-tls"] == "server",
            compress = configMap["compress"],
            mssfix = configMap["mssfix"]?.toIntOrNull(),
            dataCiphers = dataCiphers,
            keepalive = Pair(
                keepaliveParts.getOrElse(0) { "20" }.toInt(),
                keepaliveParts.getOrElse(1) { "180" }.toInt()
            ),
            verbosity = configMap["verb"]?.toIntOrNull() ?: 1,
            caCert = sectionContent["ca"]?.toString()?.trim() ?: "",
            clientCert = sectionContent["cert"]?.toString()?.trim() ?: "",
            clientKey = sectionContent["key"]?.toString()?.trim() ?: "",
            tlsCrypt = sectionContent["tls-crypt"]?.toString()?.trim()
        )
    }

    private fun startVPN(config: OpenVpnConfig) {
        try {
            val builder = Builder().apply {
                setSession("OpenVPN: ${config.remoteAddress}")
                addAddress("10.8.0.2", 24)
                addDnsServer("8.8.8.8")
                addRoute("0.0.0.0", 0)
                setMtu(config.mssfix ?: 1500)
                setConfigureIntent(
                    PendingIntent.getActivity(
                        this@AndroidVpnService,
                        0,
                        Intent(this@AndroidVpnService, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            vpnInterface = builder.establish()
            Log.d(TAG, "VPN interface established")
            connectToOpenVPNServer(config)

        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN", e)
            stopSelf()
        }
    }

    private fun connectToOpenVPNServer(config: OpenVpnConfig) {
        Thread {
            try {
                socket = Socket().apply {
                    connect(InetSocketAddress(config.remoteAddress, config.remotePort), 5000)
                    soTimeout = 30000
                }

                val localTunnel = vpnInterface ?: throw IllegalStateException("VPN interface not established")
                val inputStream = FileInputStream(localTunnel.fileDescriptor)
                val outputStream = FileOutputStream(localTunnel.fileDescriptor)

                val serverInputStream = socket?.getInputStream()
                val serverOutputStream = socket?.getOutputStream()

                if (serverInputStream == null || serverOutputStream == null) {
                    throw Exception("Failed to get socket streams")
                }

                sendOpenVpnHandshake(serverOutputStream, config)

                val buffer = ByteArray(MAX_PACKET_SIZE)
                while (isRunning.get()) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        serverOutputStream.write(buffer, 0, bytesRead)
                        serverOutputStream.flush()
                    }

                    val serverBytesRead = serverInputStream.read(buffer)
                    if (serverBytesRead > 0) {
                        outputStream.write(buffer, 0, serverBytesRead)
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in VPN connection", e)
                stopSelf()
            }
        }.start()
    }

    private fun sendOpenVpnHandshake(outputStream: java.io.OutputStream, config: OpenVpnConfig) {
        val handshake = buildString {
            appendln("OpenVPN CONNECT")
            appendln("VERSION: 2.5")
            appendln("PROTOCOL: ${config.protocol.uppercase()}")
            appendln("REMOTE: ${config.remoteAddress}:${config.remotePort}")
            appendln("DEVTYPE: ${config.devType}")
            if (config.tlsCrypt != null) {
                appendln("TLS_CRYPT: enabled")
            }
        }
        outputStream.write(handshake.toByteArray())
        outputStream.flush()
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Service")
            .setContentText("VPN connection active")
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN Service Channel"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning.set(false)
        try {
            socket?.close()
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing resources", e)
        }
        super.onDestroy()
    }
}