package com.mlbb.serverpicker

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 26f
        }

        statusText = TextView(this).apply {
            text = "VPN выключен"
            textSize = 18f
            setPadding(0, 32, 0, 32)
        }

        startButton = Button(this).apply {
            text = "Запустить VPN"
            setOnClickListener {
                requestVpnPermission()
            }
        }

        stopButton = Button(this).apply {
            text = "Остановить VPN"
            setOnClickListener {
                stopVpn()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(startButton)
        root.addView(stopButton)

        setContentView(root)
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)

        if (intent != null) {
            startActivityForResult(intent, VPN_PERMISSION_REQUEST)
        } else {
            startVpn()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_PERMISSION_REQUEST && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, MlbbVpnService::class.java)
        startService(intent)

        statusText.text = "VPN запущен"
    }

    private fun stopVpn() {
        val intent = Intent(this, MlbbVpnService::class.java)
        stopService(intent)

        statusText.text = "VPN выключен"
    }

    companion object {
        private const val VPN_PERMISSION_REQUEST = 1001
    }
}
