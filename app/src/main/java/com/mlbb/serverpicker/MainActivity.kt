package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class Country(
        val name: String,
        val code: String,
        val servers: List<String>
    )

    private val countries = mutableListOf<Country>()

    private lateinit var root: LinearLayout
    private lateinit var countrySpinner: Spinner
    private lateinit var serversLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var vpnStatusText: TextView
    private lateinit var modeGroup: RadioGroup

    private var selectedEntry: String? = null

    private var filterMode = FILTER_ALLOW

    private var pendingCountryCode: String? = null
    private var pendingServerEntry: String? = null

    companion object {
        private const val VPN_REQUEST_CODE = 701

        private const val FILTER_ALLOW = "allow"
        private const val FILTER_BLOCK = "block"

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

    private fun buildScreen() {

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(16),
                dp(18),
                dp(16),
                dp(24)
            )
        }

        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(scroll)

        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 25f
            setTextColor(TEXT)
            setTypeface(null, Typeface.BOLD)
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

        root.addView(
            sectionTitle("РЕЖИМ ФИЛЬТРА"),
            matchParams(bottom = 7)
        )

        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(
                dp(10),
                dp(4),
                dp(10),
                dp(4)
            )

            background = roundedBackground(
                CARD,
                BORDER,
                12
            )
        }

        val allowRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Разрешить выбранное"
            textSize = 15f
            setTextColor(TEXT)
            isChecked = true
            setPadding(
                dp(4),
                dp(8),
                dp(4),
                dp(8)
            )
        }

        val blockRadio = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Заблокировать выбранное"
            textSize = 15f
            setTextColor(TEXT)
            setPadding(
                dp(4),
                dp(8),
                dp(4),
                dp(8)
            )
        }

        modeGroup.addView(
            allowRadio,
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        modeGroup.addView(
            blockRadio,
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        modeGroup.setOnCheckedChangeListener { _, checkedId ->

            filterMode =
                if (checkedId == allowRadio.id) {
                    FILTER_ALLOW
                } else {
                    FILTER_BLOCK
                }

            selectedEntry = null

            updateModeDescription()
            showServers()
        }

        root.addView(
            modeGroup,
            matchParams(bottom = 18)
        )

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

        statusText = TextView(this).apply {
            text = "Загрузка списка..."
            textSize = 13f
            setTextColor(SECONDARY)
        }

        root.addView(
            statusText,
            matchParams(bottom = 14)
        )

        root.addView(
            createVpnStatusCard(),
            matchParams(bottom = 18)
        )

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

                    vpnStatusText.text = "● VPN выключен"
                    vpnStatusText.setTextColor(SECONDARY)

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

        val footer = TextView(this).apply {
            text = "Фильтр работает на уровне IP-маршрутизации Android."
            textSize = 12f
            setTextColor(SECONDARY)
            gravity = Gravity.CENTER
        }

        root.addView(
            footer,
            matchParams()
        )
    }

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
                Typeface.BOLD
            )
        }

        container.addView(
            vpnStatusText,
            matchParams()
        )

        return container
    }

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

                val item = array.getJSONObject(i)

                val serverArray =
                    item.getJSONArray("servers")

                val servers = mutableListOf<String>()

                for (j in 0 until serverArray.length()) {

                    val raw =
                        serverArray.getString(j)

                    val normalized =
                        normalizeEntry(raw)

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

            val names = countries.map {
                "${it.name} (${it.code})"
            }

            val adapter =
                object : ArrayAdapter<String>(
                    this,
                    android.R.layout.simple_spinner_item,
                    names
                ) {

                    override fun getView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {

                        val view = super.getView(
                            position,
                            convertView,
                            parent
                        )

                        (view as TextView).apply {
                            setTextColor(TEXT)
                            textSize = 16f
                            gravity =
                                Gravity.CENTER_VERTICAL
                        }

                        return view
                    }

                    override fun getDropDownView(
                        position: Int,
                        convertView: View?,
                        parent: ViewGroup
                    ): View {

                        val view = super.getDropDownView(
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
                object : AdapterView.OnItemSelectedListener {

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

    private fun showServers() {

        serversLayout.removeAllViews()

        val country =
            currentCountry()
                ?: return

        val isBlockMode =
            filterMode == FILTER_BLOCK

        val wholeCountryCard =
            LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                setPadding(
                    dp(14),
                    dp(14),
                    dp(14),
                    dp(14)
                )

                val selected =
                    selectedEntry == null

                background =
                    roundedBackground(
                        if (selected) {
                            if (isBlockMode) {
                                0xFF352222.toInt()
                            } else {
                                CARD_SELECTED
                            }
                        } else {
                            CARD
                        },
                        if (selected) {
                            if (isBlockMode) {
                                RED
                            } else {
                                GREEN
                            }
                        } else {
                            BORDER
                        },
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
                text =
                    if (isBlockMode) "🚫" else "🌐"

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
                    Typeface.BOLD
                )
            }

        titleContainer.addView(
            wholeTitle,
            matchParams(bottom = 2)
        )

        val description =
            TextView(this).apply {

                text =
                    if (isBlockMode) {
                        "Заблокировать все IP выбранной страны"
                    } else {
                        "Разрешить все IP выбранной страны"
                    }

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
                    if (selected) "✓" else ""

                textSize = 22f

                setTextColor(
                    if (isBlockMode) RED else GREEN
                )

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
                    if (selected) {
                        if (isBlockMode) {
                            "Вся страна будет заблокирована"
                        } else {
                            "Вся страна выбрана"
                        }
                    } else {
                        if (isBlockMode) {
                            "Заблокировать всю страну"
                        } else {
                            "Выбрать всю страну"
                        }
                    }

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
                        if (isBlockMode) {
                            "Будет заблокирована вся страна: ${country.name}"
                        } else {
                            "Выбрана вся страна: ${country.name}"
                        }
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

        for (entry in country.servers) {

            serversLayout.addView(
                createServerCard(entry),
                matchParams(bottom = 10)
            )
        }
    }

    private fun createServerCard(
        entry: String
    ): View {

        val selected =
            selectedEntry == entry

        val isBlockMode =
            filterMode == FILTER_BLOCK

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
                        if (selected) {
                            if (isBlockMode) {
                                0xFF352222.toInt()
                            } else {
                                CARD_SELECTED
                            }
                        } else {
                            CARD
                        },
                        if (selected) {
                            if (isBlockMode) {
                                RED
                            } else {
                                GREEN
                            }
                        } else {
                            BORDER
                        },
                        14
                    )
            }

        val top =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val icon =
            TextView(this).apply {
                text =
                    if (isBlockMode) "🚫" else "🖥"

                textSize = 21f
            }

        top.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(40),
                dp(40)
            )
        )

        val info =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val title =
            TextView(this).apply {
                text = entry
                textSize = 16f
                setTextColor(TEXT)

                setTypeface(
                    null,
                    Typeface.BOLD
                )
            }

        info.addView(
            title,
            matchParams(bottom = 2)
        )

        val description =
            TextView(this).apply {

                text =
                    if (isBlockMode) {
                        "Заблокировать этот сервер"
                    } else {
                        "Разрешить только этот сервер"
                    }

                textSize = 13f
                setTextColor(SECONDARY)
            }

        info.addView(
            description,
            matchParams()
        )

        top.addView(
            info,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val selectedMark =
            TextView(this).apply {

                text =
                    if (selected) "✓" else ""

                textSize = 21f

                setTextColor(
                    if (isBlockMode) RED else GREEN
                )

                gravity =
                    Gravity.CENTER
            }

        top.addView(
            selectedMark,
            LinearLayout.LayoutParams(
                dp(36),
                dp(40)
            )
        )

        card.addView(
            top,
            matchParams()
        )

        val button =
            Button(this).apply {

                text =
                    if (selected) {
                        if (isBlockMode) {
                            "Сервер будет заблокирован"
                        } else {
                            "Сервер выбран"
                        }
                    } else {
                        if (isBlockMode) {
                            "Заблокировать этот сервер"
                        } else {
                            "Выбрать этот сервер"
                        }
                    }

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

                    selectedEntry =
                        if (selected) {
                            null
                        } else {
                            entry
                        }

                    showServers()

                    statusText.text =
                        if (selectedEntry == null) {
                            "Выбрана вся страна: ${currentCountry()?.name}"
                        } else {
                            if (isBlockMode) {
                                "Будет заблокирован сервер: $entry"
                            } else {
                                "Выбран сервер: $entry"
                            }
                        }
                }
            }

        card.addView(
            button,
            matchParams(
                height = 44,
                top = 12
            )
        )

        return card
    }

    private fun requestVpn() {

        val country =
            currentCountry()

        if (country == null) {

            statusText.text =
                "Сначала выберите страну."

            return
        }

        pendingCountryCode =
            country.code

        pendingServerEntry =
            selectedEntry

        val prepare =
            VpnService.prepare(this)

        if (prepare != null) {

            startActivityForResult(
                prepare,
                VPN_REQUEST_CODE
            )

        } else {

            startFilteringService()
        }
    }

    private fun startFilteringService() {

        val countryCode =
            pendingCountryCode

        if (countryCode.isNullOrBlank()) {

            statusText.text =
                "Ошибка: страна не выбрана."

            return
        }

        val intent =
            Intent(
                this,
                MlbbVpnService::class.java
            ).apply {

                action =
                    MlbbVpnService.ACTION_START

                putExtra(
                    MlbbVpnService.EXTRA_COUNTRY_CODE,
                    countryCode
                )

                putExtra(
                    MlbbVpnService.EXTRA_SERVER_IP,
                    pendingServerEntry
                )

                putExtra(
                    MlbbVpnService.EXTRA_FILTER_MODE,
                    filterMode
                )
            }

        try {

            startService(intent)

            vpnStatusText.text =
                if (filterMode == FILTER_BLOCK) {
                    "● Блокировка включена"
                } else {
                    "● Фильтр включён"
                }

            vpnStatusText.setTextColor(
                if (filterMode == FILTER_BLOCK) {
                    RED
                } else {
                    GREEN
                }
            )

            val country =
                currentCountry()

            val target =
                pendingServerEntry ?: "вся страна"

            statusText.text =
                if (filterMode == FILTER_BLOCK) {
                    "Блокируется $target для ${country?.name}"
                } else {
                    "Разрешён $target для ${country?.name}"
                }

        } catch (e: Exception) {

            statusText.text =
                "Ошибка запуска VPN: ${e.message}"
        }
    }

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

        if (requestCode != VPN_REQUEST_CODE) {
            return
        }

        if (resultCode == RESULT_OK) {

            startFilteringService()

        } else {

            statusText.text =
                "Разрешение VPN не предоставлено."

            vpnStatusText.text =
                "● VPN выключен"

            vpnStatusText.setTextColor(
                SECONDARY
            )
        }
    }

    private fun currentCountry(): Country? {

        val position =
            countrySpinner.selectedItemPosition

        if (
            position < 0 ||
            position >= countries.size
        ) {
            return null
        }

        return countries[position]
    }

    private fun updateModeDescription() {

        val country =
            currentCountry()
                ?: return

        statusText.text =
            if (filterMode == FILTER_BLOCK) {
                "Режим: блокировка выбранной страны или сервера"
            } else {
                "Режим: разрешение выбранной страны или сервера"
            }
    }

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

    private fun sectionTitle(
        text: String
    ): TextView {

        return TextView(this).apply {

            this.text = text
            textSize = 11f
            setTextColor(SECONDARY)

            setTypeface(
                null,
                Typeface.BOLD
            )
        }
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(fill)

            setStroke(
                dp(1),
                stroke
            )

            cornerRadius =
                dp(radiusDp).toFloat()
        }
    }

    private fun matchParams(
        height: Int =
            ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (
                height ==
                ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                dp(height)
            }
        ).apply {

            if (
                top != 0 ||
                bottom != 0
            ) {

                setMargins(
                    0,
                    dp(top),
                    0,
                    dp(bottom)
                )
            }
        }
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).roundToInt()
    }
}