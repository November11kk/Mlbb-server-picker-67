package com.mlbb.serverpicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.netvalve.bridge.Bridge
import dev.netvalve.bridge.Handler
import dev.netvalve.bridge.TCPConn
import dev.netvalve.bridge.Tunnel
import dev.netvalve.bridge.UDPConn
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

class MlbbVpnService : VpnService() {

    companion object {

        const val ACTION_START =
            "com.mlbb.serverpicker.START_FILTER"

        const val ACTION_STOP =
            "com.mlbb.serverpicker.STOP_FILTER"

        const val EXTRA_COUNTRY_CODE =
            "country_code"

        const val EXTRA_SERVER_IP =
            "server_ip"

        const val EXTRA_FILTER_MODE =
            "filter_mode"

        const val FILTER_ALLOW =
            "allow"

        const val FILTER_BLOCK =
            "block"

        private const val BLOCKED_HOST =
            "ip.ml.youngjoygame.com"

        private const val CHANNEL_ID =
            "mlbb_filter_channel"

        private const val NOTIFICATION_ID =
            701
    }

    private var vpnInterface:
        ParcelFileDescriptor? = null

    private var netstackTunnel:
        Tunnel? = null

    private val running =
        AtomicBoolean(false)

    private val flowThreads =
        mutableListOf<Thread>()

    private val flowThreadsLock =
        Any()

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
                    intent.getStringExtra(
                        EXTRA_COUNTRY_CODE
                    )

                val serverEntry =
                    intent.getStringExtra(
                        EXTRA_SERVER_IP
                    )

                val filterMode =
                    intent.getStringExtra(
                        EXTRA_FILTER_MODE
                    ) ?: FILTER_ALLOW

                if (countryCode.isNullOrBlank()) {

                    stopSelf()

                    return START_NOT_STICKY
                }

                createNotificationChannel()

                startForeground(
                    NOTIFICATION_ID,
                    createNotification(
                        "Подготовка фильтра"
                    )
                )

