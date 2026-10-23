
package com.mlbb.serverpicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.netvalve.bridge.Bridge
import dev.netvalve.bridge.Handler
import dev.netvalve.bridge.TCPConn
import dev.netvalve.bridge.Tunnel
import dev.netvalve.bridge.UDPConn
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class MlbbVpnService : VpnService() {

    companion object {
        const val ACTION_START =
            "com.mlbb.serverpicker.START_FILTER"

        const val ACTION_STOP =
            "com.mlbb.serverpicker.STOP_FILTER"

        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_FILTER_MODE = "filter_mode"

        const val FILTER_ALLOW = "allow"
        const val FILTER_BLOCK = "block"

        private const val CHANNEL_ID = "mlbb_filter_channel"
        private const val NOTIFICATION_ID = 701
        private const val TAG = "MlbbVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var netstackTunnel: Tunnel? = null

    private val running = AtomicBoolean(false)

    @Volatile
    private var stopping = false

    @Synchronized
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
                stopping = false

                val countryCode =
                    intent.getStringExtra(EXTRA_COUNTRY_CODE)

                val selectedEntry =
                    intent.getStringExtra(EXTRA_SERVER_IP)

                val filterMode =
                    intent.getStringExtra(EXTRA_FILTER_MODE)
                        ?: FILTER_ALLOW

                if (countryCode.isNullOrBlank()) {
                    Log.e(TAG, "Не передан код страны")
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }

                createNotificationChannel()

                try {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("Подготовка VPN-фильтра")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Не удалось запустить foreground-службу", e)
                    stopFiltering()
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }

                startFiltering(
                    countryCode,
                    selectedEntry,
                    filterMode
                )
            }

            else -> {
                stopFiltering()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun startFiltering(
        countryCode: String,
        selectedEntry: String?,
        filterMode: String
    ) {
        try {
            val jsonText = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val countries = JSONObject(jsonText)
                .getJSONArray("countries")

            val allIps = linkedSetOf<String>()
            val countryIps = linkedSetOf<String>()
            val selectedServerIps = linkedSetOf<String>()

            var countryFound = false

            for (i in 0 until countries.length()) {
                val country = countries.getJSONObject(i)
                val code = country.optString("code")
                val servers = country.optJSONArray("servers")
                    ?: continue

                if (code == countryCode) {
                    countryFound = true
                }

                for (j in 0 until servers.length()) {
                    val entry = normalizeEntry(
                        servers.optString(j)
                    ) ?: continue

                    val ips = expandEntry(entry)
                    if (ips.isEmpty()) continue

                    allIps.addAll(ips)

                    if (code == countryCode) {
                        countryIps.addAll(ips)

                        if (
                            selectedEntry.isNullOrBlank() ||
                            selectedEntry == countryCode ||
                            selectedEntry == entry
                        ) {
                            selectedServerIps.addAll(ips)
                        }
                    }
                }
            }

            if (!countryFound) {
                failStart("Страна с кодом $countryCode не найдена")
                return
            }

            val isWholeCountry =
                selectedEntry.isNullOrBlank() ||
                    selectedEntry == countryCode

            val selectedIps =
                if (isWholeCountry) countryIps
                else selectedServerIps

            if (selectedIps.isEmpty()) {
                failStart(
                    "Не найдены IP выбранной страны или сервера. " +
                        "Проверь server_data.json"
                )
                return
            }

            if (
                filterMode != FILTER_ALLOW &&
                filterMode != FILTER_BLOCK
            ) {
                failStart("Неизвестный режим фильтра: $filterMode")
                return
            }

            val blockedIps: Set<String> =
                if (filterMode == FILTER_BLOCK) {
                    selectedIps.toSet()
                } else {
                    (allIps - selectedIps).toSet()
                }

            if (blockedIps.isEmpty()) {
                failStart(
                    "Список IP для блокировки пуст. " +
                        "Проверь данные серверов."
                )
                return
            }

            val builder = Builder()
                .setSession("MLBB Server Picker")
                .addAddress("10.10.0.2", 32)
                .setMtu(1500)

            for (ip in blockedIps) {
                builder.addRoute(ip, 32)
            }

            val newInterface = builder.establish()

            if (newInterface == null) {
                failStart(
                    "Android не создал VPN-интерфейс. " +
                        "Проверь разрешение VPN."
                )
                return
            }

            vpnInterface = newInterface

            val tunFd = newInterface.detachFd()
            vpnInterface = null

            try {
                val tunnel = Bridge.newTunnel(
                    tunFd.toLong(),
                    1500L,
                    "FAST_REJECT",
                    NetstackHandler()
                )

                netstackTunnel = tunnel
                running.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка Bridge.newTunnel", e)

                runCatching {
                    ParcelFileDescriptor.adoptFd(tunFd).close()
                }

                failStart(
                    "Ошибка сетевого движка: " +
                        "${e.javaClass.simpleName}: " +
                        (e.message ?: "без подробностей")
                )
                return
            }

            val modeText =
                if (filterMode == FILTER_BLOCK) {
                    "блокировка"
                } else {
                    "разрешение выбранного"
                }

            val selectionText =
                if (isWholeCountry) "вся страна" else "сервер"

            updateNotification(
                "VPN активен: $selectionText; $modeText; " +
                    "IP в блокировке: ${blockedIps.size}"
            )

            Log.i(
                TAG,
                "VPN запущен: country=$countryCode, " +
                    "mode=$filterMode, blocked=${blockedIps.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки VPN-фильтра", e)
            failStart(
                "Ошибка фильтра: ${e.javaClass.simpleName}: " +
                    (e.message ?: "без подробностей")
            )
        }
    }

    private inner class NetstackHandler : Handler {

        override fun handleTCP(
            srcIp: String,
            srcPort: Long,
            dstIp: String,
            dstPort: Long,
            conn: TCPConn
        ) {
            Log.d(
                TAG,
                "TCP BLOCK $srcIp:$srcPort -> $dstIp:$dstPort"
            )
            runCatching { conn.close() }
        }

        override fun handleUDP(
            srcIp: String,
            srcPort: Long,
            dstIp: String,
            dstPort: Long,
            conn: UDPConn
        ) {
            Log.d(
                TAG,
                "UDP BLOCK $srcIp:$srcPort -> $dstIp:$dstPort"
            )
            runCatching { conn.close() }
        }

        override fun log(level: Long, msg: String) {
            Log.d(TAG, "Netstack[$level]: $msg")
        }
    }

    private fun failStart(message: String) {
        Log.e(TAG, message)

        runCatching {
            updateNotification(message)
        }

        stopFiltering()
        stopForegroundCompat()
        stopSelf()
    }

    /**
     * Останавливает VPN.
     *
     * Не используем ранний return по флагу stopping:
     * очистка ресурсов должна выполняться при каждом вызове.
     */
    @Synchronized
    private fun stopFiltering() {
        stopping = true
        running.set(false)

        val tunnel = netstackTunnel
        netstackTunnel = null

        if (tunnel != null) {
            try {
                tunnel.stop()
                Log.i(TAG, "Netstack tunnel остановлен")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка остановки Netstack", e)
            }
        }

        val pfd = vpnInterface
        vpnInterface = null

        if (pfd != null) {
            try {
                pfd.close()
                Log.i(TAG, "VPN-интерфейс закрыт")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка закрытия VPN-интерфейса", e)
            }
        }

        Log.i(TAG, "Очистка VPN завершена")
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

    private fun normalizeEntry(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        if (value.contains("/")) {
            return value.takeIf { isValidCidr(it) }
        }

        val ip = if (value.count { it == ':' } == 1) {
            val candidate = value.substringBeforeLast(":")
            val port = value.substringAfterLast(":").toIntOrNull()

            if (
                port != null &&
                port in 0..65535 &&
                isValidIpv4(candidate)
            ) {
                candidate
            } else {
                value
            }
        } else {
            value
        }

        return ip.takeIf { isValidIpv4(it) }
    }

    private fun expandEntry(entry: String): Set<String> {
        if (!entry.contains("/")) {
            return if (isValidIpv4(entry)) setOf(entry)
            else emptySet()
        }

        val parts = entry.split("/")
        if (parts.size != 2 || !isValidIpv4(parts[0])) {
            return emptySet()
        }

        val prefix = parts[1].toIntOrNull() ?: return emptySet()
        if (prefix !in 20..32) return emptySet()

        val ipNumber = ipv4ToLong(parts[0])
        val hostBits = 32 - prefix
        val blockSize = 1L shl hostBits
        val network = (ipNumber / blockSize) * blockSize

        val result = LinkedHashSet<String>()

        for (offset in 0 until blockSize) {
            result.add(longToIpv4(network + offset))
        }

        return result
    }

    private fun isValidCidr(value: String): Boolean {
        val parts = value.split("/")
        if (parts.size != 2 || !isValidIpv4(parts[0])) {
            return false
        }

        val prefix = parts[1].toIntOrNull() ?: return false
        return prefix in 20..32
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() &&
                number != null &&
                number in 0..255
        }
    }

    private fun ipv4ToLong(ip: String): Long {
        return ip.split(".").fold(0L) { result, part ->
            (result shl 8) or part.toLong()
        }
    }

    private fun longToIpv4(value: Long): String {
        return "${(value shr 24) and 255}." +
            "${(value shr 16) and 255}." +
            "${(value shr 8) and 255}." +
            "${value and 255}"
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

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            flags
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
