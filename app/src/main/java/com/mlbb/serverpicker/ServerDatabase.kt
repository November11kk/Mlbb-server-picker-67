package com.mlbb.serverpicker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress

class ServerDatabase(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val DATABASE_NAME = "server_data.db"
        private const val DATABASE_VERSION = 1
        private const val ASSET_DATABASE_NAME = "server_data.db"
    }

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

    private val helper = object : SQLiteOpenHelper(
        appContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            /*
             * Обычно сюда приложение не попадёт,
             * потому что готовая база копируется из assets
             * до открытия SQLite.
             *
             * Но оставляем создание схемы как резервный вариант.
             */
            createSchema(db)
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            /*
             * Сейчас структура базы версии 1 уже готова.
             * При будущих изменениях версии здесь можно
             * добавить миграции.
             */
        }
    }

    init {
        ensureDatabaseCopied()
    }

    /**
     * Копирует готовую SQLite-базу:
     *
     * app/src/main/assets/server_data.db
     *
     * во внутреннее хранилище приложения:
     *
     * /data/data/com.mlbb.serverpicker/databases/server_data.db
     *
     * Копирование выполняется только если локальной базы ещё нет.
     */
    private fun ensureDatabaseCopied() {
        val databaseFile = appContext.getDatabasePath(DATABASE_NAME)

        if (databaseFile.exists() && databaseFile.length() > 0L) {
            return
        }

        databaseFile.parentFile?.mkdirs()

        val temporaryFile = File(
            databaseFile.parentFile,
            "$DATABASE_NAME.tmp"
        )

        if (temporaryFile.exists()) {
            temporaryFile.delete()
        }

        try {
            appContext.assets.open(ASSET_DATABASE_NAME).use { input ->
                FileOutputStream(temporaryFile).use { output ->

                    val buffer = ByteArray(8192)

                    while (true) {
                        val count = input.read(buffer)

                        if (count == -1) {
                            break
                        }

                        output.write(buffer, 0, count)
                    }

                    output.flush()
                }
            }

            if (!temporaryFile.renameTo(databaseFile)) {
                temporaryFile.copyTo(
                    databaseFile,
                    overwrite = true
                )
                temporaryFile.delete()
            }

        } catch (e: Exception) {
            temporaryFile.delete()

            throw IllegalStateException(
                "Не удалось загрузить server_data.db из assets",
                e
            )
        }
    }

    private fun createSchema(db: SQLiteDatabase) {

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS countries (
                code TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS server_entries (
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
                    REFERENCES countries(code)
                    ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_server_entries_country
            ON server_entries(country_code)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            idx_server_entries_type
            ON server_entries(type)
            """.trimIndent()
        )
    }

    /**
     * Получить список всех стран.
     */
    fun getCountries(): List<Pair<String, String>> {

        val db = helper.readableDatabase

        val result = mutableListOf<Pair<String, String>>()

        db.query(
            "countries",
            arrayOf("code", "name"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC"
        ).use { cursor ->

            val codeIndex = cursor.getColumnIndexOrThrow("code")
            val nameIndex = cursor.getColumnIndexOrThrow("name")

            while (cursor.moveToNext()) {
                result.add(
                    cursor.getString(codeIndex) to
                        cursor.getString(nameIndex)
                )
            }
        }

        return result
    }

    /**
     * Получить активные записи определённой страны.
     */
    fun getEntries(
        countryCode: String,
        includeDisabled: Boolean = false
    ): List<ServerEntry> {

        val db = helper.readableDatabase

        val selection: String
        val selectionArgs: Array<String>

        if (includeDisabled) {
            selection = "country_code = ?"
            selectionArgs = arrayOf(countryCode)
        } else {
            selection = "country_code = ? AND enabled = 1"
            selectionArgs = arrayOf(countryCode)
        }

        return queryEntries(
            db = db,
            selection = selection,
            selectionArgs = selectionArgs
        )
    }

    /**
     * Получить все записи всех стран.
     */
    fun getAllEntries(
        includeDisabled: Boolean = false
    ): List<ServerEntry> {

        val db = helper.readableDatabase

        val selection: String?
        val selectionArgs: Array<String>?

        if (includeDisabled) {
            selection = null
            selectionArgs = null
        } else {
            selection = "enabled = 1"
            selectionArgs = null
        }

        return queryEntries(
            db = db,
            selection = selection,
            selectionArgs = selectionArgs
        )
    }

    private fun queryEntries(
        db: SQLiteDatabase,
        selection: String?,
        selectionArgs: Array<String>?
    ): List<ServerEntry> {

        val result = mutableListOf<ServerEntry>()

        db.query(
            "server_entries",
            arrayOf(
                "id",
                "country_code",
                "type",
                "address",
                "port",
                "protocol",
                "action",
                "enabled",
                "note",
                "raw_value"
            ),
            selection,
            selectionArgs,
            null,
            null,
            "country_code ASC, type ASC, address ASC"
        ).use { cursor ->

            val idIndex = cursor.getColumnIndexOrThrow("id")
            val countryIndex =
                cursor.getColumnIndexOrThrow("country_code")
            val typeIndex =
                cursor.getColumnIndexOrThrow("type")
            val addressIndex =
                cursor.getColumnIndexOrThrow("address")
            val portIndex =
                cursor.getColumnIndexOrThrow("port")
            val protocolIndex =
                cursor.getColumnIndexOrThrow("protocol")
            val actionIndex =
                cursor.getColumnIndexOrThrow("action")
            val enabledIndex =
                cursor.getColumnIndexOrThrow("enabled")
            val noteIndex =
                cursor.getColumnIndexOrThrow("note")
            val rawValueIndex =
                cursor.getColumnIndexOrThrow("raw_value")

            while (cursor.moveToNext()) {

                val port =
                    if (cursor.isNull(portIndex)) {
                        null
                    } else {
                        cursor.getInt(portIndex)
                    }

                result.add(
                    ServerEntry(
                        id = cursor.getLong(idIndex),
                        countryCode = cursor.getString(countryIndex),
                        type = cursor.getString(typeIndex),
                        address = cursor.getString(addressIndex),
                        port = port,
                        protocol = cursor.getString(protocolIndex),
                        action = cursor.getString(actionIndex),
                        enabled = cursor.getInt(enabledIndex) != 0,
                        note = cursor.getString(noteIndex),
                        rawValue = cursor.getString(rawValueIndex)
                    )
                )
            }
        }

        return result
    }

    /**
     * Добавить новую запись.
     */
    fun addEntry(
        countryCode: String,
        type: String,
        address: String,
        port: Int? = null,
        protocol: String = "ANY",
        action: String = "ALLOW",
        enabled: Boolean = true,
        note: String = "",
        rawValue: String = address
    ): Long {

        val db = helper.writableDatabase

        val values = ContentValues().apply {
            put("country_code", countryCode)
            put("type", type)
            put("address", address)

            if (port == null) {
                putNull("port")
            } else {
                put("port", port)
            }

            put("protocol", protocol)
            put("action", action)
            put("enabled", if (enabled) 1 else 0)
            put("note", note)
            put("raw_value", rawValue)
        }

        return db.insertOrThrow(
            "server_entries",
            null,
            values
        )
    }

    /**
     * Включить/выключить конкретную запись.
     */
    fun setEntryEnabled(
        id: Long,
        enabled: Boolean
    ) {

        val db = helper.writableDatabase

        val values = ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
        }

        db.update(
            "server_entries",
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Изменить действие записи:
     * ALLOW / BLOCK.
     */
    fun setEntryAction(
        id: Long,
        action: String
    ) {

        val db = helper.writableDatabase

        val values = ContentValues().apply {
            put("action", action)
        }

        db.update(
            "server_entries",
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Удалить запись.
     */
    fun deleteEntry(id: Long) {

        val db = helper.writableDatabase

        db.delete(
            "server_entries",
            "id = ?",
            arrayOf(id.toString())
        )
    }

    /**
     * Проверка IP-адреса.
     *
     * Оставлена здесь как вспомогательная функция,
     * если она понадобится другим частям приложения.
     */
    fun isIpAddress(value: String): Boolean {
        return try {
            InetAddress.getByName(value)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Закрыть подключение к базе.
     */
    fun close() {
        helper.close()
    }
}