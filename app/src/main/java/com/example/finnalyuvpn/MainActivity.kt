package com.example.finnalyuvpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var connectButton: Button
    private lateinit var selectConfigButton: Button
    private lateinit var statusText: TextView
    private lateinit var configNameText: TextView
    private var configUri: Uri? = null

    companion object {
        private const val VPN_REQUEST_CODE = 1
        private const val FILE_PICK_CODE = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectButton = findViewById(R.id.connectButton)
        selectConfigButton = findViewById(R.id.selectConfigButton)
        statusText = findViewById(R.id.statusText)
        configNameText = findViewById(R.id.configNameText)

        setupUI()
    }

    private fun setupUI() {
        connectButton.isEnabled = false
        configNameText.isVisible = false

        selectConfigButton.setOnClickListener {
            selectConfigFile()
        }

        connectButton.setOnClickListener {
            configUri?.let { uri ->
                prepareVpn()
            } ?: run {
                Toast.makeText(this, "Please select a config file first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectConfigFile() {
        val mimeTypes = arrayOf(
            "application/x-openvpn-profile",
            "text/plain",
            "application/octet-stream"
        )

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_TITLE, "Select OVPN Config")
        }

        try {
            startActivityForResult(intent, FILE_PICK_CODE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
            Log.e("FilePicker", "No activity found to handle file pick", e)
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        configUri?.let { uri ->
            try {
                contentResolver.openFileDescriptor(uri, "r")?.close()

                val serviceIntent = Intent(this, AndroidVpnService::class.java).apply {
                    data = uri
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                statusText.text = "Status: Connecting..."
            } catch (e: IOException) {
                Toast.makeText(this, "Error accessing config file", Toast.LENGTH_SHORT).show()
                Log.e("VPNService", "Config file error", e)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            FILE_PICK_CODE -> handleFilePickResult(resultCode, data)
            VPN_REQUEST_CODE -> handleVpnPermissionResult(resultCode)
        }
    }

    private fun handleFilePickResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    configUri = uri
                    updateUIAfterConfigSelected(uri)
                    logConfigContent(uri)
                } catch (e: SecurityException) {
                    Toast.makeText(this, "Failed to get file permissions", Toast.LENGTH_SHORT).show()
                    Log.e("FilePermission", "Failed to take persistable URI permission", e)
                }
            }
        }
    }

    private fun updateUIAfterConfigSelected(uri: Uri) {
        val fileName = uri.lastPathSegment ?: "config.ovpn"
        configNameText.text = "Selected: ${fileName.substringAfterLast('/')}"
        configNameText.isVisible = true
        connectButton.isEnabled = true
        statusText.text = "Status: Ready to connect"
    }

    private fun logConfigContent(uri: Uri) {
        try {
            val content = readConfigFile(uri)
            Log.d("VPNConfig", "Config content (first 100 chars): ${content.take(100)}...")
        } catch (e: Exception) {
            Log.e("VPNConfig", "Error reading config", e)
        }
    }

    private fun readConfigFile(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.readText()
            }
        } ?: throw IOException("Cannot open config file")
    }

    private fun handleVpnPermissionResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            statusText.text = "Status: VPN permission denied"
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }
}