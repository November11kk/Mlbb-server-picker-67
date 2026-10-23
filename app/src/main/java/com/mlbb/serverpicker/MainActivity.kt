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

class MainActivity : Activity() {

    companion object {
        private const val VPN_REQUEST_CODE = 1001
        private const val FILTER_ALLOW = "allow"
        private const val FILTER_BLOCK = "block"

        private const val BG = "#0B0F14"
        private const val CARD = "#151B23"
        private const val SELECTED = "#202A36"
        private const val GREEN = "#4CAF50"
        private const val RED = "#F44336"
        private const val WHITE = "#FFFFFF"
        private const val GRAY = "#AAB4C0"
    }

    private data class Country(
        val name: String,
        val code: String,
        val entries: List<ServerDatabase.ServerEntry>
    )

    private lateinit var database: ServerDatabase
    private lateinit var countrySpinner: Spinner
    private lateinit var serversContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var modeDescription: TextView

    private var countries: List<Country> = emptyList()
    private var selectedEntry: String? = null
    private var filterMode = FILTER_ALLOW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = ServerDatabase(applicationContext)

        buildInterface()
        loadServerData()
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        scroll.addView(root)

        root.addView(
            TextView(this).apply {
                text = "MLBB SERVER PICKER"
                textSize = 24f
                setTextColor(Color.parseColor(WHITE))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            },
            params(height = 60)
        )

        root.addView(
            TextView(this).apply {
                text = "Выбор и фильтрация записей серверов"
                textSize = 14f
                setTextColor(Color.parseColor(GRAY))
                gravity = Gravity.CENTER
            },
            params(bottom = 16)
        )

