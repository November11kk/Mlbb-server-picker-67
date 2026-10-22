
package com.mlbb.serverpicker

import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private data class Country(
        val name: String,
        val code: String,
        val servers: List<String>
    )

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView

    private val countries = mutableListOf<Country>()

    private var openedCountry: Country? = null
    private var selectedCountryCode: String? = null
    private var selectedServer: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadDatabase()
        createBaseInterface()
        showCountries()
    }

    private fun loadDatabase() {
        try {
            val jsonText = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(jsonText)
            val array = json.getJSONArray("countries")

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val serversJson = item.getJSONArray("servers")
                val servers = mutableListOf<String>()

                for (j in 0 until serversJson.length()) {
                    servers.add(serversJson.getString(j))
                }

                countries.add(
                    Country(
                        name = item.getString("name"),
                        code = item.getString("code"),
                        servers = servers
                    )
                )
            }
        } catch (e: Exception) {
            countries.clear()
        }
    }

    private fun createBaseInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 25f
            setTextColor(Color.rgb(25, 35, 50))
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(55, 65, 80))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        val scrollView = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(16))
        }

        scrollView.addView(content)

        root.addView(title)
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
        updateStatus()
    }

    private fun showCountries() {
        openedCountry = null
        content.removeAllViews()

        addHeading("Выбери страну")
        addDescription(
            "Всего стран и категорий: ${countries.size}"
        )

        if (countries.isEmpty()) {
            addDescription(
                "Не удалось загрузить базу IP. Проверь файл " +
                    "app/src/main/assets/server_data.json"
            )
            return
        }

        countries.forEach { country ->
            val selected = selectedCountryCode == country.code &&
                selectedServer == null

            addItemButton(
                title = "${country.name} (${country.code})",
                subtitle = "${country.servers.size} серверов",
                selected = selected
            ) {
                showCountry(country)
            }
        }
    }

    private fun showCountry(country: Country) {
        openedCountry = country
        content.removeAllViews()

        addItemButton(
            title = "← Назад к странам",
            subtitle = "",
            selected = false
        ) {
            showCountries()
        }

        addHeading(country.name)
        addDescription(
            "Код: ${country.code} · Серверов: ${country.servers.size}"
        )

        addItemButton(
            title = "Выбрать всю категорию",
            subtitle = "Разрешённый набор: все IP этой категории",
            selected = selectedCountryCode == country.code &&
                selectedServer == null
        ) {
            selectedCountryCode = country.code
            selectedServer = null
            updateStatus()
            showCountry(country)
        }

        addHeading("Серверы")

        country.servers.forEachIndexed { index, server ->
            val ip = server.substringBeforeLast(":")

            addItemButton(
                title = "Сервер ${index + 1}",
                subtitle = server,
                selected = selectedCountryCode == country.code &&
                    selectedServer == ip
            ) {
                selectedCountryCode = country.code
                selectedServer = ip
                updateStatus()
                showCountry(country)
            }
        }
    }

    private fun updateStatus() {
        val country = countries.find {
            it.code == selectedCountryCode
        }

        statusText.text = when {
            country == null ->
                "Выбор не сделан. VPN-фильтрация пока не запущена."

            selectedServer != null ->
                "Выбрано: ${country.name}\n" +
                    "Сервер: $selectedServer\n" +
                    "Выбор сохранён только на экране; фильтр ещё не активен."

            else ->
                "Выбрана категория: ${country.name}\n" +
                    "IP в категории: ${country.servers.size}\n" +
                    "Выбор сохранён только на экране; фильтр ещё не активен."
        }
    }

    private fun addHeading(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(25, 35, 50))
            setPadding(0, dp(18), 0, dp(8))
        }
        content.addView(view)
    }

    private fun addDescription(text: String) {
        val view = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(90, 100, 115))
            setPadding(0, dp(4), 0, dp(12))
        }
        content.addView(view)
    }

    private fun addItemButton(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val button = Button(this).apply {
            text = if (subtitle.isBlank()) {
                title
            } else {
                "$title\n$subtitle"
            }

            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            isAllCaps = false
            setPadding(dp(12), dp(8), dp(12), dp(8))

            if (selected) {
                setTextColor(Color.rgb(0, 100, 65))
                setBackgroundColor(Color.rgb(210, 245, 225))
            } else {
                setTextColor(Color.rgb(30, 40, 55))
            }

            setOnClickListener { onClick() }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = dp(6)

        content.addView(button, params)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
