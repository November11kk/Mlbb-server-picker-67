
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
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class MlbbVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.mlbb.serverpicker.START_FILTER"
        const val ACTION_STOP = "com.mlbb.serverpicker.STOP_FILTER"

        const val EXTRA_COUNTRY_CODE = "country_code"
        const val EXTRA_SERVER_IP = "server_ip"
        const val EXTRA_FILTER_MODE = "filter_mode"

        const val FILTER_ALLOW = "allow"
        const val FILTER_BLOCK = "block"

        private const val BLOCKED_HOST = "ip.ml.youngjoygame.com"
        private const val CHANNEL_ID = "mlbb_filter_channel"
        private const val NOTIFICATION_ID = 701
        private const val TAG = "MlbbVpnService"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var netstackTunnel: Tunnel? = null

    private val running = AtomicBoolean(false)

    @Volatile
    private var stopping = false

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
                    stopSelf()
                    return START_NOT_STICKY
                }

                createNotificationChannel()

                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Подготовка фильтра")
                )

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

                    allIps.addAll(ips)

                    if (code == countryCode) {
                        countryIps.addAll(ips)

                        if (selectedEntry.isNullOrBlank() ||
                            selectedEntry == countryCode ||
                            selectedEntry == entry
                        ) {
                            selectedServerIps.addAll(ips)
                        }
                    }
                }
            }

            if (!countryFound) {
                failStart("Ошибка: страна не найдена")
                return
            }

            /*
             * selectedEntry == countryCode означает,
             * что пользователь выбрал карточку «Вся страна».
             */
            val isWholeCountry =
                selectedEntry.isNullOrBlank() ||
                selectedEntry == countryCode

            val selectedIps =
                if (isWholeCountry) countryIps
                else selectedServerIps

            if (selectedIps.isEmpty()) {
                failStart(
                    "Ошибка: IP выбранного сервера не найдены"
                )
                return
            }

            /*
             * Режим BLOCK:
             * блокируем выбранную страну или сервер.
             *
             * Режим ALLOW:
             * разрешаем выбранную страну или сервер,
             * а остальные известные IP блокируем.
             */
            val blockedIps = if (filterMode == FILTER_BLOCK) {
                selectedIps.toMutableSet()
            } else {
                (allIps - selectedIps).toMutableSet()
            }

            /*
             * Дополнительно блокируем известные IPv4-адреса
             * специального хоста, если DNS их вернул.
             */
            blockedIps.addAll(resolveIpv4Addresses(BLOCKED_HOST))

            if (blockedIps.isEmpty()) {
                failStart("Ошибка: список блокировки пуст")
                return
            }

            val blockedSet = blockedIps.toHashSet()

            val builder = Builder()
                .setSession("MLBB Server Picker")
                .addAddress("10.10.0.2", 32)
                .setMtu(1500)

            /*
             * Через VPN направляются только заблокированные IP.
             * Остальные адреса не перехватываются этим VPN.
             */
            for (ip in blockedIps) {
                builder.addRoute(ip, 32)
            }

            val newInterface = builder.establish()

            if (newInterface == null) {
                failStart("VPN не разрешён Android")
                return
            }

            vpnInterface = newInterface
            running.set(true)

            val tunFd = newInterface.detachFd()

            try {
                netstackTunnel = Bridge.newTunnel(
                    tunFd.toLong(),
                    1500L,
                    "FAST_REJECT",
                    NetstackHandler(blockedSet)
                )

                // После detachFd() владение дескриптором
                // передано Netstack.
                vpnInterface = null
            } catch (e: Exception) {
                running.set(false)
                Log.e(TAG, "Не удалось запустить Netstack", e)

                /*
                 * Если запуск Netstack завершился ошибкой,
                 * закрываем переданный дескриптор, насколько возможно.
                 */
                runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }

                failStart(
                    "Ошибка запуска Netstack: ${e.javaClass.simpleName}"
                )
                return
            }

            val modeText =
                if (filterMode == FILTER_BLOCK) "блокировка"
                else "разрешение"

            val selectionText =
                if (isWholeCountry) "вся страна"
                else "сервер"

            updateNotification(
                "VPN активен: $selectionText; " +
                    "режим: $modeText; " +
                    "IP в фильтре: ${blockedIps.size}"
            )

            Log.i(
                TAG,
                "Фильтр запущен: country=$countryCode, " +
                    "wholeCountry=$isWholeCountry, " +
                    "mode=$filterMode, blocked=${blockedIps.size}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка фильтра", e)
            failStart("Ошибка фильтра: ${e.javaClass.simpleName}")
        }
    }

    private inner class NetstackHandler(
        private val blockedIps: Set<String>
    ) : Handler {

        override fun handleTCP(
            srcIp: String,
            srcPort: Long,
            dstIp: String,
            dstPort: Long,
            conn: TCPConn
        ) {
            if (!running.get() || blockedIps.contains(dstIp)) {
                Log.d(
                    TAG,
                    "TCP BLOCK $srcIp:$srcPort -> $dstIp:$dstPort"
                )
                runCatching { conn.close() }
                return
            }

            /*
             * В текущей схеме в TUN должны попадать только
             * IP из списка блокировки. Неизвестные потоки
             * не перенаправляем самодельным прокси.
             */
            runCatching { conn.close() }
        }

        override fun handleUDP(
            srcIp: String,
            srcPort: Long,
            dstIp: String,
            dstPort: Long,
            conn: UDPConn
        ) {
            if (!running.get() || blockedIps.contains(dstIp)) {
                Log.d(
                    TAG,
                    "UDP BLOCK $srcIp:$srcPort -> $dstIp:$dstPort"
                )
            }

            runCatching { conn.close() }
        }

        override fun log(level: Long, msg: String) {
            Log.d(TAG, "Netstack[$level]: $msg")
        }
    }

    private fun failStart(message: String) {
        Log.e(TAG, message)
        updateNotification(message)
        stopFiltering()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopFiltering() {
        if (stopping) return
        stopping = true

        running.set(false)

        /*
         * Сначала останавливаем Netstack, который владеет TUN FD.
         */
        val tunnel = netstackTunnel
        netstackTunnel = null

        if (tunnel != null) {
            runCatching {
                tunnel.stop()
            }.onFailure {
                Log.e(TAG, "Ошибка остановки Netstack", it)
            }
        }

        /*
         * Если TUN не был передан Netstack, закрываем его здесь.
         */
        val pfd = vpnInterface
        vpnInterface = null

        if (pfd != null) {
            runCatching {
                pfd.close()
            }.onFailure {
                Log.e(TAG, "Ошибка закрытия VPN-интерфейса", it)
            }
        }

        Log.i(TAG, "VPN остановлен")
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

    private fun resolveIpv4Addresses(hostname: String): Set<String> {
        val result = linkedSetOf<String>()

        try {
            for (address in InetAddress.getAllByName(hostname)) {
                if (address.address.size != 4) continue

                val ip = address.hostAddress ?: continue

                if (isValidIpv4(ip)) {
                    result.add(ip)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось разрешить $hostname", e)
        }

        return result
    }

    private fun normalizeEntry(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null

        if (value.contains("/")) {
            return value.takeIf { isValidCidr(it) }
        }

        /*
         * Поддерживаем записи вида IP:порт.
         * Порт не участвует в фильтрации маршрутов.
         */
        val ip = if (value.count { it == ':' } == 1) {
            val candidate = value.substringBeforeLast(":")
            val port = value.substringAfterLast(":").toIntOrNull()

            if (port == null || port !in 1..65535) {
                value
            } else {
                candidate
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

        /*
         * Ограничиваем раскрытие CIDR, чтобы случайно
         * не создать огромный список адресов.
         */
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
        if (parts.size != 2 || !isValidIpv4(parts[0])) return false

        val prefix = parts[1].toIntOrNull() ?: return false
        return prefix in 20..32
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() && number != null && number in 0..255
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
        val manager = getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
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
