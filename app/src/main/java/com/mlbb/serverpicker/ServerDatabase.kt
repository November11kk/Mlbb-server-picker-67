
package com.mlbb.serverpicker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress

class ServerDatabase(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    data class Country(
        val name: String,
        val code: String
    )

    data class ServerEntry(
        val id: Long,
        val countryCode: String,
        val type: String,
        val address: String,
        val port: Int?,
        val protocol: String,
        val action: String,
        val enabled: Boolean,
        val note: String,
        val rawValue: String
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE countries (
                code TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE server_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                country_code TEXT NOT NULL,
                type TEXT NOT NULL,
                address TEXT NOT NULL,
                port INTEGER,
                protocol TEXT NOT NULL DEFAULT 'ANY',
                action TEXT NOT NULL DEFAULT 'ALLOW',
                enabled INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                raw_value TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(country_code)
                    REFERENCES countries(code) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_entries_country ON server_entries(country_code)"
        )
        db.execSQL(
            "CREATE INDEX idx_entries_type ON server_entries(type)"
        )

        importLegacyJson(db)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // Новые версии базы будут добавляться через миграции.
        // Существующие записи не удаляем.
    }

    fun getCountries(): List<Country> {
        val result = mutableListOf<Country>()

        readableDatabase.rawQuery(
            "SELECT name, code FROM countries ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Country(
                        name = cursor.getString(0),
                        code = cursor.getString(1)
                    )
                )
            }
        }

        return result
    }

    fun getEntries(
        countryCode: String,
        includeDisabled: Boolean = true
    ): List<ServerEntry> {
        val result = mutableListOf<ServerEntry>()

        val sql = """
            SELECT id, country_code, type, address, port,
                   protocol, action, enabled, note, raw_value
            FROM server_entries
            WHERE country_code = ?
            ${if (includeDisabled) "" else "AND enabled = 1"}
            ORDER BY id
        """.trimIndent()

        readableDatabase.rawQuery(
            sql,
            arrayOf(countryCode)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.toServerEntry())
            }
        }

        return result
    }

    fun getAllEntries(
        includeDisabled: Boolean = true
    ): List<ServerEntry> {
        val result = mutableListOf<ServerEntry>()

        val sql = """
            SELECT id, country_code, type, address, port,
                   protocol, action, enabled, note, raw_value
            FROM server_entries
            ${if (includeDisabled) "" else "WHERE enabled = 1"}
            ORDER BY country_code, id
        """.trimIndent()

        readableDatabase.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.toServerEntry())
            }
        }

        return result
    }

    fun addEntry(
        countryCode: String,
        type: String,
        address: String,
        port: Int? = null,
        protocol: String = "ANY",
        action: String = "ALLOW",
        enabled: Boolean = true,
        note: String = ""
    ): Long {
        require(type in TYPES) {
            "Неизвестный тип записи: $type"
        }
        require(protocol in PROTOCOLS) {
            "Неизвестный протокол: $protocol"
        }
        require(action in ACTIONS) {
            "Неизвестное действие: $action"
        }
        require(port == null || port in 1..65535) {
            "Порт должен быть от 1 до 65535"
        }
        require(isValidAddress(type, address)) {
            "Адрес не соответствует типу $type"
        }

        val values = ContentValues().apply {
            put("country_code", countryCode)
            put("type", type)
            put("address", address.trim())

            if (port == null) {
                putNull("port")
            } else {
                put("port", port)
            }

            put("protocol", protocol)
            put("action", action)
            put("enabled", if (enabled) 1 else 0)
            put("note", note)
            put("raw_value", "")
        }

        return writableDatabase.insertOrThrow(
            "server_entries",
            null,
            values
        )
    }

    fun setEntryEnabled(
        id: Long,
        enabled: Boolean
    ): Boolean {
        val values = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
        }

        return writableDatabase.update(
            "server_entries",
            values,
            "id = ?",
            arrayOf(id.toString())
        ) > 0
    }

    fun setEntryAction(
        id: Long,
        action: String
    ): Boolean {
        require(action in ACTIONS)

        val values = ContentValues().apply {
            put("action", action)
        }

        return writableDatabase.update(
            "server_entries",
            values,
            "id = ?",
            arrayOf(id.toString())
        ) > 0
    }

    fun deleteEntry(id: Long): Boolean {
        return writableDatabase.delete(
            "server_entries",
            "id = ?",
            arrayOf(id.toString())
        ) > 0
    }

    private fun importLegacyJson(db: SQLiteDatabase): Int {
        val jsonText = try {
            contextAssetsText()
        } catch (_: Exception) {
            return 0
        }

        val countries = try {
            JSONObject(jsonText).optJSONArray("countries")
        } catch (_: Exception) {
            null
        } ?: return 0

        var imported = 0

        db.beginTransaction()

        try {
            for (i in 0 until countries.length()) {
                val country = countries.optJSONObject(i) ?: continue
                val name = country.optString("name").trim()
                val code = country.optString("code").trim()

                if (name.isEmpty() || code.isEmpty()) continue

                db.insertWithOnConflict(
                    "countries",
                    null,
                    ContentValues().apply {
                        put("name", name)
                        put("code", code)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                val servers = country.optJSONArray("servers") ?: continue

                for (j in 0 until servers.length()) {
                    val raw = servers.optString(j).trim()
                    val parsed = parseLegacyEntry(raw) ?: continue

                    val values = ContentValues().apply {
                        put("country_code", code)
                        put("type", parsed.type)
                        put("address", parsed.address)

                        if (parsed.port == null) {
                            putNull("port")
                        } else {
                            put("port", parsed.port)
                        }

                        put("protocol", "ANY")
                        put("action", "ALLOW")
                        put("enabled", 1)
                        put("note", "Импортировано из server_data.json")
                        put("raw_value", raw)
                    }

                    db.insert("server_entries", null, values)
                    imported++
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        return imported
    }

    private fun contextAssetsText(): String {
        return appContext.assets
            .open("server_data.json")
            .bufferedReader()
            .use { it.readText() }
    }

    private val appContext: Context
        get() = context

    private data class ParsedEntry(
        val type: String,
        val address: String,
        val port: Int?
    )

    private fun parseLegacyEntry(raw: String): ParsedEntry? {
        if (raw.isBlank()) return null

        val value = raw.trim()

        if (value.contains('/')) {
            val parts = value.split('/')
            if (parts.size != 2) return null

            val address = parts[0]
            val prefix = parts[1].toIntOrNull() ?: return null

            return when {
                isIpv4(address) && prefix in 0..32 ->
                    ParsedEntry("IPV4_CIDR", value, null)

                isIpv6(address) && prefix in 0..128 ->
                    ParsedEntry("IPV6_CIDR", value, null)

                else -> null
            }
        }

        val bracketedIpv6Port =
            Regex("""^\[([^\]]+)]:(\d+)$""").matchEntire(value)

        if (bracketedIpv6Port != null) {
            val address = bracketedIpv6Port.groupValues[1]
            val port = bracketedIpv6Port.groupValues[2].toIntOrNull()

            if (!isIpv6(address) || port !in 1..65535) return null

            return ParsedEntry("IPV6", address, port)
        }

        if (isIpv6(value)) {
            return ParsedEntry("IPV6", value, null)
        }

        val ipv4Port =
            Regex("""^(.+):(\d+)$""").matchEntire(value)

        if (ipv4Port != null && isIpv4(ipv4Port.groupValues[1])) {
            val number = ipv4Port.groupValues[2].toIntOrNull()
                ?: return null

            // Старые записи вида IP:0 используют ноль как заглушку.
            val port = if (number == 0) null else number

            if (port != null && port !in 1..65535) return null

            return ParsedEntry(
                "IPV4",
                ipv4Port.groupValues[1],
                port
            )
        }

        if (isIpv4(value)) {
            return ParsedEntry("IPV4", value, null)
        }

        if (isValidHostname(value)) {
            return ParsedEntry("DOMAIN", value, null)
        }

        return null
    }

    private fun Cursor.toServerEntry(): ServerEntry {
        val portIndex = getColumnIndexOrThrow("port")

        return ServerEntry(
            id = getLong(getColumnIndexOrThrow("id")),
            countryCode = getString(getColumnIndexOrThrow("country_code")),
            type = getString(getColumnIndexOrThrow("type")),
            address = getString(getColumnIndexOrThrow("address")),
            port = if (isNull(portIndex)) null else getInt(portIndex),
            protocol = getString(getColumnIndexOrThrow("protocol")),
            action = getString(getColumnIndexOrThrow("action")),
            enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
            note = getString(getColumnIndexOrThrow("note")),
            rawValue = getString(getColumnIndexOrThrow("raw_value"))
        )
    }

    private fun isValidAddress(
        type: String,
        address: String
    ): Boolean {
        val value = address.trim()

        return when (type) {
            "IPV4" -> isIpv4(value)
            "IPV4_CIDR" -> {
                val parts = value.split('/')
                parts.size == 2 &&
                    isIpv4(parts[0]) &&
                    (parts[1].toIntOrNull() in 0..32)
            }
            "IPV6" -> isIpv6(value)
            "IPV6_CIDR" -> {
                val parts = value.split('/')
                parts.size == 2 &&
                    isIpv6(parts[0]) &&
                    (parts[1].toIntOrNull() in 0..128)
            }
            "DOMAIN", "HOST" -> isValidHostname(value)
            else -> false
        }
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false

        return parts.all {
            val number = it.toIntOrNull()
            it.isNotEmpty() && number != null && number in 0..255
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (!value.contains(':') || value.contains('/') || value.contains('%')) {
            return false
        }

        return try {
            InetAddress.getByName(value) is Inet6Address
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidHostname(value: String): Boolean {
        if (value.length !in 1..253) return false
        if (value.contains("://") || value.contains('/') || value.contains(':')) {
            return false
        }

        return value.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    companion object {
        const val DATABASE_NAME = "server_data.db"
        const val DATABASE_VERSION = 1

        val TYPES = setOf(
            "IPV4",
            "IPV4_CIDR",
            "IPV6",
            "IPV6_CIDR",
            "DOMAIN",
            "HOST"
        )

        val PROTOCOLS = setOf("TCP", "UDP", "ANY")
        val ACTIONS = setOf("ALLOW", "BLOCK")
    }
}
