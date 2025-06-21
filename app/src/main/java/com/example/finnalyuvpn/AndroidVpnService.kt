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
import java.io.IOException

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
        private const val DEFAULT_MTU = 1500
        private const val CONNECT_TIMEOUT = 15000
        private const val SOCKET_TIMEOUT = 30000
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (!isRunning.get()) {
            intent?.data?.let { configUri ->
                Thread {
                    try {
                        val configText = readConfigFile(configUri)
                        Log.d(TAG, "Config file loaded (${configText.length} chars)")

                        val config = parseOpenVpnConfig(configText).apply {
                            validateConfig()
                            logConfig()
                        }

                        startVPN(config)
                        isRunning.set(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "VPN startup failed", e)
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
        val verbosity: Int,
        val caCert: String,
        val clientCert: String,
        val clientKey: String,
        val tlsCrypt: String?
    ) {
        fun validateConfig() {
            require(remoteAddress.isNotEmpty()) { "Remote address is empty" }
            require(caCert.isNotEmpty()) { "CA certificate is missing" }
            require(clientCert.isNotEmpty()) { "Client certificate is missing" }
            require(clientKey.isNotEmpty()) { "Client key is missing" }
        }

        fun logConfig() {
            Log.d(TAG, """
                |Config:
                |Remote: $remoteAddress:$remotePort
                |Protocol: $protocol
                |TLS-Crypt: ${if (tlsCrypt.isNullOrEmpty()) "disabled" else "enabled"}
                |Ciphers: ${dataCiphers.joinToString()}
                |CA Cert: ${caCert.length} chars
                |Client Cert: ${clientCert.length} chars
                |Client Key: ${clientKey.length} chars
            """.trimMargin())
        }
    }

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
                        configMap[parts[0]] = parts[1].trim()
                    }
                }
            }
        }

        val remoteParts = configMap["remote"]?.split(" ") ?: listOf()
        val keepaliveParts = configMap["keepalive"]?.split(" ") ?: listOf()
        val dataCiphers = configMap["data-ciphers"]?.split(":") ?: listOf("AES-256-GCM")

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
            vpnInterface = createVpnInterface(config)
            Log.d(TAG, "VPN interface created successfully")
            connectToOpenVPNServer(config)
        } catch (e: Exception) {
            Log.e(TAG, "VPN setup failed", e)
            stopSelf()
        }
    }

    private fun createVpnInterface(config: OpenVpnConfig): ParcelFileDescriptor {
        return Builder().apply {
            setSession("VPN-${config.remoteAddress}")
            setMtu(config.mssfix ?: DEFAULT_MTU)

            // Временный адрес из стандартного пула OpenVPN
            addAddress("10.8.0.2", 24)

            // Маршрутизация всего трафика через VPN
            addRoute("0.0.0.0", 0)
            addRoute("::", 0)

            // DNS серверы
            addDnsServer("8.8.8.8")
            addDnsServer("8.8.4.4")

            setConfigureIntent(
                PendingIntent.getActivity(
                    this@AndroidVpnService,
                    0,
                    Intent(this@AndroidVpnService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }.establish() ?: throw IllegalStateException("Failed to establish VPN interface")
    }

    private fun connectToOpenVPNServer(config: OpenVpnConfig) {
        Thread {
            try {
                Log.d(TAG, "Connecting to ${config.remoteAddress}:${config.remotePort}...")

                socket = Socket().apply {
                    connect(InetSocketAddress(config.remoteAddress, config.remotePort), CONNECT_TIMEOUT)
                    soTimeout = SOCKET_TIMEOUT
                    keepAlive = true
                }.also {
                    Log.d(TAG, "Socket connected to ${it.inetAddress}:${it.port}")
                }

                val vpnIn = FileInputStream(vpnInterface!!.fileDescriptor)
                val vpnOut = FileOutputStream(vpnInterface!!.fileDescriptor)
                val serverIn = socket!!.getInputStream()
                val serverOut = socket!!.getOutputStream()

                sendEnhancedHandshake(serverOut, config)
                Log.d(TAG, "Handshake completed")

                val buffer = ByteArray(MAX_PACKET_SIZE)
                while (isRunning.get()) {
                    try {
                        val vpnBytes = vpnIn.read(buffer)
                        if (vpnBytes > 0) {
                            serverOut.write(buffer, 0, vpnBytes)
                            Log.v(TAG, "Sent $vpnBytes bytes to server")
                        }

                        val serverBytes = serverIn.read(buffer)
                        if (serverBytes > 0) {
                            vpnOut.write(buffer, 0, serverBytes)
                            Log.v(TAG, "Received $serverBytes bytes from server")
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Data transfer error", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN connection failed", e)
                stopSelf()
            } finally {
                socket?.close()
                Log.d(TAG, "Connection closed")
            }
        }.start()
    }

    private fun sendEnhancedHandshake(outputStream: java.io.OutputStream, config: OpenVpnConfig) {
        val handshake = """
            OpenVPN CONNECT
            VERSION: 3.0
            PROTOCOL: ${config.protocol.uppercase()}
            REMOTE: ${config.remoteAddress}:${config.remotePort}
            DEVTYPE: ${config.devType}
            CIPHERS: ${config.dataCiphers.joinToString(":")}
            ${if (config.tlsCrypt != null) "TLS_CRYPT: present" else ""}
            PUSH_REQUEST: true
        """.trimIndent()

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