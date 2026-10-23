package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private data class Country(
        val name: String,
        val code: String,
        val servers: List<String>
    )

    private val countries = mutableListOf<Country>()
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var root: LinearLayout
    private lateinit var countrySpinner: Spinner
    private lateinit var serversLayout: LinearLayout
    private lateinit var statusText: TextView

    /*
     * null = выбрана вся страна
     * значение = выбран конкретный IP/диапазон
     */
    private var selectedEntry: String? = null

    private var pendingCountryCode: String? = null
    private var pendingServerEntry: String? = null

    companion object {
        private const val VPN_REQUEST_CODE = 701
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()
        loadCountries()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 24f
            gravity = Gravity.CENTER
        }

        root.addView(title)

        root.addView(TextView(this).apply {
            text = "Выбери страну, IP или всю страну и запусти фильтр."
            textSize = 15f
            setPadding(0, 12, 0, 16)
        })

        countrySpinner = Spinner(this)
        root.addView(countrySpinner)

        statusText = TextView(this).apply {
            text = "Загрузка списка..."
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }

        root.addView(statusText)

        val startButton = Button(this).apply {
            text = "Запустить фильтр"
            setOnClickListener {
                requestVpn()
            }
        }

        root.addView(startButton)

        val stopButton = Button(this).apply {
            text = "Остановить VPN"

            setOnClickListener {
                val intent = Intent(
                    this@MainActivity,
                    MlbbVpnService::class.java
                ).apply {
                    action = MlbbVpnService.ACTION_STOP
                }

                startService(intent)

                statusText.text =
                    "Отправлена команда остановки VPN."
            }
        }

        root.addView(stopButton)

        root.addView(TextView(this).apply {
            text = "IP-адреса и диапазоны"
            textSize = 20f
            setPadding(0, 20, 0, 8)
        })

        serversLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(serversLayout)
    }

    private fun loadCountries() {
        try {
            val json = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val array = JSONObject(json)
                .getJSONArray("countries")

            countries.clear()

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)

                val serverArray =
                    item.getJSONArray("servers")

                val servers = mutableListOf<String>()

                for (j in 0 until serverArray.length()) {
                    val normalized = normalizeEntry(
                        serverArray.getString(j)
                    )

                    if (normalized != null) {
                        servers.add(normalized)
                    }
                }

                countries.add(
                    Country(
                        name = item.getString("name"),
                        code = item.getString("code"),
                        servers = servers.distinct()
                    )
                )
            }

            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                countries.map {
                    "${it.name} (${it.code})"
                }
            )

            adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )

            countrySpinner.adapter = adapter

            countrySpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        /*
                         * При переключении страны
                         * автоматически выбираем всю страну.
                         */
                        selectedEntry = null

                        showServers()
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) {
                    }
                }

            statusText.text =
                "Загружено стран: ${countries.size}"

        } catch (e: Exception) {

            statusText.text =
                "Ошибка загрузки server_data.json: ${e.message}"
        }
    }

    private fun showServers() {

        serversLayout.removeAllViews()

        val country = currentCountry()
            ?: return

        /*
         * Кнопка выбора всей страны.
         */
        val wholeCountryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }

        val wholeCountryTitle = TextView(this).apply {
            text = "Вся страна"
            textSize = 18f
        }

        wholeCountryCard.addView(wholeCountryTitle)

        val wholeCountryDescription = TextView(this).apply {
            text =
                "Разрешить все IP из выбранной страны"
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }

        wholeCountryCard.addView(
            wholeCountryDescription
        )

        val wholeCountryButton = Button(this).apply {

            text = if (selectedEntry == null) {
                "Вся страна выбрана"
            } else {
                "Выбрать всю страну"
            }

            setOnClickListener {

                /*
                 * null означает всю страну.
                 */
                selectedEntry = null

                showServers()

                statusText.text =
                    "Выбрана вся страна: ${country.name}"
            }
        }

        wholeCountryCard.addView(
            wholeCountryButton,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        if (selectedEntry == null) {
            wholeCountryCard.setBackgroundColor(
                0xFFE2F2E2.toInt()
            )
        }

        serversLayout.addView(
            wholeCountryCard
        )

        val countryDivider = View(this).apply {
            setBackgroundColor(0xFFCCCCCC.toInt())
        }

        serversLayout.addView(
            countryDivider,
            LinearLayout.LayoutParams(-1, 1)
        )

        /*
         * Если у страны нет IP.
         */
        if (country.servers.isEmpty()) {

            serversLayout.addView(
                TextView(this).apply {
                    text =
                        "Для этой страны нет корректных IP."
                }
            )

            return
        }

        /*
         * Список отдельных IP/диапазонов.
         */
        for (entry in country.servers) {

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
            }

            val addressText = TextView(this).apply {
                text = entry
                textSize = 17f
            }

            card.addView(addressText)

            val pingText = TextView(this).apply {
                text = "Пинг ещё не проверялся"
                textSize = 14f
                setPadding(0, 4, 0, 4)
            }

            card.addView(pingText)

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            /*
             * Кнопка выбора конкретного IP.
             */
            val selectButton = Button(this).apply {

                text = if (selectedEntry == entry) {
                    "Выбрано"
                } else {
                    "Выбрать"
                }

                setOnClickListener {

                    selectedEntry = entry

                    showServers()

                    statusText.text =
                        "Выбран IP: $entry"
                }
            }

            buttons.addView(
                selectButton,
                LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                )
            )

            /*
             * Кнопка проверки пинга.
             */
            val pingButton = Button(this).apply {
                text = "Проверить пинг"
            }

            pingButton.setOnClickListener {

                pingButton.isEnabled = false

                pingText.text =
                    "Проверка IP..."

                executor.execute {

                    val result =
                        checkRange(entry)

                    runOnUiThread {

                        pingText.text = result

                        pingButton.isEnabled = true
                    }
                }
            }

            /*
             * ВАЖНО:
             * pingButton добавляется только ОДИН раз.
             */
            buttons.addView(
                pingButton,
                LinearLayout.LayoutParams(
                    0,
                    -2,
                    1f
                )
            )

            card.addView(buttons)

            if (selectedEntry == entry) {
                card.setBackgroundColor(
                    0xFFE2F2E2.toInt()
                )
            }

            val divider = View(this).apply {
                setBackgroundColor(
                    0xFFCCCCCC.toInt()
                )
            }

            serversLayout.addView(card)

            serversLayout.addView(
                divider,
                LinearLayout.LayoutParams(
                    -1,
                    1
                )
            )
        }
    }

    private fun checkRange(entry: String): String {

        val ips = expandEntry(entry)

        if (ips.isEmpty()) {
            return "Не удалось разобрать диапазон"
        }

        val successfulTimes =
            mutableListOf<Long>()

        var checked = 0

        for (ip in ips) {

            if (
                checked >= 5 &&
                successfulTimes.size >= 3
            ) {
                break
            }

            checked++

            try {

                val address =
                    InetAddress.getByName(ip)

                val started =
                    System.nanoTime()

                val reachable =
                    address.isReachable(700)

                val elapsed =
                    (
                        System.nanoTime() - started
                    ) / 1_000_000

                if (reachable) {
                    successfulTimes.add(
                        elapsed
                    )
                }

            } catch (_: Exception) {
                /*
                 * Адрес не ответил.
                 */
            }
        }

        if (successfulTimes.isEmpty()) {

            return "Проверено $checked IP · " +
                "ответов нет. ICMP может блокироваться."
        }

        val average =
            successfulTimes.average().toLong()

        return "Проверено $checked IP · " +
            "ответили ${successfulTimes.size} · " +
            "средний пинг: ${average} мс"
    }

    private fun expandEntry(
        entry: String
    ): List<String> {

        if (!entry.contains("/")) {
            return listOf(entry)
        }

        val parts = entry.split("/")

        if (parts.size != 2) {
            return emptyList()
        }

        val base =
            ipv4ToLong(parts[0])
                ?: return emptyList()

        val prefix =
            parts[1].toIntOrNull()
                ?: return emptyList()

        if (prefix !in 20..32) {
            return emptyList()
        }

        val size =
            1L shl (32 - prefix)

        val network =
            (base / size) * size

        return (0 until size).map {
            offset ->
            longToIpv4(network + offset)
        }
    }

    private fun normalizeEntry(
        raw: String
    ): String? {

        val value = raw.trim()

        if (value.contains("/")) {

            val parts =
                value.split("/")

            if (parts.size != 2) {
                return null
            }

            val prefix =
                parts[1].toIntOrNull()
                    ?: return null

            if (prefix !in 20..32) {
                return null
            }

            return parts[0]
                .takeIf {
                    isPlainIpv4(it)
                }
                ?.let {
                    "$it/$prefix"
                }
        }

        val ip =
            value.substringBeforeLast(":")

        return ip.takeIf {
            isPlainIpv4(it)
        }
    }

    private fun isPlainIpv4(
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

    private fun ipv4ToLong(
        ip: String
    ): Long? {

        if (!isPlainIpv4(ip)) {
            return null
        }

        return ip.split(".")
            .fold(0L) { result, part ->

                (result shl 8) or
                    part.toLong()
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

    private fun currentCountry(): Country? {

        val position =
            countrySpinner.selectedItemPosition

        return countries.getOrNull(position)
    }

    private fun requestVpn() {

        val country =
            currentCountry()

        if (country == null) {

            statusText.text =
                "Сначала выбери страну."

            return
        }

        /*
         * Теперь selectedEntry МОЖЕТ быть null.
         *
         * null = вся страна.
         * IP = конкретный выбранный IP.
         */

        pendingCountryCode =
            country.code

        pendingServerEntry =
            selectedEntry

        val prepareIntent =
            VpnService.prepare(this)

        if (prepareIntent != null) {

            @Suppress("DEPRECATION")
            startActivityForResult(
                prepareIntent,
                VPN_REQUEST_CODE
            )

        } else {

            startSelectedFilter()
        }
    }

    @Deprecated(
        "Используется для совместимости с VPN-разрешением"
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode ==
            VPN_REQUEST_CODE
        ) {

            if (
                resultCode ==
                Activity.RESULT_OK
            ) {

                startSelectedFilter()

            } else {

                statusText.text =
                    "Разрешение VPN не предоставлено."
            }
        }
    }

    private fun startSelectedFilter() {

        val code =
            pendingCountryCode
                ?: return

        /*
         * entry может быть null.
         *
         * null означает:
         * использовать всю страну.
         */
        val entry =
            pendingServerEntry

        val intent =
            Intent(
                this,
                MlbbVpnService::class.java
            ).apply {

                action =
                    MlbbVpnService.ACTION_START

                putExtra(
                    MlbbVpnService.EXTRA_COUNTRY_CODE,
                    code
                )

                if (!entry.isNullOrBlank()) {

                    putExtra(
                        MlbbVpnService.EXTRA_SERVER_IP,
                        entry
                    )
                }
            }

        try {

            if (
                android.os.Build.VERSION.SDK_INT >= 26
            ) {

                startForegroundService(
                    intent
                )

            } else {

                startService(intent)
            }

            if (entry.isNullOrBlank()) {

                statusText.text =
                    "Запущен фильтр всей страны: $code"

            } else {

                statusText.text =
                    "Запущен фильтр: $code · $entry"
            }

        } catch (e: Exception) {

            statusText.text =
                "Не удалось запустить фильтр: ${e.message}"
        }
    }

    override fun onDestroy() {

        executor.shutdownNow()

        super.onDestroy()
    }
}