
package com.mlbb.serverpicker

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
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

    companion object {
        private const val VPN_REQUEST_CODE = 701
        private const val PREFS_NAME = "mlbb_filter_settings"
        private const val KEY_COUNTRY = "selected_country"
        private const val KEY_SERVER = "selected_server"
    }

    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView

    private val countries = mutableListOf<Country>()

    private var selectedCountryCode: String? = null
    private var selectedServerIp: String? = null
    private var vpnPermissionPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedCountryCode = prefs.getString(KEY_COUNTRY, null)
        selectedServerIp = prefs.getString(KEY_SERVER, null)

        loadDatabase()
        createInterface()
        showCountries()
    }

    private fun loadDatabase() {
        try {
            val text = assets.open("server_data.json")
                .bufferedReader()
                .use { it.readText() }

            val array = JSONObject(text).getJSONArray("countries")

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val serversJson = item.getJSONArray("servers")
                val servers = mutableListOf<String>()

                for (j in 0 until serversJson.length()) {
                    val raw = serversJson.getString(j)
                    val ip = raw.substringBeforeLast(":")

                    if (isValidIpv4(ip)) {
                        servers.add(ip)
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
        } catch (_: Exception) {
            countries.clear()
        }
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val title = TextView(this).apply {
            text = "MLBB Server Picker"
            textSize = 24f
            setTextColor(Color.rgb(25, 35, 50))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(45, 55, 70))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }

        val scroll = ScrollView(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(12))
        }

        scroll.addView(content)

        val startButton = Button(this).apply {
            text = "Запустить фильтр"
            isAllCaps = false
            setOnClickListener { requestVpnPermissionAndStart() }
        }

        val stopButton = Button(this).apply {
            text = "Остановить фильтр"
            isAllCaps = false
            setOnClickListener { stopFilter() }
        }

        root.addView(title)
        root.addView(
            statusText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(startButton)
        root.addView(stopButton)

        setContentView(root)
        updateStatus()
    }

    private fun showCountries() {
        content.removeAllViews()
        addHeading("Выбери страну или категорию")

        if (countries.isEmpty()) {
            addDescription(
                "Не удалось загрузить server_data.json. " +
                    "Проверь содержимое файла в app/src/main/assets/."
            )
            return
        }

        countries.forEach { country ->
            addItem(
                "${country.name} (${country.code})",
                "${country.servers.size} IP",
                selectedCountryCode == country.code &&
                    selectedServerIp == null
            ) {
                selectedCountryCode = country.code
                selectedServerIp = null
                saveSelection()
                showCountry(country)
                updateStatus()
            }
        }
    }

    private fun showCountry(country: Country) {
        content.removeAllViews()

        addItem("← Назад к странам", "", false) {
            showCountries()
        }

        addHeading(country.name)
        addDescription("IP-адресов: ${country.servers.size}")

        addItem(
            "Выбрать всю категорию",
            "Разрешить IP этой категории",
            selectedCountryCode == country.code &&
                selectedServerIp == null
        ) {
            selectedCountryCode = country.code
            selectedServerIp = null
            saveSelection()
            showCountry(country)
            updateStatus()
        }

        addHeading("Выбрать отдельный сервер")

        country.servers.forEachIndexed { index, ip ->
            addItem(
                "Сервер ${index + 1}",
                ip,
                selectedCountryCode == country.code &&
                    selectedServerIp == ip
            ) {
                selectedCountryCode = country.code
                selectedServerIp = ip
                saveSelection()
                showCountry(country)
                updateStatus()
            }
        }
    }

    private fun saveSelection() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_COUNTRY, selectedCountryCode)
            .putString(KEY_SERVER, selectedServerIp)
            .apply()
    }

    private fun requestVpnPermissionAndStart() {
        val countryCode = selectedCountryCode

        if (countryCode.isNullOrBlank()) {
            statusText.text = "Сначала выбери страну или категорию."
            return
        }

        val country = countries.find { it.code == countryCode }
        if (country == null) {
            statusText.text = "Выбранная категория отсутствует в базе."
            return
        }

        if (selectedServerIp != null &&
            selectedServerIp !in country.servers
        ) {
            statusText.text = "Выбранный IP отсутствует в этой категории."
            return
        }

        val permissionIntent = VpnService.prepare(this)

        if (permissionIntent != null) {
            vpnPermissionPending = true
            @Suppress("DEPRECATION")
            startActivityForResult(
                permissionIntent,
                VPN_REQUEST_CODE
            )
        } else {
            startFilterService()
        }
    }

    @Deprecated("Uses Android VPN permission activity result")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE && vpnPermissionPending) {
            vpnPermissionPending = false

            if (resultCode == Activity.RESULT_OK) {
                startFilterService()
            } else {
                statusText.text =
                    "Разрешение VPN не предоставлено. Фильтр не запущен."
            }
        }
    }

    private fun startFilterService() {
        val countryCode = selectedCountryCode

        if (countryCode.isNullOrBlank()) {
            statusText.text = "Сначала выбери страну."
            return
        }

        val intent = Intent(this, MlbbVpnService::class.java).apply {
            action = MlbbVpnService.ACTION_START
            putExtra(
                MlbbVpnService.EXTRA_COUNTRY_CODE,
                countryCode
            )
            putExtra(
                MlbbVpnService.EXTRA_SERVER_IP,
                selectedServerIp
            )
        }

        try {
            startService(intent)
            statusText.text =
                "Команда запуска отправлена.\n" +
                    "Категория: $countryCode\n" +
                    "Сервер: ${selectedServerIp ?: "вся категория"}\n" +
                    "Проверь работу фильтра перед запуском игры."
        } catch (e: Exception) {
            statusText.text =
                "Не удалось запустить фильтр: ${e.message}"
        }
    }

    private fun stopFilter() {
        val intent = Intent(this, MlbbVpnService::class.java).apply {
            action = MlbbVpnService.ACTION_STOP
        }

        try {
            startService(intent)
            statusText.text = "Отправлена команда остановки фильтра."
        } catch (e: Exception) {
            statusText.text =
                "Не удалось остановить фильтр: ${e.message}"
        }
    }

    private fun updateStatus() {
        val country = countries.find {
            it.code == selectedCountryCode
        }

        statusText.text = when {
            country == null ->
                "Страна не выбрана. Выбери её в списке ниже."

            selectedServerIp != null ->
                "Выбрано: ${country.name}\n" +
                    "IP сервера: $selectedServerIp"

            else ->
                "Выбрана категория: ${country.name}\n" +
                    "IP в категории: ${country.servers.size}"
        }
    }

    private fun addHeading(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(25, 35, 50))
            setPadding(0, dp(16), 0, dp(8))
        })
    }

    private fun addDescription(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(85, 95, 110))
            setPadding(0, dp(4), 0, dp(10))
        })
    }

    private fun addItem(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val button = Button(this).apply {
            text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            isAllCaps = false
            setPadding(dp(10), dp(6), dp(10), dp(6))

            if (selected) {
                setTextColor(Color.rgb(0, 100, 65))
                setBackgroundColor(Color.rgb(210, 245, 225))
            } else {
                setTextColor(Color.rgb(30, 40, 55))
            }

            setOnClickListener { onClick() }
        }

        content.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(5)
            }
        )
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all {
            val number = it.toIntOrNull()
            number != null && number in 0..255 && it.isNotEmpty()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
