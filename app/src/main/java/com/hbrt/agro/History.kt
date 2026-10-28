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

    private class Helper(c: Context) : SQLiteOpenHelper(c, "hbrt.db", null, 2) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE readings(ts INTEGER PRIMARY KEY, t REAL, h REAL, s INTEGER, st REAL, w INTEGER, " +
                    "fan INTEGER, pump INTEGER, lights INTEGER, heater INTEGER, vent INTEGER)"
            )
            d.execSQL("CREATE TABLE alerts(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, msg TEXT)")
            onUpgrade(d, 1, 2)
        }

        override fun onUpgrade(d: SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) d.execSQL(
                "CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, board TEXT, ts INTEGER, c TEXT, d TEXT, byw TEXT, w TEXT, " +
                    "a TEXT, m TEXT, st TEXT, v INTEGER, x REAL)"
            )
        }
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

    /** Adds [samples] only where the phone has nothing within 2 minutes (e.g. points from the cloud log). */
    fun fillGaps(samples: List<Sample>) {
        if (samples.isEmpty()) return
        db.beginTransaction()
        try {
            samples.forEach { s ->
                val near = db.rawQuery(
                    "SELECT 1 FROM readings WHERE ts BETWEEN ? AND ? LIMIT 1",
                    arrayOf((s.ts - 120_000).toString(), (s.ts + 120_000).toString())
                ).use { it.moveToFirst() }
                if (!near) add(s)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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
        db.delete("events", "ts<?", arrayOf(olderThan.toString()))
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

    /** Keeps the board's timeline on the phone, so it goes further back than the board's last 40 events. */
    fun addEvents(board: String, list: List<Ev>) {
        if (!::db.isInitialized) return
        db.beginTransaction()
        try {
            list.filter { it.t > 0 }.forEach { e ->
                val v = ContentValues().apply {
                    put("id", "$board:${e.t}:${e.c}:${e.d}:${e.v}"); put("board", board); put("ts", e.t); put("c", e.c); put("d", e.d)
                    put("byw", e.by); put("w", e.w); put("a", e.a); put("m", e.m); put("st", e.st); put("v", e.v); put("x", e.x)
                }
                db.insertWithOnConflict("events", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(board: String, limit: Int = 300): List<Ev> {
        val out = ArrayList<Ev>()
        db.rawQuery("SELECT ts,c,d,byw,w,a,m,st,v,x FROM events WHERE board=? ORDER BY ts DESC LIMIT $limit", arrayOf(board)).use { c ->
            while (c.moveToNext()) out += Ev(c.getLong(0), c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getInt(8), c.getFloat(9))
        }
        return out
    }
}
