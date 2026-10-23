
package com.mlbb.serverpicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MlbbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mlbb.serverpicker.START_VPN"
        const val ACTION_STOP = "com.mlbb.serverpicker.STOP_VPN"
        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_SERVER_ENTRY = "server_entry"
        const val EXTRA_FILTER_MODE = "filter_mode"

        private const val CHANNEL_ID = "mlbb_filter_channel"
        private const val NOTIFICATION_ID = 701
        private const val MLBB_PACKAGE = "com.mobile.legends"
        private const val FILTER_ALLOW = "allow"
        private const val FILTER_BLOCK = "block"
    }

    private val running = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    stopFiltering()
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }

                ACTION_START -> {
                    stopFiltering()

                    val countryCode =
                        intent.getStringExtra(EXTRA_COUNTRY_CODE).orEmpty()
                    val selectedEntry =
                        intent.getStringExtra(EXTRA_SERVER_ENTRY).orEmpty()
                    val mode =
                        intent.getStringExtra(EXTRA_FILTER_MODE) ?: FILTER_ALLOW

                    if (countryCode.isBlank() || selectedEntry.isBlank()) {
                        createNotificationChannel()
                        startForegroundCompat(createNotification(
                            "Не выбрана страна или сервер"
                        ))
                        stopSelf()
                        return START_NOT_STICKY
                    }

                    createNotificationChannel()

                    startForegroundCompat(
                        createNotification("Запуск VPN...")
                    )

                    startFiltering(countryCode, selectedEntry, mode)
                }
            }
        } catch (e: Exception) {
            try {
                createNotificationChannel()
                startForegroundCompat(
                    createNotification(
                        "Ошибка запуска: ${e.message ?: e.javaClass.simpleName}"
                    )
                )
            } catch (_: Exception) {
            }

            stopFiltering()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startFiltering(
        countryCode: String,
        selectedEntry: String,
        mode: String
    ) {
        thread(name = "MLBB-SQLite-VPN") {
            val database = ServerDatabase(applicationContext)

            try {
                updateNotification("Читаем SQLite...")

                val allEntries =
                    database.getAllEntries(includeDisabled = false)

                val countryEntries = allEntries.filter {
                    it.countryCode.equals(countryCode, ignoreCase = true)
                }

                if (countryEntries.isEmpty()) {
                    updateNotification(
                        "В SQLite нет записей выбранной страны"
                    )
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                val allIps = allEntries
                    .filter {
                        it.type == "IPV4" ||
                        it.type == "IPV4_CIDR" ||
                        it.type == "DOMAIN" ||
                        it.type == "HOST"
                    }
                    .flatMap { resolveEntry(it.address, it.type) }
                    .toSet()

                val selectedIps =
                    if (selectedEntry.equals(countryCode, ignoreCase = true)) {
                        countryEntries
                            .filter {
                                it.type == "IPV4" ||
                                it.type == "IPV4_CIDR" ||
                                it.type == "DOMAIN" ||
                                it.type == "HOST"
                            }
                            .flatMap { resolveEntry(it.address, it.type) }
                            .toSet()
                    } else {
                        countryEntries
                            .filter { it.address == selectedEntry }
                            .flatMap { resolveEntry(it.address, it.type) }
                            .toSet()
                    }

                if (selectedIps.isEmpty()) {
                    updateNotification(
                        "Не удалось получить IPv4 выбранной записи"
                    )
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                val routedIps =
                    if (mode == FILTER_BLOCK) selectedIps
                    else allIps - selectedIps

                try {
                    packageManager.getApplicationInfo(
                        MLBB_PACKAGE,
                        PackageManager.GET_META_DATA
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    updateNotification("Mobile Legends не установлена")
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                updateNotification("Создаём VPN-интерфейс...")

                val builder = Builder()
                    .setSession("MLBB Server Picker")
                    .setMtu(1500)
                    .addAddress("10.10.0.2", 32)

                builder.addAllowedApplication(MLBB_PACKAGE)

                var routeCount = 0

                for (ip in routedIps) {
                    try {
                        builder.addRoute(ip, 32)
                        routeCount++
                    } catch (_: Exception) {
                    }
                }

                updateNotification("Создаём VPN: $routeCount маршрутов")

                val established = builder.establish()

                if (established == null) {
                    updateNotification(
                        "Android не разрешил создать VPN"
                    )
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                vpnInterface = established
                running.set(true)

                updateNotification("VPN запущен: $routeCount маршрутов")

                /*
                 * Диагностический VPN: пакеты читаются из TUN,
                 * но не пересылаются в интернет.
                 */
                val input = FileInputStream(established.fileDescriptor)
                val buffer = ByteArray(32767)

                try {
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        updateNotification(
                            "Ошибка чтения VPN: ${e.message ?: "неизвестная ошибка"}"
                        )
                    }
                } finally {
                    try {
                        input.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                updateNotification(
                    "Ошибка VPN: ${e.message ?: e.javaClass.simpleName}"
                )
                stopFiltering()
                stopSelf()
            } finally {
                database.close()
            }
        }
    }

    private fun resolveEntry(
        address: String,
        type: String
    ): Set<String> {
        return try {
            when (type) {
                "IPV4" -> {
                    if (isIpv4(address)) setOf(address) else emptySet()
                }

                "IPV4_CIDR" -> expandIpv4Cidr(address)

                "DOMAIN", "HOST" -> {
                    InetAddress.getAllByName(address)
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .filter { isIpv4(it) }
                        .toSet()
                }

                else -> emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun expandIpv4Cidr(value: String): Set<String> {
        val parts = value.split("/")

        if (parts.size != 2 || !isIpv4(parts[0])) {
            return emptySet()
        }

        val prefix = parts[1].toIntOrNull() ?: return emptySet()
        if (prefix !in 0..32) return emptySet()

        val octets = parts[0].split(".").map {
            it.toLongOrNull() ?: return emptySet()
        }

        if (octets.size != 4) return emptySet()

        val ip = (octets[0] shl 24) or
            (octets[1] shl 16) or
            (octets[2] shl 8) or
            octets[3]

        val mask = if (prefix == 0) {
            0L
        } else {
            (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        }

        val network = ip and mask
        val count = 1L shl (32 - prefix)

        // Не создаём чрезмерно большое количество IP и маршрутов.
        if (count > 65536L) return emptySet()

        return (0 until count).map { offset ->
            val number = (network + offset) and 0xFFFFFFFFL
            "${(number shr 24) and 255}." +
                "${(number shr 16) and 255}." +
                "${(number shr 8) and 255}." +
                "${number and 255}"
        }.toSet()
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val number = part.toIntOrNull()
            number != null &&
                number in 0..255 &&
                part.isNotEmpty() &&
                part.all(Char::isDigit)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MLBB Server Picker",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(message: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)

        val immutableFlag =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MLBB Server Picker")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MLBB Server Picker")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification(message))
        } catch (_: Exception) {
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopFiltering() {
        running.set(false)

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }

        vpnInterface = null
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
