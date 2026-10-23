
package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        scrollView.addView(rootLayout)

        val title = TextView(this).apply {
            text = "MLBB SERVER PICKER"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }

        rootLayout.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60)
            )
        )

        val subtitle = TextView(this).apply {
            text = "Выбор и блокировка IP-серверов Mobile Legends"
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_SECONDARY))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }

        rootLayout.addView(subtitle, matchParams())

        rootLayout.addView(sectionTitle("РЕЖИМ ФИЛЬТРА"))

        val modeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(COLOR_CARD, 16)
        }

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val allowRadio = RadioButton(this).apply {
            text = "Разрешить выбранное"
            textSize = 16f
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }

        val blockRadio = RadioButton(this).apply {
            text = "Заблокировать выбранное"
            textSize = 16f
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }

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

        modeCard.addView(modeGroup, matchParams())

        modeDescription = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(COLOR_SECONDARY))
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }

        modeCard.addView(modeDescription, matchParams())

        rootLayout.addView(
            modeCard,
            matchParams(14)
        )

        rootLayout.addView(sectionTitle("СТРАНА"))

        countrySpinner = Spinner(this).apply {
            background = roundedBackground(COLOR_CARD, 12)
        }

        rootLayout.addView(
            countrySpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        rootLayout.addView(sectionTitle("ВЫБРАННЫЙ СЕРВЕР"))

        statusText = TextView(this).apply {
            text = "Загрузка списка..."
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_SECONDARY))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(COLOR_CARD, 12)
        }

        rootLayout.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(14)
            }
        )

        rootLayout.addView(sectionTitle("СЕРВЕРЫ"))

        serversContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        rootLayout.addView(serversContainer, matchParams(14))

        val startButton = Button(this).apply {
            text = "ЗАПУСТИТЬ ФИЛЬТР"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_ACCENT, 14)
            setOnClickListener {
                requestVpn()
            }
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

        val stopButton = Button(this).apply {
            text = "ОСТАНОВИТЬ VPN"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_RED, 14)
            setOnClickListener {
                stopVpn()
            }
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
            val jsonText = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(jsonText)
            val countriesArray = root.optJSONArray("countries")
                ?: throw IllegalStateException(
                    "Массив countries не найден"
                )

            val countryList = mutableListOf<Country>()

            for (i in 0 until countriesArray.length()) {
                val countryObject = countriesArray.optJSONObject(i)
                    ?: continue

                val name = countryObject.optString("name", "")
                val code = countryObject.optString("code", "")
                val servers = mutableListOf<String>()
                val serverArray = countryObject.optJSONArray("servers")

                if (serverArray != null) {
                    for (j in 0 until serverArray.length()) {
                        val server = serverArray.optString(j)
                        if (server.isNotBlank()) {
                            servers.add(server)
                        }
                    }
                }

                if (name.isNotBlank() && code.isNotBlank()) {
                    countryList.add(
                        Country(
                            name = name,
                            code = code,
                            servers = servers
                        )
                    )
                }
            }

            countries = countryList.sortedBy {
                it.name.lowercase()
            }

            if (countries.isEmpty()) {
                throw IllegalStateException(
                    "В server_data.json нет стран"
                )
            }

            setupCountrySpinner()
            statusText.text = "Загружено стран: ${countries.size}"

        } catch (e: Exception) {
            statusText.text = "Ошибка загрузки списка"
            Toast.makeText(
                this,
                "Ошибка server_data.json: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setupCountrySpinner() {
        val names = countries.map { it.name }

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
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position !in countries.indices) return
                    showServers(countries[position])
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) = Unit
            }

        if (countries.isNotEmpty()) {
            countrySpinner.setSelection(0)
            showServers(countries[0])
        }
    }

    private fun showServers(country: Country) {
        serversContainer.removeAllViews()
        selectedEntry = null
        statusText.text = "Выбрана страна: ${country.name}"

        val allCountryCard = createServerCard(
            title = "Вся страна",
            value = country.code,
            isSelected = false
        )

        allCountryCard.setOnClickListener {
            selectedEntry = country.code
            statusText.text = if (filterMode == FILTER_BLOCK) {
                "Будет заблокирована вся страна: ${country.name}"
            } else {
                "Будет разрешена вся страна: ${country.name}"
            }
            refreshServerCards()
        }

        serversContainer.addView(allCountryCard)

        for (server in country.servers) {
            val normalized = normalizeEntry(server) ?: continue

            val serverCard = createServerCard(
                title = server,
                value = normalized,
                isSelected = false
            )

            serverCard.setOnClickListener {
                selectedEntry = normalized
                statusText.text = if (filterMode == FILTER_BLOCK) {
                    "Будет заблокирован: $normalized"
                } else {
                    "Будет разрешён: $normalized"
                }
                refreshServerCards()
            }

            serversContainer.addView(serverCard)
        }

        updateModeDescription()
    }

    private fun refreshServerCards() {
        for (i in 0 until serversContainer.childCount) {
            val view = serversContainer.getChildAt(i)
            if (view !is LinearLayout) continue

            val value = view.tag as? String ?: continue

            view.background = roundedBackground(
                if (selectedEntry == value) {
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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            tag = value
            background = roundedBackground(
                if (isSelected) COLOR_CARD_SELECTED else COLOR_CARD,
                14
            )
        }

        val titleText = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor(COLOR_TEXT))
        }

        card.addView(titleText, matchParams())

        if (title != value) {
            val valueText = TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_SECONDARY))
                setPadding(0, dp(4), 0, 0)
            }

            card.addView(valueText, matchParams())
        }

        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(8)
        }

        return card
    }

    private fun updateModeDescription() {
        modeDescription.text = if (filterMode == FILTER_BLOCK) {
            "Выбранные IP будут блокироваться. Остальной трафик не блокируется."
        } else {
            "Выбранные IP будут разрешены. Остальные известные IP будут блокироваться."
        }

        val entry = selectedEntry
        if (entry != null) {
            statusText.text = if (filterMode == FILTER_BLOCK) {
                "Блокировка: $entry"
            } else {
                "Разрешение: $entry"
            }
        }
    }

    private fun currentCountry(): Country? {
        val position = countrySpinner.selectedItemPosition
        if (position !in countries.indices) return null
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

        val entry = selectedEntry

        if (entry == null) {
            Toast.makeText(
                this,
                "Выберите сервер или всю страну",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        pendingCountryCode = country.code
        pendingServerIp = entry
        pendingFilterMode = filterMode

        val prepareIntent = VpnService.prepare(this)

        if (prepareIntent != null) {
            startActivityForResult(
                prepareIntent,
                VPN_REQUEST_CODE
            )
        } else {
            startVpnService()
        }
    }

    @Deprecated("Используется для совместимости с запросом разрешения VPN")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != VPN_REQUEST_CODE) return

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
        val intent = Intent(this, MlbbVpnService::class.java).apply {
            action = MlbbVpnService.ACTION_START

            putExtra(
                MlbbVpnService.EXTRA_COUNTRY_CODE,
                pendingCountryCode
            )

            putExtra(
                MlbbVpnService.EXTRA_SERVER_IP,
                pendingServerIp
            )

            putExtra(
                MlbbVpnService.EXTRA_FILTER_MODE,
                pendingFilterMode ?: FILTER_ALLOW
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            statusText.text = "Команда запуска VPN отправлена"

            Toast.makeText(
                this,
                "Команда запуска VPN отправлена",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            statusText.text = "Не удалось запустить VPN"

            Toast.makeText(
                this,
                "Ошибка запуска VPN: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopVpn() {
        val intent = Intent(this, MlbbVpnService::class.java).apply {
            action = MlbbVpnService.ACTION_STOP
        }

        try {
            startService(intent)
            statusText.text = "Отправлена команда остановки VPN"

            Toast.makeText(
                this,
                "Команда остановки VPN отправлена",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {
            statusText.text = "Не удалось остановить VPN"

            Toast.makeText(
                this,
                "Ошибка остановки VPN: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun normalizeEntry(value: String): String? {
        var result = value.trim()
        if (result.isEmpty()) return null

        if (result.contains("/")) {
            val parts = result.split("/")
            if (parts.size != 2) return null

            val ip = parts[0].trim()
            val prefix = parts[1].trim().toIntOrNull()

            if (!isValidIpv4(ip)) return null
            if (prefix == null || prefix !in 0..32) return null

            return "$ip/$prefix"
        }

        if (result.count { it == ':' } == 1) {
            val colonIndex = result.lastIndexOf(':')
            val possibleIp = result.substring(0, colonIndex)
            val possiblePort = result.substring(colonIndex + 1)

            if (
                isValidIpv4(possibleIp) &&
                possiblePort.toIntOrNull() in 0..65535
            ) {
                result = possibleIp
            }
        }

        if (!isValidIpv4(result)) return null
        return result
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false

        for (part in parts) {
            val number = part.toIntOrNull() ?: return false
            if (number !in 0..255) return false
        }

        return true
    }

    private fun isValidCidr(value: String): Boolean {
        val parts = value.split("/")
        if (parts.size != 2) return false

        val ip = parts[0]
        val prefix = parts[1].toIntOrNull() ?: return false

        return isValidIpv4(ip) && prefix in 0..32
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor(COLOR_SECONDARY))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
    }

    private fun roundedBackground(
        color: String,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun matchParams(bottom: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (bottom > 0) {
                bottomMargin = dp(bottom)
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }
}