        root.addView(section("РЕЖИМ ФИЛЬТРА"))

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(CARD)
        }

        val allow = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Разрешить выбранное"
            setTextColor(Color.parseColor(WHITE))
        }

        val block = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Заблокировать выбранное"
            setTextColor(Color.parseColor(WHITE))
        }

        radioGroup.addView(allow)
        radioGroup.addView(block)

        allow.isChecked = true

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            filterMode = if (checkedId == block.id) {
                FILTER_BLOCK
            } else {
                FILTER_ALLOW
            }

            updateModeDescription()

            countries
                .getOrNull(countrySpinner.selectedItemPosition)
                ?.let { showServers(it) }
        }

        root.addView(
            radioGroup,
            params(bottom = 8)
        )

        modeDescription = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor(GRAY))
            setPadding(dp(4), dp(8), dp(4), dp(12))
        }

        root.addView(modeDescription)

        root.addView(section("СТРАНА"))

        countrySpinner = Spinner(this).apply {
            background = rounded(CARD)
        }

        root.addView(
            countrySpinner,
            params(height = 52, bottom = 14)
        )

        root.addView(section("ВЫБРАННАЯ ЗАПИСЬ"))

        statusText = TextView(this).apply {
            text = "Загрузка SQLite базы..."
            textSize = 14f
            setTextColor(Color.parseColor(GRAY))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(CARD)
        }

        root.addView(
            statusText,
            params(height = 56, bottom = 14)
        )

        root.addView(section("ЗАПИСИ СТРАНЫ"))

        serversContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            serversContainer,
            params(bottom = 14)
        )

        root.addView(
            Button(this).apply {
                text = "ЗАПУСТИТЬ ФИЛЬТР"
                setTextColor(Color.parseColor(WHITE))
                background = rounded(GREEN)
                setOnClickListener {
                    requestVpn()
                }
            },
            params(height = 54, bottom = 8)
        )

        root.addView(
            Button(this).apply {
                text = "ОСТАНОВИТЬ VPN"
                setTextColor(Color.parseColor(WHITE))
                background = rounded(RED)
                setOnClickListener {
                    stopVpn()
                }
            },
            params(height = 54)
        )

        setContentView(scroll)

        updateModeDescription()
    }

    private fun loadServerData() {
        try {
            val allActiveEntries =
                database.getAllEntries(includeDisabled = false)

            val entriesByCountry =
                allActiveEntries.groupBy { it.countryCode }

            countries = database
                .getCountries()
                .map { country ->

                    Country(
                        name = country.second,
                        code = country.first,
                        entries = entriesByCountry[country.first]
                            ?: emptyList()
                    )
                }
                .sortedBy {
                    it.name.lowercase()
                }

            if (countries.isEmpty()) {
                statusText.text = "SQLite база пуста"

                Toast.makeText(
                    this,
                    "В SQLite базе нет стран.",
                    Toast.LENGTH_LONG
                ).show()

                return
            }

            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                countries.map { it.name }
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
                        countries
                            .getOrNull(position)
                            ?.let { showServers(it) }
                    }

                    override fun onNothingSelected(
                        parent: AdapterView<*>?
                    ) = Unit
                }

            statusText.text =
                "SQLite: ${countries.size} стран, " +
                "${
                    allActiveEntries.size
                } активных записей"

            Toast.makeText(
                this,
                "SQLite загружена: ${countries.size} стран",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            statusText.text = "Ошибка загрузки SQLite"

            Toast.makeText(
                this,
                "Ошибка базы данных: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showServers(country: Country) {

        serversContainer.removeAllViews()

        selectedEntry = null

        statusText.text =
            "Выбрана страна: ${country.name}"

        addServerCard(
            title = "Вся страна (${country.entries.size} записей)",
            value = country.code,
            selected = false
        )

        country.entries.forEach { entry ->

            val label = buildString {

                append(entry.type)
                append(" • ")
                append(entry.address)

                entry.port?.let {
                    append(":")
                    append(it)
                }

                append(" • ")
                append(entry.protocol)

                append(" • ")
                append(entry.action)
            }

            addServerCard(
                title = label,
                value = entry.address,
                selected = false
            )
        }

        updateModeDescription()
    }

    private fun addServerCard(
        title: String,
        value: String,
        selected: Boolean
    ) {

        val card = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
            )

            background = rounded(
                if (selected) {
                    SELECTED
                } else {
                    CARD
                }
            )

            tag = value

            isClickable = true
            isFocusable = true
        }

        card.addView(
            TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(Color.parseColor(WHITE))
            }
        )

        card.setOnClickListener {

            selectedEntry = value

            updateSelectedCard()

            val selectedCountry =
                countries.getOrNull(
                    countrySpinner.selectedItemPosition
                )

            statusText.text =
                if (selectedCountry != null &&
                    value == selectedCountry.code
                ) {

                    "Выбрана вся страна: ${selectedCountry.name}"

                } else {

                    "Выбрано: $value"
                }

            updateModeDescription()
        }

        serversContainer.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
    }

    private fun updateSelectedCard() {

        for (i in 0 until serversContainer.childCount) {

            val card =
                serversContainer.getChildAt(i)

            val value =
                card.tag as? String
                    ?: continue

            card.background = rounded(
                if (value == selectedEntry) {
                    SELECTED
                } else {
                    CARD
                }
            )
        }
    }

    private fun updateModeDescription() {

        modeDescription.text =
            if (filterMode == FILTER_ALLOW) {

                "Режим разрешения: выбери страну " +
                "или отдельную запись."

            } else {

                "Режим блокировки: выбери страну " +
                "или отдельную запись."
            }
    }

    private fun requestVpn() {

        val country =
            countries.getOrNull(
                countrySpinner.selectedItemPosition
            )

        if (country == null) {

            Toast.makeText(
                this,
                "Нет доступных стран",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        if (selectedEntry == null) {

            Toast.makeText(
                this,
                "Сначала выбери «Вся страна» " +
                    "или отдельную запись",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        val prepareIntent =
            VpnService.prepare(this)

        if (prepareIntent != null) {

            @Suppress("DEPRECATION")
            startActivityForResult(
                prepareIntent,
                VPN_REQUEST_CODE
            )

        } else {

            startVpn(
                country.code,
                selectedEntry!!,
                filterMode
            )
        }
    }

    @Deprecated(
        "Deprecated by Android API; retained for compatibility"
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

        if (
            requestCode == VPN_REQUEST_CODE &&
            resultCode == RESULT_OK
        ) {

            val country =
                countries.getOrNull(
                    countrySpinner.selectedItemPosition
                )

            val entry = selectedEntry

            if (
                country != null &&
                entry != null
            ) {

                startVpn(
                    country.code,
                    entry,
                    filterMode
                )
            }

        } else if (
            requestCode == VPN_REQUEST_CODE
        ) {

            Toast.makeText(
                this,
                "Разрешение VPN не предоставлено",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startVpn(
        countryCode: String,
        entry: String,
        mode: String
    ) {

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
                    MlbbVpnService.EXTRA_SERVER_ENTRY,
                    entry
                )

                putExtra(
                    MlbbVpnService.EXTRA_FILTER_MODE,
                    mode
                )
            }

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {

            startForegroundService(intent)

        } else {

            startService(intent)
        }

        Toast.makeText(
            this,
            "Команда запуска VPN отправлена",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stopVpn() {

        startService(
            Intent(
                this,
                MlbbVpnService::class.java
            ).apply {
                action =
                    MlbbVpnService.ACTION_STOP
            }
        )

        Toast.makeText(
            this,
            "Команда остановки VPN отправлена",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun section(text: String) =
        TextView(this).apply {

            this.text = text

            textSize = 13f

            typeface =
                Typeface.DEFAULT_BOLD

            setTextColor(
                Color.parseColor(GRAY)
            )

            setPadding(
                0,
                dp(8),
                0,
                dp(8)
            )
        }

    private fun params(
        height: Int =
            LinearLayout.LayoutParams.WRAP_CONTENT,
        bottom: Int = 0
    ) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (
                height ==
                LinearLayout.LayoutParams.WRAP_CONTENT
            ) {
                height
            } else {
                dp(height)
            }
        ).apply {
            bottomMargin = dp(bottom)
        }

    private fun rounded(
        color: String
    ) =
        GradientDrawable().apply {

            setColor(
                Color.parseColor(color)
            )

            cornerRadius =
                dp(12).toFloat()
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()

    override fun onDestroy() {

        if (::database.isInitialized) {
            database.close()
        }

        super.onDestroy()
    }
}