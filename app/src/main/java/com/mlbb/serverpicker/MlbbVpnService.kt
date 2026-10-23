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
import java.net.InetAddress
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

        /*
         * Хост, который всегда пытаемся блокировать.
         */
        private const val BLOCKED_HOST =
            "ip.ml.youngjoygame.com"

        private const val CHANNEL_ID =
            "mlbb_filter_channel"

        private const val NOTIFICATION_ID =
            701
    }

    private var vpnInterface:
        ParcelFileDescriptor? = null

    private var packetThread:
        Thread? = null

    private val running =
        AtomicBoolean(false)

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
            // ЧИТАЕМ ВСЕ СТРАНЫ И IP
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

                    allIps.addAll(addresses)

                    // Выбранная страна / выбранный сервер
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
            // ПРОВЕРКА СТРАНЫ
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
            // ОПРЕДЕЛЯЕМ ЧТО БЛОКИРОВАТЬ
            // -------------------------------------------------

            val blockedIps =
                if (filterMode == FILTER_BLOCK) {

                    /*
                     * Режим BLOCK:
                     *
                     * блокируем выбранные IP.
                     */
                    selectedIps.toMutableSet()

                } else {

                    /*
                     * Режим ALLOW:
                     *
                     * блокируем все известные IP,
                     * кроме выбранных.
                     */
                    (allIps - selectedIps)
                        .toMutableSet()
                }

            // -------------------------------------------------
            // БЛОКИРОВКА
            // ip.ml.youngjoygame.com
            // -------------------------------------------------

            /*
             * Получаем актуальные IPv4-адреса
             * hostname через DNS.
             *
             * Эти IP добавляются в blockedIps
             * независимо от выбранной страны.
             */

            val blockedHostIps =
                resolveIpv4Addresses(
                    BLOCKED_HOST
                )

            blockedIps.addAll(
                blockedHostIps
            )

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
             * Каждый IP, добавленный через addRoute(),
             * будет направлен в TUN.
             *
             * Пакеты из TUN мы намеренно не пересылаем.
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
            // NOTIFICATION
            // -------------------------------------------------

            val modeText =
                if (filterMode == FILTER_BLOCK)
                    "блокировка"
                else
                    "разрешение"

            updateNotification(
                "Режим: $modeText; " +
                    "IP: ${blockedIps.size}; " +
                    "host: $BLOCKED_HOST " +
                    "(${blockedHostIps.size})"
            )

            // -------------------------------------------------
            // PACKET THREAD
            // -------------------------------------------------

            packetThread =
                thread(
                    start = true,
                    name = "MLBB-IP-filter"
                ) {

                    val buffer =
                        ByteArray(32767)

                    try {

                        FileInputStream(
                            newInterface.fileDescriptor
                        ).use { input ->

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

                                /*
                                 * Пакет попал в TUN.
                                 *
                                 * Мы его НЕ пересылаем.
                                 *
                                 * Следовательно:
                                 *
                                 * BLOCK:
                                 * выбранный IP блокируется.
                                 *
                                 * ALLOW:
                                 * неразрешённый IP блокируется.
                                 *
                                 * ip.ml.youngjoygame.com:
                                 * его разрешённые DNS IPv4
                                 * также блокируются.
                                 */
                            }
                        }

                    } catch (_: Exception) {

                        // Ожидаемо при закрытии VPN.
                    }
                }

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
    // DNS RESOLUTION
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

                /*
                 * Берём только IPv4.
                 *
                 * IPv6 здесь специально
                 * не используется.
                 */

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

            /*
             * Если DNS временно не отвечает,
             * приложение продолжает работу
             * с IP из server_data.json.
             */
        }

        return result
    }

    // =========================================================
    // NORMALIZE IP
    // =========================================================

    private fun normalizeEntry(
        raw: String
    ): String? {

        val value =
            raw.trim()

        if (value.contains("/")) {

            return value.takeIf {
                isValidCidr(it)
            }
        }

        val ip =
            value.substringBeforeLast(":")

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

        if (!entry.contains("/")) {

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
         * Защита от огромных диапазонов.
         *
         * Разрешаем только /20 - /32.
         */

        if (prefix !in 20..32) {

            return null
        }

        val ipNumber =
            ipv4ToLong(parts[0])

        val hostBits =
            32 - prefix

        val blockSize =
            1L shl hostBits

        val network =
            (ipNumber / blockSize) *
                blockSize

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

        if (parts.size != 4) {

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

        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    // =========================================================
    // STOP
    // =========================================================

    private fun stopForegroundCompat() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.N
        ) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

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

            // Уже закрыт.
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

    override fun onBind(
        intent: Intent?
    ) = super.onBind(intent)
}