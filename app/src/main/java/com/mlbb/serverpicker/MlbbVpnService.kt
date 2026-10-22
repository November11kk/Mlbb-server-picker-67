
package com.mlbb.serverpicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class MlbbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mlbb.serverpicker.START_FILTER"
        const val ACTION_STOP = "com.mlbb.serverpicker.STOP_FILTER"

        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_SERVER_IP = "server_ip"

        private const val CHANNEL_ID = "mlbb_filter_channel"
        private const val NOTIFICATION_ID = 701
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
                stopForegroundCompat()
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

                createNotificationChannel()
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Подготовка фильтра")
                )

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
            val text = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val countries =
                JSONObject(text).getJSONArray("countries")

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
                    val ip = servers.getString(j)
                        .substringBeforeLast(":")

                    if (!isValidIpv4(ip)) continue

                    allIps.add(ip)

                    if (code == countryCode &&
                        (selectedServerIp.isNullOrBlank() ||
                            selectedServerIp == ip)
                    ) {
                        allowedIps.add(ip)
                    }
                }
            }

            if (!countryFound || allowedIps.isEmpty()) {
                updateNotification("Ошибка: категория или IP не найдены")
                stopFiltering()
                stopForegroundCompat()
                stopSelf()
                return
            }

            val blockedIps = allIps - allowedIps

            val builder = Builder()
                .setSession("MLBB Server Picker")
                .addAddress("10.10.0.2", 32)
                .setMtu(1500)

            for (ip in blockedIps) {
                builder.addRoute(ip, 32)
            }

            val newInterface = builder.establish()

            if (newInterface == null) {
                updateNotification("VPN не разрешён Android")
                stopFiltering()
                stopForegroundCompat()
                stopSelf()
                return
            }

            vpnInterface = newInterface
            running.set(true)

            updateNotification(
                "Фильтр включён: $countryCode, " +
                    "${blockedIps.size} IP заблокировано по маршрутам"
            )

            packetThread = thread(
                start = true,
                name = "MLBB-IP-filter"
            ) {
                val buffer = ByteArray(32767)

                try {
                    FileInputStream(newInterface.fileDescriptor).use { input ->
                        while (running.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break

                            // Packets reaching this interface are dropped.
                        }
                    }
                } catch (_: Exception) {
                    // Expected when the VPN interface is closed.
                }
            }
        } catch (e: Exception) {
            stopFiltering()
            updateNotification("Ошибка фильтра: ${e.javaClass.simpleName}")
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MLBB IP-фильтр",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MLBB Server Picker")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(running.get())
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MLBB Server Picker")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(running.get())
                .build()
        }
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(message))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all {
            val number = it.toIntOrNull()
            number != null && number in 0..255 && it.isNotEmpty()
        }
    }

    override fun onRevoke() {
        stopFiltering()
        stopForegroundCompat()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFiltering()
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
