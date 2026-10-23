package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : Activity() {

    data class Country(
        val name: String,
        val code: String,
        val servers: List<String>
    )

    companion object {
        private const val VPN_REQUEST_CODE = 1001

        private const val FILTER_ALLOW = "allow"
        private const val FILTER_BLOCK = "block"

        private const val COLOR_BACKGROUND = "#0B0F14"
        private const val COLOR_CARD = "#151B23"
        private const val COLOR_CARD_SELECTED = "#202A36"
        private const val COLOR_ACCENT = "#4CAF50"
        private const val COLOR_RED = "#F44336"
        private const val COLOR_TEXT = "#FFFFFF"
        private const val COLOR_SECONDARY = "#AAB4C0"
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var countrySpinner: Spinner
    private lateinit var serversContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var modeDescription: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var countries: List<Country> = emptyList()

    private var selectedEntry: String? = null

    private var filterMode = FILTER_ALLOW

    private var pendingCountryCode: String? = null
    private var pendingServerIp: String? = null
    private var pendingFilterMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildInterface()
        loadServerData()
    }

    private fun buildInterface() {

        val scrollView = ScrollView(this)
        scrollView.setBackgroundColor(Color.parseColor(COLOR_BACKGROUND))

        rootLayout = LinearLayout(this)
        rootLayout.orientation = LinearLayout.VERTICAL
        rootLayout.setPadding(
            dp(16),
            dp(16),
            dp(16),
            dp(24)
        )

        scrollView.addView(rootLayout)

        val title = TextView(this)
        title.text = "MLBB SERVER PICKER"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        title.setTypeface(null, android.graphics.Typeface.BOLD)

        rootLayout.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60)
            )
        )

        val subtitle = TextView(this)
        subtitle.text = "Выбор и блокировка IP-серверов Mobile Legends"
        subtitle.textSize = 14f
        subtitle.setTextColor(Color.parseColor(COLOR_SECONDARY))
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, 0, 0, dp(12))

        rootLayout.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rootLayout.addView(
            sectionTitle("РЕЖИМ ФИЛЬТРА")
        )

        val modeCard = LinearLayout(this)
        modeCard.orientation = LinearLayout.VERTICAL
        modeCard.setPadding(
            dp(14),
            dp(10),
            dp(14),
            dp(10)
        )
        modeCard.background = roundedBackground(COLOR_CARD, 16)

        val modeGroup = RadioGroup(this)
        modeGroup.orientation = RadioGroup.VERTICAL

        val allowRadio = RadioButton(this)
        allowRadio.text = "Разрешить выбранное"
        allowRadio.textSize = 16f
        allowRadio.setTextColor(Color.WHITE)
        allowRadio.id = View.generateViewId()

        val blockRadio = RadioButton(this)
        blockRadio.text = "Заблокировать выбранное"
        blockRadio.textSize = 16f
        blockRadio.setTextColor(Color.WHITE)
        blockRadio.id = View.generateViewId()

        modeGroup.addView(allowRadio)
        modeGroup.addView(blockRadio)

        allowRadio.isChecked = true

        modeGroup.setOnCheckedChangeListener { _, checkedId ->

            filterMode = if (checkedId == blockRadio.id) {
                FILTER_BLOCK
            } else {
                FILTER_ALLOW
            }

            updateModeDescription()
        }

        modeCard.addView(
            modeGroup,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        modeDescription = TextView(this)
        modeDescription.textSize = 13f
        modeDescription.setTextColor(Color.parseColor(COLOR_SECONDARY))
        modeDescription.setPadding(
            dp(4),
            dp(6),
            dp(4),
            dp(4)
        )

        modeCard.addView(
            modeDescription,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        rootLayout.addView(
            modeCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        rootLayout.addView(
            sectionTitle("СТРАНА")
        )

        countrySpinner = Spinner(this)

        countrySpinner.background = roundedBackground(
            COLOR_CARD,
            12
        )

        rootLayout.addView(
            countrySpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        rootLayout.addView(
            sectionTitle("ВЫБРАННЫЙ СЕРВЕР")
        )

        statusText = TextView(this)
        statusText.text = "Ничего не выбрано"
        statusText.textSize = 14f
        statusText.setTextColor(Color.parseColor(COLOR_SECONDARY))
        statusText.gravity = Gravity.CENTER_VERTICAL
        statusText.setPadding(
            dp(14),
            dp(10),
            dp(14),
            dp(10)
        )
        statusText.background = roundedBackground(
            COLOR_CARD,
            12
        )

        rootLayout.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        rootLayout.addView(
            sectionTitle("СЕРВЕРЫ")
        )

        serversContainer = LinearLayout(this)
        serversContainer.orientation = LinearLayout.VERTICAL

        rootLayout.addView(
            serversContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        startButton = Button(this)
        startButton.text = "ЗАПУСТИТЬ ФИЛЬТР"
        startButton.textSize = 15f
        startButton.setTextColor(Color.WHITE)
        startButton.background = roundedBackground(
            COLOR_ACCENT,
            14
        )

        startButton.setOnClickListener {
            requestVpn()
        }

        rootLayout.addView(
            startButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        stopButton = Button(this)
        stopButton.text = "ОСТАНОВИТЬ VPN"
        stopButton.textSize = 15f
        stopButton.setTextColor(Color.WHITE)
        stopButton.background = roundedBackground(
            COLOR_RED,
            14
        )

        stopButton.setOnClickListener {
            stopVpn()
        }

        rootLayout.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        setContentView(scrollView)

        updateModeDescription()
    }

    private fun loadServerData() {

        try {

            val jsonText = assets
                .open("server_data.json")
                .bufferedReader()
                .use {
                    it.readText()
                }

            val root = JSONObject(jsonText)

            val countryList = mutableListOf<Country>()

            val countriesObject = root.optJSONObject("countries")

            if (countriesObject != null) {

                val keys = countriesObject.keys()

                while (keys.hasNext()) {

                    val code = keys.next()
                    val countryObject = countriesObject.optJSONObject(code)

                    if (countryObject != null) {

                        val name = countryObject.optString(
                            "name",
                            code
                        )

                        val servers = mutableListOf<String>()

                        val serverArray =
                            countryObject.optJSONArray("servers")

                        if (serverArray != null) {

                            for (i in 0 until serverArray.length()) {

                                val value =
                                    serverArray.optString(i)

                                if (value.isNotBlank()) {
                                    servers.add(value)
                                }
                            }
                        }

                        countryList.add(
                            Country(
                                name = name,
                                code = code,
                                servers = servers
                            )
                        )
                    }
                }
            }

            countries = countryList.sortedBy {
                it.name.lowercase()
            }

            setupCountrySpinner()

        } catch (e: Exception) {

            statusText.text =
                "Ошибка загрузки server_data.json"

            Toast.makeText(
                this,
                "Ошибка: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupCountrySpinner() {

        val names = countries.map {
            it.name
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            names
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        countrySpinner.adapter = adapter

        countrySpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {

                    if (position >= countries.size) {
                        return
                    }

                    showServers(countries[position])
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                }
            }
    }

    private fun showServers(country: Country) {

        serversContainer.removeAllViews()

        selectedEntry = null

        statusText.text =
            "Выбрана страна: ${country.name}"

        val allCountryCard = createServerCard(
            title = "Вся страна",
            value = country.code,
            isSelected = false
        )

        allCountryCard.setOnClickListener {

            selectedEntry = country.code

            statusText.text =
                if (filterMode == FILTER_BLOCK) {
                    "Будет заблокирована вся страна: ${country.name}"
                } else {
                    "Будет разрешена вся страна: ${country.name}"
                }

            refreshServerCards(country)
        }

        serversContainer.addView(allCountryCard)

        for (server in country.servers) {

            val normalized = normalizeEntry(server)

            if (normalized == null) {
                continue
            }

            val serverCard = createServerCard(
                title = server,
                value = normalized,
                isSelected = false
            )

            serverCard.setOnClickListener {

                selectedEntry = normalized

                statusText.text =
                    if (filterMode == FILTER_BLOCK) {
                        "Будет заблокирован: $normalized"
                    } else {
                        "Будет разрешён: $normalized"
                    }

                refreshServerCards(country)
            }

            serversContainer.addView(serverCard)
        }

        updateModeDescription()
    }

    private fun refreshServerCards(country: Country) {

        for (i in 0 until serversContainer.childCount) {

            val view = serversContainer.getChildAt(i)

            if (view !is LinearLayout) {
                continue
            }

            val value = view.tag as? String ?: continue

            val isSelected =
                selectedEntry == value ||
                    (
                        value == country.code &&
                            selectedEntry == country.code
                        )

            view.background = roundedBackground(
                if (isSelected) {
                    COLOR_CARD_SELECTED
                } else {
                    COLOR_CARD
                },
                14
            )
        }
    }

    private fun createServerCard(
        title: String,
        value: String,
        isSelected: Boolean
    ): LinearLayout {

        val card = LinearLayout(this)

        card.orientation = LinearLayout.VERTICAL

        card.gravity = Gravity.CENTER_VERTICAL

        card.setPadding(
            dp(14),
            dp(12),
            dp(14),
            dp(12)
        )

        card.tag = value

        card.background = roundedBackground(
            if (isSelected) {
                COLOR_CARD_SELECTED
            } else {
                COLOR_CARD
            },
            14
        )

        val titleText = TextView(this)

        titleText.text = title

        titleText.textSize = 15f

        titleText.setTextColor(
            Color.WHITE
        )

        card.addView(
            titleText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (title != value) {

            val valueText = TextView(this)

            valueText.text = value

            valueText.textSize = 12f

            valueText.setTextColor(
                Color.parseColor(COLOR_SECONDARY)
            )

            valueText.setPadding(
                0,
                dp(4),
                0,
                0
            )

            card.addView(
                valueText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        card.layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }

        return card
    }

    private fun updateModeDescription() {

        val country = currentCountry()

        if (filterMode == FILTER_BLOCK) {

            modeDescription.text =
                "Выбранные IP будут блокироваться. " +
                    "Остальной трафик не блокируется."

        } else {

            modeDescription.text =
                "Выбранные IP будут разрешены. " +
                    "Остальные известные IP будут блокироваться."
        }

        if (selectedEntry != null) {

            val entry = selectedEntry!!

            statusText.text =
                if (filterMode == FILTER_BLOCK) {
                    "Блокировка: $entry"
                } else {
                    "Разрешение: $entry"
                }
        }
    }

    private fun currentCountry(): Country? {

        val position =
            countrySpinner.selectedItemPosition

        if (position < 0) {
            return null
        }

        if (position >= countries.size) {
            return null
        }

        return countries[position]
    }

    private fun requestVpn() {

        val country = currentCountry()

        if (country == null) {

            Toast.makeText(
                this,
                "Сначала выберите страну",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (selectedEntry == null) {

            Toast.makeText(
                this,
                "Выберите сервер или всю страну",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        pendingCountryCode = country.code
        pendingServerIp = selectedEntry
        pendingFilterMode = filterMode

        val prepareIntent =
            VpnService.prepare(this)

        if (prepareIntent != null) {

            startActivityForResult(
                prepareIntent,
                VPN_REQUEST_CODE
            )

        } else {

            startVpnService()
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

            startVpnService()

        } else {

            Toast.makeText(
                this,
                "Разрешение VPN не предоставлено",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startVpnService() {

        val intent = Intent(
            this,
            MlbbVpnService::class.java
        )

        intent.action =
            MlbbVpnService.ACTION_START

        intent.putExtra(
            MlbbVpnService.EXTRA_COUNTRY_CODE,
            pendingCountryCode
        )

        intent.putExtra(
            MlbbVpnService.EXTRA_SERVER_IP,
            pendingServerIp
        )

        intent.putExtra(
            MlbbVpnService.EXTRA_FILTER_MODE,
            pendingFilterMode ?: FILTER_ALLOW
        )

        startService(intent)

        statusText.text =
            if (filterMode == FILTER_BLOCK) {
                "VPN запущен • выбранное блокируется"
            } else {
                "VPN запущен • выбранное разрешается"
            }

        Toast.makeText(
            this,
            "VPN-фильтр запущен",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopVpn() {

        val intent = Intent(
            this,
            MlbbVpnService::class.java
        )

        intent.action =
            MlbbVpnService.ACTION_STOP

        startService(intent)

        statusText.text =
            "VPN остановлен"

        Toast.makeText(
            this,
            "VPN-фильтр остановлен",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun normalizeEntry(
        value: String
    ): String? {

        var result =
            value.trim()

        if (result.isEmpty()) {
            return null
        }

        if (result.contains("/")) {

            val parts =
                result.split("/")

            if (parts.size != 2) {
                return null
            }

            val ip = parts[0].trim()

            val prefix =
                parts[1].trim().toIntOrNull()

            if (!isValidIpv4(ip)) {
                return null
            }

            if (prefix == null ||
                prefix !in 0..32
            ) {
                return null
            }

            return "$ip/$prefix"
        }

        if (result.count {
                it == ':'
            } == 1
        ) {

            val colonIndex =
                result.lastIndexOf(':')

            val possibleIp =
                result.substring(
                    0,
                    colonIndex
                )

            val possiblePort =
                result.substring(
                    colonIndex + 1
                )

            if (isValidIpv4(possibleIp) &&
                possiblePort.toIntOrNull() != null
            ) {

                result = possibleIp
            }
        }

        if (!isValidIpv4(result)) {
            return null
        }

        return result
    }

    private fun isValidIpv4(
        value: String
    ): Boolean {

        val parts =
            value.split(".")

        if (parts.size != 4) {
            return false
        }

        for (part in parts) {

            val number =
                part.toIntOrNull()
                    ?: return false

            if (number !in 0..255) {
                return false
            }
        }

        return true
    }

    private fun isValidCidr(
        value: String
    ): Boolean {

        val parts =
            value.split("/")

        if (parts.size != 2) {
            return false
        }

        val ip =
            parts[0]

        val prefix =
            parts[1].toIntOrNull()
                ?: return false

        return isValidIpv4(ip) &&
            prefix in 0..32
    }

    private fun sectionTitle(
        text: String
    ): TextView {

        val view = TextView(this)

        view.text = text

        view.textSize = 12f

        view.setTextColor(
            Color.parseColor(COLOR_SECONDARY)
        )

        view.setTypeface(
            null,
            android.graphics.Typeface.BOLD
        )

        view.setPadding(
            dp(2),
            dp(4),
            dp(2),
            dp(8)
        )

        return view
    }

    private fun roundedBackground(
        color: String,
        radius: Int
    ): GradientDrawable {

        val drawable =
            GradientDrawable()

        drawable.setColor(
            Color.parseColor(color)
        )

        drawable.cornerRadius =
            dp(radius).toFloat()

        return drawable
    }

    private fun matchParams(
        bottom: Int = 0
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (bottom > 0) {
                bottomMargin = dp(bottom)
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