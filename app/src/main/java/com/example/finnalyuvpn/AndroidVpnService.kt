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
import java.io.IOException
import java.io.InputStreamReader
import android.net.Uri

class AndroidVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "vpn_service_channel"
        private const val CHANNEL_NAME = "VPN Service"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            intent?.data?.let { configUri ->
                startForeground(NOTIFICATION_ID, createNotification())
                Thread {
                    try {
                        val config = readConfigFile(configUri)
                        startVPN(config)
                        isRunning = true
                    } catch (e: Exception) {
                        Log.e("VPNService", "Error starting VPN", e)
                        stopSelf()
                    }
                }.start()
            } ?: run {
                Log.e("VPNService", "No config URI provided")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun readConfigFile(uri: Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            } ?: throw IOException("Cannot read config file")
        } catch (e: Exception) {
            Log.e("VPNConfig", "Error reading config", e)
            throw e
        }
    }

    private fun startVPN(config: String) {
        // Здесь должен быть парсинг конфига и настройка VPN
        // Это упрощенный пример - в реальности нужно парсить конфиг

        try {
            val builder = Builder().apply {
                setSession("MyVPNService")
                addAddress("10.8.0.2", 24) // Эти параметры должны браться из конфига
                addDnsServer("8.8.8.8")
                addRoute("0.0.0.0", 0)
                setMtu(1500)
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
            Log.d("VPNService", "VPN connection established")

            // Здесь должна быть реализация работы с OpenVPN протоколом
            // Это сложная часть, требующая отдельной реализации

        } catch (e: Exception) {
            Log.e("VPNService", "Error establishing VPN", e)
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Service")
            .setContentText("VPN is running")
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "VPN Service Channel"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("VPNService", "Error closing VPN interface", e)
        }
        super.onDestroy()
    }
}