
package com.mlbb.serverpicker

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MlbbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mlbb.serverpicker.START_FILTER"
        const val ACTION_STOP = "com.mlbb.serverpicker.STOP_FILTER"

        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_SERVER_IP = "server_ip"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetThread: Thread? = null

    private val running = AtomicBoolean(false)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFiltering()
                stopSelf()
            }

            ACTION_START -> {
                stopFiltering()

                val countryCode =
                    intent.getStringExtra(EXTRA_COUNTRY_CODE)

                val serverIp =
                    intent.getStringExtra(EXTRA_SERVER_IP)

                if (countryCode.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startFiltering(countryCode, serverIp)
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun startFiltering(
        countryCode: String,
        selectedServerIp: String?
    ) {
        try {
            val databaseText = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(databaseText)
            val countries = json.getJSONArray("countries")

            val allIps = linkedSetOf<String>()
            val allowedIps = linkedSetOf<String>()
            var countryFound = false

            for (i in 0 until countries.length()) {
                val country = countries.getJSONObject(i)
                val code = country.getString("code")
                val servers = country.getJSONArray("servers")

                if (code == countryCode) {
                    countryFound = true
                }

                for (j in 0 until servers.length()) {
                    val entry = servers.getString(j)
                    val ip = entry.substringBeforeLast(":")

                    if (!isValidIpv4(ip)) {
                        continue
                    }

                    allIps.add(ip)

                    if (code == countryCode) {
                        if (selectedServerIp.isNullOrBlank() ||
                            ip == selectedServerIp
                        ) {
                            allowedIps.add(ip)
                        }
                    }
                }
            }

            if (!countryFound || allowedIps.isEmpty()) {
                stopSelf()
                return
            }

            // IPs in the database that are not part of the selection.
            val blockedIps = allIps - allowedIps

            val builder = Builder()
                .setSession("MLBB Server Picker")
                .addAddress("10.10.0.2", 32)
                .setMtu(1500)

            // Only blocked IPs enter the VPN.
            // Do NOT add a default route such as 0.0.0.0/0.
            for (ip in blockedIps) {
                builder.addRoute(ip, 32)
            }

            val newInterface = builder.establish()

            if (newInterface == null) {
                stopSelf()
                return
            }

            vpnInterface = newInterface
            running.set(true)

            // Packets directed to blocked IPs are discarded.
            // No other traffic is forwarded through this VPN.
            packetThread = thread(
                start = true,
                name = "MLBB-IP-filter"
            ) {
                val buffer = ByteArray(32767)

                try {
                    FileInputStream(newInterface.fileDescriptor).use { input ->
                        while (running.get()) {
                            val count = input.read(buffer)

                            if (count < 0) {
                                break
                            }

                            // Intentionally discard these packets.
                        }
                    }
                } catch (_: Exception) {
                    // The descriptor can close when filtering stops.
                }
            }
        } catch (_: Exception) {
            stopFiltering()
            stopSelf()
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")

        if (parts.size != 4) {
            return false
        }

        return parts.all { part ->
            val number = part.toIntOrNull()
            number != null &&
                number in 0..255 &&
                part.isNotEmpty()
        }
    }

    private fun stopFiltering() {
        running.set(false)

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
            // Already closed.
        }

        vpnInterface = null
        packetThread = null
    }

    override fun onDestroy() {
        stopFiltering()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopFiltering()
        stopSelf()
        super.onRevoke()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
