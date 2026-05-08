package com.example.syncar

import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repositorio: Encapsula toda la lógica de acceso a datos (SQLite).
 * Sigue el principio de Responsabilidad Única.
 */
class SynCarRepository(private val dbHelper: DatabaseHelper) {

    fun login(user: String, pass: String): Int? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT id FROM usuarios WHERE username=? AND password=?", arrayOf(user, pass))
        return if (cursor.moveToFirst()) {
            val id = cursor.getInt(0)
            cursor.close()
            id
        } else {
            cursor.close()
            null
        }
    }

    fun crearNuevoTrayecto(userId: Int): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("usuario_id", userId)
            put("inicio", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }
        return db.insert("sesiones", null, values)
    }

    fun finalizarTrayecto(journeyId: Long, duracion: Long, tempMedia: Float, distMin: Float) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("fin", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("temp_media", if (tempMedia.isNaN()) 0f else tempMedia)
            put("dist_min", distMin)
            put("duracion_seg", duracion)
        }
        db.update("sesiones", values, "id = ?", arrayOf(journeyId.toString()))
    }

    fun guardarTelemetría(journeyId: Long, tipo: String, valor: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("sesion_id", journeyId)
            put("tipo", tipo)
            put("valor", valor)
        }
        db.insert("datos", null, values)
    }

    fun obtenerHistorialTelemetriaReciente(): List<String> {
        val list = mutableListOf<String>()
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT tipo, valor FROM datos ORDER BY id DESC LIMIT 15", null)
        while (c.moveToNext()) list.add("${c.getString(0)}: ${c.getString(1)}")
        c.close()
        return list
    }

    fun obtenerPuntosTemperaturaGrafica(): List<Float> {
        val puntos = mutableListOf<Float>()
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT valor FROM datos WHERE tipo='TEMP' ORDER BY id DESC LIMIT 20", null)
        while (c.moveToNext()) {
            c.getString(0).replace(",", ".").toFloatOrNull()?.let { puntos.add(it) }
        }
        c.close()
        return puntos.reversed()
    }

    fun obtenerListaTrayectos(): List<Trayecto> {
        val lista = mutableListOf<Trayecto>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT id, duracion_seg, temp_media, dist_min FROM sesiones WHERE fin IS NOT NULL ORDER BY id DESC", null)
        while (cursor.moveToNext()) {
            lista.add(Trayecto(cursor.getInt(0), cursor.getInt(1), cursor.getFloat(2), cursor.getFloat(3)))
        }
        cursor.close()
        return lista
    }

    fun obtenerDetalleGraficaViaje(journeyId: Int): List<Float> {
        val puntos = mutableListOf<Float>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT valor FROM datos WHERE sesion_id = ? AND tipo = 'TEMP' ORDER BY id ASC", arrayOf(journeyId.toString()))
        while (cursor.moveToNext()) {
            cursor.getString(0).replace(",", ".").toFloatOrNull()?.let { puntos.add(it) }
        }
        cursor.close()
        return puntos
    }
}