package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import kotlin.math.roundToInt

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
    private lateinit var vpnStatusText: TextView

    /*
     * null = выбрана вся страна
     * значение = выбран конкретный IP/диапазон
     */
    private var selectedEntry: String? = null

    private var pendingCountryCode: String? = null
    private var pendingServerEntry: String? = null

    companion object {
        private const val VPN_REQUEST_CODE = 701

        private const val BG = 0xFF101114.toInt()
        private const val CARD = 0xFF1A1C21.toInt()
        private const val CARD_SELECTED = 0xFF202D26.toInt()
        private const val BORDER = 0xFF30333A.toInt()
        private const val TEXT = 0xFFF5F5F5.toInt()
        private const val SECONDARY = 0xFFAAADB5.toInt()
        private const val GREEN = 0xFF65D48B.toInt()
        private const val RED = 0xFFFF6B6B.toInt()
        private const val BUTTON = 0xFF2A2D33.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG

        buildScreen()
        loadCountries()
    }

    // ---------------------------------------------------------
    // ОСНОВНОЙ ЭКРАН
    // ---------------------------------------------------------

    private fun buildScreen() {

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)

        // Заголовок
        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 25f
            setTextColor(TEXT)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        root.addView(
            title,
            matchParams(bottom = 4)
        )

        val subtitle = TextView(this).apply {
            text = "Управление игровым соединением"
            textSize = 14f
            setTextColor(SECONDARY)
        }

        root.addView(
            subtitle,
            matchParams(bottom = 20)
        )

        // -----------------------------------------------------
        // COUNTRY
        // -----------------------------------------------------

        root.addView(
            sectionTitle("СТРАНА"),
            matchParams(bottom = 7)
        )

        countrySpinner = Spinner(this).apply {
            setPadding(
                dp(12),
                0,
                dp(12),
                0
            )
        }

        val spinnerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(
                CARD,
                BORDER,
                12
            )
            addView(
                countrySpinner,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54)
                )
            )
        }

        root.addView(
            spinnerContainer,
            matchParams(bottom = 18)
        )

        // -----------------------------------------------------
        // STATUS
        // -----------------------------------------------------

        statusText = TextView(this).apply {
            text = "Загрузка списка..."
            textSize = 13f
            setTextColor(SECONDARY)
        }

        root.addView(
            statusText,
            matchParams(bottom = 14)
        )

        // -----------------------------------------------------
        // VPN STATUS CARD
        // -----------------------------------------------------

        root.addView(
            createVpnStatusCard(),
            matchParams(bottom = 18)
        )

        // -----------------------------------------------------
        // SERVERS TITLE
        // -----------------------------------------------------

        root.addView(
            sectionTitle("СЕРВЕРЫ"),
            matchParams(bottom = 8)
        )

        serversLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            serversLayout,
            matchParams(bottom = 16)
        )

        // -----------------------------------------------------
        // START BUTTON
        // -----------------------------------------------------

        val startButton = Button(this).apply {
            text = "ЗАПУСТИТЬ ФИЛЬТР"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundedBackground(
                GREEN,
                GREEN,
                14
            )

            setOnClickListener {
                requestVpn()
            }
        }

        root.addView(
            startButton,
            matchParams(
                height = 54,
                bottom = 10
            )
        )

        // -----------------------------------------------------
        // STOP BUTTON
        // -----------------------------------------------------

        val stopButton = Button(this).apply {
            text = "Остановить VPN"
            textSize = 14f
            setTextColor(TEXT)
            isAllCaps = false
            background = roundedBackground(
                BUTTON,
                BORDER,
                14
            )

            setOnClickListener {

                val intent = Intent(
                    this@MainActivity,
                    MlbbVpnService::class.java
                ).apply {
                    action = MlbbVpnService.ACTION_STOP
                }

                try {
                    startService(intent)

                    vpnStatusText.text =
                        "● VPN выключен"

                    vpnStatusText.setTextColor(
                        SECONDARY
                    )

                    statusText.text =
                        "Отправлена команда остановки VPN."

                } catch (e: Exception) {

                    statusText.text =
                        "Не удалось остановить VPN: ${e.message}"
                }
            }
        }

        root.addView(
            stopButton,
            matchParams(
                height = 52,
                bottom = 16
            )
        )

        // Нижняя подпись
        val footer = TextView(this).apply {
            text =
                "Фильтр работает на уровне IP-маршрутизации Android."
            textSize = 12f
            setTextColor(SECONDARY)
            gravity = Gravity.CENTER
        }

        root.addView(
            footer,
            matchParams()
        )
    }

    // ---------------------------------------------------------
    // VPN STATUS
    // ---------------------------------------------------------

    private fun createVpnStatusCard(): View {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
            )

            background = roundedBackground(
                CARD,
                BORDER,
                14
            )
        }

        val label = TextView(this).apply {
            text = "СОСТОЯНИЕ"
            textSize = 11f
            setTextColor(SECONDARY)
        }

        container.addView(
            label,
            matchParams(bottom = 4)
        )

        vpnStatusText = TextView(this).apply {
            text = "● VPN выключен"
            textSize = 15f
            setTextColor(SECONDARY)
            setTypeface(
                null,
                android.graphics.Typeface.BOLD
            )
        }

        container.addView(
            vpnStatusText,
            matchParams()
        )

        return container
    }

    // ---------------------------------------------------------
    // COUNTRIES
    // ---------------------------------------------------------

    private fun loadCountries() {

        try {

            val json = assets
                .open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val array = JSONObject(json)
                .getJSONArray("countries")

            countries.clear()

            for (i in 0 until array.length()) {

                val item =
                    array.getJSONObject(i)

                val serverArray =
                    item.getJSONArray("servers")

                val servers =
                    mutableListOf<String>()

                for (j in 0 until serverArray.length()) {

                    val normalized =
                        normalizeEntry(
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

            val adapter =
                object : ArrayAdapter<String>(
                    this,
                    android.R.layout.simple_spinner_item,
                    countries.map {
                        "${it.name} (${it.code})"
                    }
                ) {

                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {

                        val view =
                            super.getView(
                                position,
                                convertView,
                                parent
                            )

                        (view as TextView).apply {
                            setTextColor(TEXT)
                            textSize = 16f
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        return view
                    }

                    override fun getDropDownView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {

                        val view =
                            super.getDropDownView(
                                position,
                                convertView,
                                parent
                            )

                        (view as TextView).apply {
                            setTextColor(TEXT)
                            textSize = 16f
                            setPadding(
                                dp(14),
                                dp(12),
                                dp(14),
                                dp(12)
                            )
                            setBackgroundColor(CARD)
                        }

                        return view
                    }
                }

            adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )

            countrySpinner.adapter = adapter

            countrySpinner.onItemSelectedListener =
                object :
                    AdapterView.OnItemSelectedListener {

                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {

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

    // ---------------------------------------------------------
    // SERVER LIST
    // ---------------------------------------------------------

    private fun showServers() {

        serversLayout.removeAllViews()

        val country =
            currentCountry()
                ?: return

        // Вся страна
        val wholeCountryCard =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                background =
                    roundedBackground(
                        if (selectedEntry == null)
                            CARD_SELECTED
                        else
                            CARD,
                        if (selectedEntry == null)
                            GREEN
                        else
                            BORDER,
                        14
                    )
            }

        val topRow =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val icon =
            TextView(this).apply {
                text = "🌐"
                textSize = 24f
            }

        topRow.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            )
        )

        val titleContainer =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val wholeTitle =
            TextView(this).apply {
                text = "Вся страна"
                textSize = 17f
                setTextColor(TEXT)
                setTypeface(
                    null,
                    android.graphics.Typeface.BOLD
                )
            }

        titleContainer.addView(
            wholeTitle,
            matchParams(bottom = 2)
        )

        val description =
            TextView(this).apply {
                text =
                    "Разрешить все IP выбранной страны"
                textSize = 13f
                setTextColor(SECONDARY)
            }

        titleContainer.addView(
            description,
            matchParams()
        )

        topRow.addView(
            titleContainer,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val check =
            TextView(this).apply {

                text =
                    if (selectedEntry == null)
                        "✓"
                    else
                        ""

                textSize = 22f
                setTextColor(GREEN)
                gravity = Gravity.CENTER
            }

        topRow.addView(
            check,
            LinearLayout.LayoutParams(
                dp(36),
                dp(40)
            )
        )

        wholeCountryCard.addView(
            topRow,
            matchParams()
        )

        val chooseCountryButton =
            Button(this).apply {

                text =
                    if (selectedEntry == null)
                        "Вся страна выбрана"
                    else
                        "Выбрать всю страну"

                textSize = 13f
                isAllCaps = false
                setTextColor(TEXT)

                background =
                    roundedBackground(
                        BUTTON,
                        BORDER,
                        10
                    )

                setOnClickListener {

                    selectedEntry = null

                    showServers()

                    statusText.text =
                        "Выбрана вся страна: ${country.name}"
                }
            }

        wholeCountryCard.addView(
            chooseCountryButton,
            matchParams(
                height = 44,
                top = 12
            )
        )

        serversLayout.addView(
            wholeCountryCard,
            matchParams(bottom = 10)
        )

        if (country.servers.isEmpty()) {

            val empty =
                TextView(this).apply {
                    text =
                        "Для этой страны нет корректных IP."
                    textSize = 14f
                    setTextColor(SECONDARY)
                    gravity = Gravity.CENTER
                    setPadding(
                        dp(10),
                        dp(20),
                        dp(10),
                        dp(20)
                    )
                }

            serversLayout.addView(
                empty,
                matchParams()
            )

            return
        }

        // IP карточки
        for (entry in country.servers) {

            serversLayout.addView(
                createServerCard(entry),
                matchParams(bottom = 10)
            )
        }
    }

    // ---------------------------------------------------------
    // SERVER CARD
    // ---------------------------------------------------------

    private fun createServerCard(
        entry: String
    ): View {

        val selected =
            selectedEntry == entry

        val card =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                background =
                    roundedBackground(
                        if (selected)
                            CARD_SELECTED
                        else
                            CARD,
                        if (selected)
                            GREEN
                        else
                            BORDER,
                        14
                    )
            }

        val address =
            TextView(this).apply {
                text = entry
                textSize = 17f
                setTextColor(TEXT)
                setTypeface(
              