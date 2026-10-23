
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
    private var packetThread: Thread? = null

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
                    stopSelf()
                    return START_NOT_STICKY
                }

                createNotificationChannel()
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Загрузка правил из SQLite")
                )

                startFiltering(countryCode, selectedEntry, mode)
            }
        }

        return START_STICKY
    }

    private fun startFiltering(
        countryCode: String,
        selectedEntry: String,
        mode: String
    ) {
        thread(name = "MLBB-SQLite-VPN") {
            val database = ServerDatabase(applicationContext)

            try {
                val allEntries = database.getAllEntries(includeDisabled = false)
                val countryEntries = allEntries.filter {
                    it.countryCode.equals(countryCode, ignoreCase = true)
                }

                if (countryEntries.isEmpty()) {
                    updateNotification("В SQLite нет активных записей страны")
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                val allIps = allEntries
                    .filter { it.type == "IPV4" || it.type == "IPV4_CIDR" ||
                        it.type == "DOMAIN" || it.type == "HOST" }
                    .flatMap { resolveEntry(it.address, it.type) }
                    .toSet()

                val selectedIps = if (selectedEntry == countryCode) {
                    countryEntries
                        .filter {
                            it.type == "IPV4" || it.type == "IPV4_CIDR" ||
                                it.type == "DOMAIN" || it.type == "HOST"
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
                    updateNotification("Выбранная запись не содержит IPv4")
                    stopFiltering()
                    stopSelf()
                    return@thread
                }

                // В режиме allow направляем в TUN адреса, не входящие в выбор.
                // В режиме block направляем в TUN выбранные адреса.
                val routedIps = if (mode == FILTER_BLOCK) {
                    selectedIps
                } else {
                    allIps - selectedIps
                }

                val builder = Builder()
                    .setSession("MLBB Server Picker")
                    .addAddress("10.10.0.2", 32)
                    .setMtu(1500)

                try {
                    packageManager.getApplicationInfo(
                        MLBB_PACKAGE,
                        PackageManager.GET_META_DATA
                    )
                    builder.addAllowedApplication(MLBB_PACKAGE)
                } catch (_: PackageManager.NameNotFoundException) {
                    updateNotification("Mobile Legends не установлена")
                    stopSelf()
                    return@thread
                }

                routedIps.forEach { ip ->
                    try {
                        builder.addRoute(ip, 32)
                    } catch (_: Exception) {
                        // Пропускаем некорректную запись, чтобы не сорвать запуск.
                    }
                }

                val established = builder.establish()
                if (established == null) {
                    updateNotification("Не удалось создать VPN")
                    stopSelf()
                    return@thread
                }

                vpnInterface = established
                running.set(true)

                updateNotification(
                    "SQLite: ${countryEntries.size} записей; маршрутов: ${routedIps.size}"
                )

                /*
                 * ВАЖНО:
                 * Этот упрощённый сервис НЕ пересылает пакеты из TUN в сеть.
                 * Любые пакеты, попавшие сюда по установленным маршрутам,
                 * будут отброшены. Это не полноценный VPN-туннель и не
                 * гарантирует переключение игрового региона.
                 */
                val input = FileInputStream(established.fileDescriptor)
                val buffer = ByteArray(32767)

                try {
                    while (running.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        // Пакеты намеренно не пересылаются этим упрощённым фильтром.
                    }
                } catch (_: Exception) {
                    // Закрытие интерфейса при остановке VPN может вызвать IOException.
                } finally {
                    try {
                        input.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                updateNotification("Ошибка фильтра: ${e.message ?: "неизвестная"}")
                stopFiltering()
                stopSelf()
            } finally {
                database.close()
            }
        }
    }

    private fun resolveEntry(address: String, type: String): Set<String> {
        return try {
            when (type) {
                "IPV4" -> {
                    if (isIpv4(address)) setOf(address) else emptySet()
                }

                "IPV4_CIDR" -> expandIpv4Cidr(address)

                "DOMAIN", "HOST" -> InetAddress.getAllByName(address)
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress ?: "" }
                    .filter { isIpv4(it) }
                    .toSet()

                else -> emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun expandIpv4Cidr(value: String): Set<String> {
        val parts = value.split("/")
        if (parts.size != 2 || !isIpv4(parts[0])) return emptySet()

        val prefix = parts[1].toIntOrNull() ?: return emptySet()
        if (prefix !in 0..32) return emptySet()

        val octets = parts[0].split(".").map { it.toLong() }
        val ip = (octets[0] shl 24) or
            (octets[1] shl 16) or
            (octets[2] shl 8) or
            octets[3]

        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val count = 1L shl (32 - prefix)

        // Не создаём огромные списки адресов из широких CIDR.
        if (count > 65536L) return emptySet()

        return (0 until count).map { offset ->
            val number = (network + offset) and 0xFFFFFFFFL
            "${(number shr 24) and 255}.${(number shr 16) and 255}." +
                "${(number shr 8) and 255}.${number and 255}"
        }.toSet()
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val number = part.toIntOrNull()
            number != null && number in 0..255 &&
                part.isNotEmpty() && part.all(Char::isDigit)
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(message))
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
        }

        vpnInterface = null
        packetThread = null
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
