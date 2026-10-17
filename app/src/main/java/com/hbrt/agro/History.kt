package com.hbrt.agro

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Long-term history in SQLite on the phone (survives app restarts; kept for 6 months). */
object History {
    private lateinit var db: SQLiteDatabase

    fun init(c: Context) {
        db = Helper(c).writableDatabase
    }

    private class Helper(c: Context) : SQLiteOpenHelper(c, "hbrt.db", null, 1) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE readings(ts INTEGER PRIMARY KEY, t REAL, h REAL, s INTEGER, st REAL, w INTEGER, " +
                    "fan INTEGER, pump INTEGER, lights INTEGER, heater INTEGER, vent INTEGER)"
            )
            d.execSQL("CREATE TABLE alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, msg TEXT)")
        }

        override fun onUpgrade(d: SQLiteDatabase, oldV: Int, newV: Int) {}
    }

    private fun Boolean.i() = if (this) 1 else 0

    fun add(s: Sample) {
        val v = ContentValues().apply {
            put("ts", s.ts); put("t", s.t); put("h", s.h); put("s", s.s); put("st", s.st); put("w", s.w)
            put("fan", s.fan.i()); put("pump", s.pump.i()); put("lights", s.lights.i())
            put("heater", s.heater.i()); put("vent", s.vent.i())
        }
        db.insertWithOnConflict("readings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun since(from: Long): List<Sample> {
        val out = ArrayList<Sample>()
        db.rawQuery(
            "SELECT ts,t,h,s,st,w,fan,pump,lights,heater,vent FROM readings WHERE ts>=? ORDER BY ts",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Sample(
                    c.getLong(0), c.getFloat(1), c.getFloat(2), c.getInt(3), c.getFloat(4), c.getInt(5),
                    c.getInt(6) == 1, c.getInt(7) == 1, c.getInt(8) == 1, c.getInt(9) == 1, c.getInt(10) == 1
                )
            }
        }
        return out
    }

    fun prune(olderThan: Long) {
        db.delete("readings", "ts<?", arrayOf(olderThan.toString()))
        db.delete("alerts", "ts<?", arrayOf(olderThan.toString()))
    }

    fun logAlert(msg: String) {
        db.insert("alerts", null, ContentValues().apply { put("ts", System.currentTimeMillis()); put("msg", msg) })
    }

    fun alerts(limit: Int = 100): List<AlertEntry> {
        val out = ArrayList<AlertEntry>()
        db.rawQuery("SELECT ts,msg FROM alerts ORDER BY ts DESC LIMIT $limit", null).use { c ->
            while (c.moveToNext()) out += AlertEntry(c.getLong(0), c.getString(1))
        }
        return out
    }

    fun clearAlerts() {
        db.delete("alerts", null, null)
    }
}
