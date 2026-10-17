package com.hbrt.agro

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CSV and PDF reports, built natively (no libraries). */
object Exporter {
    private const val CREDIT = "HBRT AGRO  |  Developed by Mostafa Mesbah Aabedi & Milad Ahmadi"

    private fun dir(c: Context) = File(c.cacheDir, "exports").apply { mkdirs() }
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    private fun Boolean.i() = if (this) 1 else 0

    fun csv(c: Context, data: List<Sample>): File {
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val f = File(dir(c), "HBRT_${stamp()}.csv")
        f.bufferedWriter().use { w ->
            w.write("﻿") // BOM so Excel opens it as UTF-8
            w.write("Time,Air Temp (C),Humidity (%),Soil Moisture (%),Soil Temp (C),Water Tank (%),Fan,Pump,Lights,Heater,Vent\n")
            for (s in data) {
                w.write(
                    String.format(
                        Locale.US, "%s,%.1f,%.1f,%d,%.1f,%d,%d,%d,%d,%d,%d\n",
                        tf.format(Date(s.ts)), s.t, s.h, s.s, s.st, s.w,
                        s.fan.i(), s.pump.i(), s.lights.i(), s.heater.i(), s.vent.i()
                    )
                )
            }
        }
        return f
    }

    fun pdf(c: Context, data: List<Sample>, rangeLabel: String): File {
        val W = 595f
        val H = 842f
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
        var cv: Canvas = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        fun footer() {
            p.typeface = Typeface.DEFAULT; p.textSize = 9f; p.color = 0xFF888888.toInt(); p.textAlign = Paint.Align.CENTER
            cv.drawText("$CREDIT  |  page $pageNo", W / 2, H - 20, p)
            p.textAlign = Paint.Align.LEFT
        }

        // Header
        p.color = 0xFF0B1410.toInt(); cv.drawRect(0f, 0f, W, 90f, p)
        p.color = 0xFF39FF14.toInt(); p.textSize = 24f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        cv.drawText("HBRT GREENHOUSE REPORT", W / 2, 48f, p)
        p.color = 0xFFE0E0E0.toInt(); p.textSize = 11f; p.typeface = Typeface.DEFAULT
        val span = if (data.isEmpty()) "" else "${df.format(Date(data.first().ts))} to ${df.format(Date(data.last().ts))}"
        cv.drawText("$rangeLabel  |  $span  |  ${data.size} samples", W / 2, 72f, p)
        p.textAlign = Paint.Align.LEFT

        // Summary table
        var y = 125f
        p.color = 0xFF000000.toInt(); p.textSize = 14f; p.typeface = Typeface.DEFAULT_BOLD
        cv.drawText("Summary", 40f, y, p); y += 22
        p.textSize = 11f
        val cols = floatArrayOf(40f, 230f, 320f, 410f)
        listOf("Sensor", "Min", "Average", "Max").forEachIndexed { i, s -> cv.drawText(s, cols[i], y, p) }
        y += 6; p.strokeWidth = 0.8f; cv.drawLine(40f, y, W - 40, y, p); y += 16
        p.typeface = Typeface.DEFAULT
        val metrics = listOf(
            "Air temperature (°C)" to data.map { it.t },
            "Humidity (%)" to data.map { it.h },
            "Soil moisture (%)" to data.map { it.s.toFloat() },
            "Soil temperature (°C)" to data.map { it.st },
            "Water tank (%)" to data.map { it.w.toFloat() },
        )
        for ((name, vals) in metrics) {
            val s = vals.stat() ?: continue
            cv.drawText(name, cols[0], y, p)
            cv.drawText(s.min.f1(), cols[1], y, p)
            cv.drawText(s.avg.f1(), cols[2], y, p)
            cv.drawText(s.max.f1(), cols[3], y, p)
            y += 17
        }
        if (data.isNotEmpty()) {
            fun pct(sel: (Sample) -> Boolean) = data.count(sel) * 100 / data.size
            y += 6
            cv.drawText(
                "Time switched on:  Fan ${pct { it.fan }}%   Pump ${pct { it.pump }}%   Heater ${pct { it.heater }}%   " +
                    "Lights ${pct { it.lights }}%   Vent open ${pct { it.vent }}%",
                40f, y, p
            )
            y += 10
        }

        // Chart
        val chart = RectF(60f, y + 20, W - 40, y + 250)
        drawChart(cv, chart, data.downsample(500), p)
        y = chart.bottom + 45

        // Table of readings (reduced to 400 rows so the PDF stays small; CSV has everything)
        val rows = data.downsample(400)
        val tc = floatArrayOf(40f, 160f, 215f, 270f, 325f, 385f, 440f, 495f)
        val heads = listOf("Time", "Temp", "Hum", "Soil", "S.Temp", "Tank", "Fan", "Pump")
        fun tableHead() {
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 10f; p.color = 0xFF000000.toInt()
            heads.forEachIndexed { i, h -> cv.drawText(h, tc[i], y, p) }
            y += 5; cv.drawLine(40f, y, W - 40, y, p); y += 13
            p.typeface = Typeface.DEFAULT
        }
        p.textSize = 14f; p.typeface = Typeface.DEFAULT_BOLD; p.color = 0xFF000000.toInt()
        cv.drawText("Readings" + if (rows.size < data.size) " (every ${data.size / rows.size}th sample)" else "", 40f, y, p)
        y += 20
        tableHead()
        for (r in rows) {
            if (y > H - 45) {
                footer(); doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageNo).create())
                cv = page.canvas
                y = 50f
                tableHead()
            }
            p.textSize = 9.5f; p.color = 0xFF222222.toInt()
            val cells = listOf(
                df.format(Date(r.ts)), r.t.f1(), r.h.f1(), "${r.s}", r.st.f1(), "${r.w}",
                if (r.fan) "ON" else "-", if (r.pump) "ON" else "-"
            )
            cells.forEachIndexed { i, s -> cv.drawText(s, tc[i], y, p) }
            y += 13
        }
        footer()
        doc.finishPage(page)

        val f = File(dir(c), "HBRT_Report_${stamp()}.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun drawChart(cv: Canvas, r: RectF, data: List<Sample>, p: Paint) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f; p.color = 0xFFCCCCCC.toInt()
        cv.drawRect(r, p)
        p.style = Paint.Style.FILL
        if (data.size < 2) return
        val series = listOf(
            Triple("Temp °C", 0xFFFF8C00.toInt(), data.map { it.t }),
            Triple("Humidity %", 0xFF1E9E1E.toInt(), data.map { it.h }),
            Triple("Soil %", 0xFF2E8B57.toInt(), data.map { it.s.toFloat() }),
            Triple("Tank %", 0xFF0088DD.toInt(), data.map { it.w.toFloat() }),
            Triple("Soil temp °C", 0xFFC9A400.toInt(), data.map { it.st }),
        )
        val all = series.flatMap { it.third }
        val lo = minOf(0f, all.min())
        val hi = maxOf(100f, all.max())
        p.textSize = 8f; p.color = 0xFF666666.toInt(); p.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4
            val yy = r.bottom - r.height() * i / 4
            p.color = 0xFFEEEEEE.toInt(); cv.drawLine(r.left, yy, r.right, yy, p)
            p.color = 0xFF666666.toInt(); cv.drawText(String.format(Locale.US, "%.0f", v), r.left - 4, yy + 3, p)
        }
        p.textAlign = Paint.Align.LEFT
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f
        for ((_, color, vals) in series) {
            val path = Path()
            vals.forEachIndexed { i, v ->
                val x = r.left + r.width() * i / (vals.size - 1)
                val yy = r.bottom - (v - lo) / (hi - lo) * r.height()
                if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
            }
            p.color = color
            cv.drawPath(path, p)
        }
        p.style = Paint.Style.FILL; p.textSize = 9f
        var x = r.left
        for ((name, color, _) in series) {
            p.color = color; cv.drawRect(x, r.bottom + 12, x + 12, r.bottom + 16, p)
            p.color = 0xFF333333.toInt(); cv.drawText(name, x + 16, r.bottom + 17, p)
            x += 16 + p.measureText(name) + 18
        }
    }

    /** Opens the Android share sheet (WhatsApp, email, Drive, ...). */
    fun share(c: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(send, "Share ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a file to a location the user picked with the system "Save as" dialog. */
    fun copyTo(c: Context, f: File, uri: Uri): Boolean = try {
        c.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        true
    } catch (e: Exception) {
        false
    }

    /** Saves into Downloads/HBRT without any permission (Android 10+). Used by the daily report. */
    fun saveToDownloads(c: Context, f: File, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HBRT")
        }
        val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
        return copyTo(c, f, uri)
    }
}