                startFiltering(
                    countryCode,
                    serverEntry,
                    filterMode
                )
            }

            else -> {

                stopSelf()

                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    // =========================================================
    // START FILTERING
    // =========================================================

    private fun startFiltering(
        countryCode: String,
        selectedEntry: String?,
        filterMode: String
    ) {

        try {

            val text =
                assets
                    .open("server_data.json")
                    .bufferedReader()
                    .use { it.readText() }

            val countries =
                JSONObject(text)
                    .getJSONArray("countries")

            val allIps =
                linkedSetOf<String>()

            val selectedIps =
                linkedSetOf<String>()

            var countryFound =
                false

            // -------------------------------------------------
            // READ COUNTRIES AND IPs
            // -------------------------------------------------

            for (i in 0 until countries.length()) {

                val country =
                    countries.getJSONObject(i)

                val code =
                    country.getString("code")

                val servers =
                    country.getJSONArray("servers")

                if (code == countryCode) {

                    countryFound = true
                }

                for (j in 0 until servers.length()) {

                    val entry =
                        normalizeEntry(
                            servers.getString(j)
                        ) ?: continue

                    val addresses =
                        expandEntry(entry)
                            ?: continue

                    allIps.addAll(
                        addresses
                    )

                    if (
                        code == countryCode &&
                        (
                            selectedEntry.isNullOrBlank() ||
                            selectedEntry == entry
                        )
                    ) {

                        selectedIps.addAll(
                            addresses
                        )
                    }
                }
            }

            // -------------------------------------------------
            // CHECK COUNTRY
            // -------------------------------------------------

            if (!countryFound) {

                updateNotification(
                    "Ошибка: страна не найдена"
                )

                stopFiltering()
                stopForegroundCompat()
                stopSelf()

                return
            }

            if (selectedIps.isEmpty()) {

                updateNotification(
                    "Ошибка: IP выбранного объекта не найдены"
                )

                stopFiltering()
                stopForegroundCompat()
                stopSelf()

                return
            }

            // -------------------------------------------------
            // DETERMINE BLOCKED IPs
            // -------------------------------------------------

            val blockedIps =
                if (filterMode == FILTER_BLOCK) {

                    selectedIps.toMutableSet()

                } else {

                    (allIps - selectedIps)
                        .toMutableSet()
                }

            // -------------------------------------------------
            // BLOCK SPECIAL HOST
            // -------------------------------------------------

            val blockedHostIps =
                resolveIpv4Addresses(
                    BLOCKED_HOST
                )

            blockedIps.addAll(
                blockedHostIps
            )

            if (blockedIps.isEmpty()) {

                updateNotification(
                    "Ошибка: список блокировки пуст"
                )

                stopFiltering()
                stopForegroundCompat()
                stopSelf()

                return
            }

            // -------------------------------------------------
            // SAVE FILTER SET
            // -------------------------------------------------

            val blockedSet =
                blockedIps.toHashSet()

            // -------------------------------------------------
            // VPN BUILDER
            // -------------------------------------------------

            val builder =
                Builder()
                    .setSession(
                        "MLBB Server Picker"
                    )
                    .addAddress(
                        "10.10.0.2",
                        32
                    )
                    .setMtu(1500)

            /*
             * Только заблокированные IP направляются
             * в TUN.
             *
             * Разрешённые IP продолжают идти напрямую
             * через обычное подключение телефона.
             */

            for (ip in blockedIps) {

                builder.addRoute(
                    ip,
                    32
                )
            }

            val newInterface =
                builder.establish()

            if (newInterface == null) {

                updateNotification(
                    "VPN не разрешён Android"
                )

                stopFiltering()
                stopForegroundCompat()
                stopSelf()

                return
            }

            vpnInterface =
                newInterface

            running.set(true)

            // -------------------------------------------------
            // TAKE OWNERSHIP OF TUN FD
            // -------------------------------------------------

            /*
             * NetValve's Go netstack explicitly takes ownership
             * of the TUN file descriptor.
             *
             * detachFd() передаёт владение FD от Android
             * ParcelFileDescriptor к Go netstack.
             */

            val tunFd =
                newInterface.detachFd()

            // -------------------------------------------------
            // START NETSTACK
            // -------------------------------------------------

            val handler =
                NetstackHandler(
                    blockedSet
                )

            try {

                netstackTunnel =
                    Bridge.newTunnel(
                        tunFd.toLong(),
                        1500L,
                        "FAST_REJECT",
                        handler
                    )

            } catch (e: Exception) {

                running.set(false)

                updateNotification(
                    "Ошибка запуска netstack: " +
                        e.javaClass.simpleName
                )

                stopFiltering()
                stopForegroundCompat()
                stopSelf()

                return
            }

            // После detachFd() ParcelFileDescriptor больше
            // не владеет TUN.
            vpnInterface = null

            // -------------------------------------------------
            // NOTIFICATION
            // -------------------------------------------------

            val modeText =
                if (
                    filterMode == FILTER_BLOCK
                ) {
                    "блокировка"
                } else {
                    "разрешение"
                }

            updateNotification(
                "Netstack: ON; " +
                    "режим: $modeText; " +
                    "заблокировано IP: ${blockedIps.size}"
            )

        } catch (e: Exception) {

            stopFiltering()

            updateNotification(
                "Ошибка фильтра: " +
                    e.javaClass.simpleName
            )

            stopForegroundCompat()

            stopSelf()
        }
    }

    // =========================================================
    // NETSTACK HANDLER
    // =========================================================

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

            if (!running.get()) {

                runCatching {
                    conn.close()
                }

                return
            }

            /*
             * Любой поток, который оказался в TUN,
             * должен быть заблокирован, если его destination
             * присутствует в blockedIps.
             *
             * Это дополнительная страховка:
             * даже если пакет каким-то образом оказался
             * внутри netstack, он не уйдёт в интернет.
             */

            if (blockedIps.contains(dstIp)) {

                logNetstack(
                    "TCP BLOCK " +
                        "$srcIp:$srcPort -> " +
                        "$dstIp:$dstPort"
                )

                runCatching {
                    conn.close()
                }

                return
            }

            /*
             * Для остальных потоков выполняем обычный
             * direct forwarding.
             */

            val worker =
                thread(
                    start = true,
                    name = "MLBB-TCP-$dstIp:$dstPort"
                ) {

                    forwardTcp(
                        conn,
                        dstIp,
                        dstPort.toInt()
                    )
                }

            rememberThread(worker)
        }

        override fun handleUDP(
            srcIp: String,
            srcPort: Long,
            dstIp: String,
            dstPort: Long,
            conn: UDPConn
        ) {

            if (!running.get()) {

                runCatching {
                    conn.close()
                }

                return
            }

            if (blockedIps.contains(dstIp)) {

                logNetstack(
                    "UDP BLOCK " +
                        "$srcIp:$srcPort -> " +
                        "$dstIp:$dstPort"
                )

                runCatching {
                    conn.close()
                }

                return
            }

            val worker =
                thread(
                    start = true,
                    name = "MLBB-UDP-$dstIp:$dstPort"
                ) {

                    forwardUdp(
                        conn,
                        dstIp,
                        dstPort.toInt()
                    )
                }

            rememberThread(worker)
        }

        override fun log(
            level: Long,
            msg: String
        ) {

            logNetstack(
                "[$level] $msg"
            )
        }
    }

    // =========================================================
    // TCP FORWARDING
    // =========================================================

    private fun forwardTcp(
        conn: TCPConn,
        dstIp: String,
        dstPort: Int
    ) {

        var socket:
            Socket? = null

        try {

            socket =
                Socket()

            /*
             * Очень важно:
             *
             * socket должен быть защищён от Android VPN,
             * иначе собственный исходящий TCP может снова
             * попасть в TUN и создать бесконечную петлю.
             */

            if (!protect(socket)) {

                logNetstack(
                    "TCP protect() failed: " +
                        "$dstIp:$dstPort"
                )

                return
            }

            socket.tcpNoDelay = true

            socket.connect(
                InetSocketAddress(
                    dstIp,
                    dstPort
                ),
                10000
            )

            val input =
                BufferedInputStream(
                    socket.getInputStream()
                )

            val output =
                BufferedOutputStream(
                    socket.getOutputStream()
                )

            val upstreamToApp =
                thread(
                    start = true,
                    name = "MLBB-TCP-DOWN-$dstIp:$dstPort"
                ) {

                    val buffer =
                        ByteArray(16384)

                    try {

                        while (
                            running.get()
                        ) {

                            val count =
                                input.read(
                                    buffer
                                )

                            if (count < 0) {
                                break
                            }

                            if (count == 0) {
                                continue
                            }

                            conn.write(
                                buffer.copyOf(
                                    count
                                )
                            )
                        }

                    } catch (_: Exception) {

                    } finally {

                        runCatching {
                            conn.close()
                        }
                    }
                }

            try {

                val buffer =
                    ByteArray(16384)

                while (
                    running.get()
                ) {

                    val count =
                        conn.read(
                            buffer
                        ).toInt()

                    if (count <= 0) {
                        break
                    }

                    output.write(
                        buffer,
                        0,
                        count
                    )

                    output.flush()
                }

            } catch (_: Exception) {

            } finally {

                runCatching {
                    output.flush()
                }

                runCatching {
                    conn.close()
                }

                runCatching {
                    upstreamToApp.join(
                        2000
                    )
                }
            }

        } catch (e: Exception) {

            logNetstack(
                "TCP forwarding failed " +
                    "$dstIp:$dstPort: " +
                    e.javaClass.simpleName
            )

        } finally {

            runCatching {
                socket?.close()
            }

            runCatching {
                conn.close()
            }
        }
    }

    // =========================================================
    // UDP FORWARDING
    // =========================================================

    private fun forwardUdp(
        conn: UDPConn,
        dstIp: String,
        dstPort: Int
    ) {

        var socket:
            DatagramSocket? = null

        try {

            socket =
                DatagramSocket()

            /*
             * Защищаем UDP socket от VPN,
             * чтобы он отправлял напрямую наружу.
             */

            if (!protect(socket)) {

                logNetstack(
                    "UDP protect() failed: " +
                        "$dstIp:$dstPort"
                )

                return
            }

            socket.connect(
                InetAddress.getByName(
                    dstIp
                ),
                dstPort
            )

            socket.soTimeout = 1000

            val upstreamToApp =
                thread(
                    start = true,
                    name = "MLBB-UDP-DOWN-$dstIp:$dstPort"
                ) {

                    val buffer =
                        ByteArray(65535)

                    try {

                        while (
                            running.get()
                        ) {

                            try {

                                val packet =
                                    DatagramPacket(
                                        buffer,
                                        buffer.size
                                    )

                                socket.receive(
                                    packet
                                )

                                val data =
                                    packet.data.copyOfRange(
                                        packet.offset,
                                        packet.offset +
                                            packet.length
                                    )

                                conn.send(
                                    data
                                )

                            } catch (
                                _: java.net.SocketTimeoutException
                            ) {

                                // Просто проверяем running.
                            }
                        }

                    } catch (_: Exception) {

                    } finally {

                        runCatching {
                            conn.close()
                        }
                    }
                }

            try {

                while (
                    running.get()
                ) {

                    val data =
                        conn.receive()
                            ?: break

                    if (data.isEmpty()) {
                        continue
                    }

                    val packet =
                        DatagramPacket(
                            data,
                            data.size,
                            InetAddress.getByName(
                                dstIp
                            ),
                            dstPort
                        )

                    socket.send(
                        packet
                    )
                }

            } catch (_: Exception) {

            } finally {

                runCatching {
                    conn.close()
                }

                runCatching {
                    upstreamToApp.join(
                        2000
                    )
                }
            }

        } catch (e: Exception) {

            logNetstack(
                "UDP forwarding failed " +
                    "$dstIp:$dstPort: " +
                    e.javaClass.simpleName
            )

        } finally {

            runCatching {
                socket?.close()
            }

            runCatching {
                conn.close()
            }
        }
    }

    // =========================================================
    // THREAD MANAGEMENT
    // =========================================================

    private fun rememberThread(
        worker: Thread
    ) {

        synchronized(
            flowThreadsLock
        ) {

            flowThreads.add(
                worker
            )

            flowThreads.removeAll {
                !it.isAlive
            }
        }
    }

    // =========================================================
    // STOP
    // =========================================================

    private fun stopFiltering() {

        running.set(false)

        /*
         * Сначала останавливаем netstack.
         *
         * Это важно: Go netstack владеет TUN FD.
         */

        runCatching {

            netstackTunnel?.stop()
        }

        netstackTunnel = null

        /*
         * После Tunnel.stop() TUN FD уже закрыт
         * netstack'ом.
         */

        vpnInterface?.let {

            runCatching {
                it.close()
            }
        }

        vpnInterface = null

        synchronized(
            flowThreadsLock
        ) {

            flowThreads.forEach {

                runCatching {
                    it.interrupt()
                }
            }

            flowThreads.clear()
        }
    }

    override fun onDestroy() {

        stopFiltering()

        stopForegroundCompat()

        super.onDestroy()
    }

    // =========================================================
    // DNS
    // =========================================================

    private fun resolveIpv4Addresses(
        hostname: String
    ): Set<String> {

        val result =
            linkedSetOf<String>()

        try {

            val addresses =
                InetAddress.getAllByName(
                    hostname
                )

            for (address in addresses) {

                if (
                    address.address.size != 4
                ) {
                    continue
                }

                val hostAddress =
                    address.hostAddress
                        ?: continue

                if (
                    isValidIpv4(
                        hostAddress
                    )
                ) {

                    result.add(
                        hostAddress
                    )
                }
            }

        } catch (_: Exception) {

        }

        return result
    }

    // =========================================================
    // NORMALIZE ENTRY
    // =========================================================

    private fun normalizeEntry(
        raw: String
    ): String? {

        val value =
            raw.trim()

        if (
            value.contains("/")
        ) {

            return value.takeIf {
                isValidCidr(it)
            }
        }

        val ip =
            value.substringBeforeLast(
                ":"
            )

        return ip.takeIf {
            isValidIpv4(it)
        }
    }

    // =========================================================
    // EXPAND CIDR
    // =========================================================

    private fun expandEntry(
        entry: String
    ): Set<String>? {

        if (
            !entry.contains("/")
        ) {

            return if (
                isValidIpv4(entry)
            ) {

                setOf(entry)

            } else {

                null
            }
        }

        val parts =
            entry.split("/")

        if (
            parts.size != 2 ||
            !isValidIpv4(parts[0])
        ) {

            return null
        }

        val prefix =
            parts[1].toIntOrNull()
                ?: return null

        /*
         * Не расширяем огромные сети.
         *
         * Разрешены /20 ... /32.
         */

        if (
            prefix !in 20..32
        ) {

            return null
        }

        val ipNumber =
            ipv4ToLong(
                parts[0]
            )

        val hostBits =
            32 - prefix

        val blockSize =
            1L shl hostBits

        val network =
            (
                ipNumber / blockSize
            ) * blockSize

        val result =
            LinkedHashSet<String>()

        for (
            offset in 0 until blockSize
        ) {

            result.add(
                longToIpv4(
                    network + offset
                )
            )
        }

        return result
    }

    // =========================================================
    // VALIDATION
    // =========================================================

    private fun isValidCidr(
        value: String
    ): Boolean {

        val parts =
            value.split("/")

        if (
            parts.size != 2 ||
            !isValidIpv4(parts[0])
        ) {

            return false
        }

        val prefix =
            parts[1].toIntOrNull()
                ?: return false

        return prefix in 20..32
    }

    private fun isValidIpv4(
        ip: String
    ): Boolean {

        val parts =
            ip.split(".")

        if (
            parts.size != 4
        ) {

            return false
        }

        return parts.all {

            val number =
                it.toIntOrNull()

            number != null &&
                number in 0..255 &&
                it.isNotEmpty()
        }
    }

    // =========================================================
    // IP CONVERSION
    // =========================================================

    private fun ipv4ToLong(
        ip: String
    ): Long {

        return ip
            .split(".")
            .fold(0L) { result, part ->

                (
                    result shl 8
                ) or part.toLong()
            }
    }

    private fun longToIpv4(
        value: Long
    ): String {

        return "${(value shr 24) and 255}." +
            "${(value shr 16) and 255}." +
            "${(value shr 8) and 255}." +
            "${value and 255}"
    }

    // =========================================================
    // LOGGING
    // =========================================================

    private fun logNetstack(
        message: String
    ) {

        android.util.Log.d(
            "MlbbNetstack",
            message
        )
    }

    // =========================================================
    // NOTIFICATION
    // =========================================================

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "MLBB IP-фильтр",
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(
                channel
            )
        }
    }

    private fun createNotification(
        message: String
    ): Notification {

        val openApp =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M
                    ) {

                        PendingIntent.FLAG_IMMUTABLE

                    } else {

                        0
                    }
            )

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "MLBB Server Picker"
                )
                .setContentText(
                    message
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_lock_lock
                )
                .setContentIntent(
                    pendingIntent
                )
                .setOngoing(
                    running.get()
                )
                .build()

        } else {

            @Suppress("DEPRECATION")

            Notification.Builder(this)
                .setContentTitle(
                    "MLBB Server Picker"
                )
                .setContentText(
                    message
                )
                .setSmallIcon(
                    android.R.drawable
                        .ic_lock_lock
                )
                .setContentIntent(
                    pendingIntent
                )
                .setOngoing(
                    running.get()
                )
                .build()
        }
    }

    private fun updateNotification(
        message: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(
                message
            )
        )
    }

    private fun stopForegroundCompat() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } else {

            @Suppress("DEPRECATION")

            stopForeground(
                true
            )
        }
    }
}