package com.example.tymap.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedPlace(
    val id: Long = 0,
    val title: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val category: String = CATEGORY_FAVORITE, // HOME, WORK, FAVORITE, RECENT
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CATEGORY_HOME = "HOME"
        const val CATEGORY_WORK = "WORK"
        const val CATEGORY_FAVORITE = "FAVORITE"
        const val CATEGORY_RECENT = "RECENT"
    }
}

class SavedPlaceDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "tymap_places.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_PLACES = "saved_places"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_ADDRESS = "address"
        const val COL_LAT = "lat"
        const val COL_LON = "lon"
        const val COL_CATEGORY = "category"
        const val COL_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_PLACES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TITLE TEXT NOT NULL,
                $COL_ADDRESS TEXT NOT NULL,
                $COL_LAT REAL NOT NULL,
                $COL_LON REAL NOT NULL,
                $COL_CATEGORY TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PLACES")
        onCreate(db)
    }

    fun insertPlace(place: SavedPlace): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, place.title)
            put(COL_ADDRESS, place.address)
            put(COL_LAT, place.lat)
            put(COL_LON, place.lon)
            put(COL_CATEGORY, place.category)
            put(COL_CREATED_AT, place.createdAt)
        }
        return db.insert(TABLE_PLACES, null, values)
    }

    fun updatePlace(place: SavedPlace): Int {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, place.title)
            put(COL_ADDRESS, place.address)
            put(COL_LAT, place.lat)
            put(COL_LON, place.lon)
            put(COL_CATEGORY, place.category)
        }
        return db.update(TABLE_PLACES, values, "$COL_ID = ?", arrayOf(place.id.toString()))
    }

    fun deletePlace(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_PLACES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun searchPlaces(query: String): List<SavedPlace> {
        val list = mutableListOf<SavedPlace>()
        val db = readableDatabase
        val pattern = "%${query.trim()}%"
        val cursor = db.rawQuery(
            "SELECT * FROM $TABLE_PLACES WHERE $COL_TITLE LIKE ? OR $COL_ADDRESS LIKE ? ORDER BY $COL_CREATED_AT DESC LIMIT 10",
            arrayOf(pattern, pattern)
        )
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TITLE))
                val address = it.getString(it.getColumnIndexOrThrow(COL_ADDRESS))
                val lat = it.getDouble(it.getColumnIndexOrThrow(COL_LAT))
                val lon = it.getDouble(it.getColumnIndexOrThrow(COL_LON))
                val cat = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY))
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT))
                list.add(SavedPlace(id, title, address, lat, lon, cat, createdAt))
            }
        }
        return list
    }

    fun getAllPlaces(): List<SavedPlace> {
        val list = mutableListOf<SavedPlace>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PLACES ORDER BY $COL_CREATED_AT DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TITLE))
                val address = it.getString(it.getColumnIndexOrThrow(COL_ADDRESS))
                val lat = it.getDouble(it.getColumnIndexOrThrow(COL_LAT))
                val lon = it.getDouble(it.getColumnIndexOrThrow(COL_LON))
                val cat = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY))
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT))
                list.add(SavedPlace(id, title, address, lat, lon, cat, createdAt))
            }
        }
        return list
    }

    fun getPlaceByCategory(category: String): SavedPlace? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_PLACES WHERE $COL_CATEGORY = ? LIMIT 1", arrayOf(category))
        cursor.use {
            if (it.moveToNext()) {
                val id = it.getLong(it.getColumnIndexOrThrow(COL_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TITLE))
                val address = it.getString(it.getColumnIndexOrThrow(COL_ADDRESS))
                val lat = it.getDouble(it.getColumnIndexOrThrow(COL_LAT))
                val lon = it.getDouble(it.getColumnIndexOrThrow(COL_LON))
                val cat = it.getString(it.getColumnIndexOrThrow(COL_CATEGORY))
                val createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT))
                return SavedPlace(id, title, address, lat, lon, cat, createdAt)
            }
        }
        return null
    }

    fun saveOrUpdateCategory(category: String, title: String, address: String, lat: Double, lon: Double): Long {
        val existing = getPlaceByCategory(category)
        return if (existing != null) {
            updatePlace(existing.copy(title = title, address = address, lat = lat, lon = lon))
            existing.id
        } else {
            insertPlace(SavedPlace(title = title, address = address, lat = lat, lon = lon, category = category))
        }
    }
}
