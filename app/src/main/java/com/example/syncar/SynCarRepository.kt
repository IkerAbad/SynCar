package com.example.syncar

import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repositorio: Encapsula toda la lógica de acceso a datos (SQLite).
 */
class SynCarRepository(private val dbHelper: DatabaseHelper) {

    fun login(user: String, pass: String): Usuario? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT id, username FROM usuarios WHERE username=? AND password=?", arrayOf(user, pass))
        return if (cursor.moveToFirst()) {
            val usuario = Usuario(cursor.getInt(0), cursor.getString(1))
            cursor.close()
            usuario
        } else {
            cursor.close()
            null
        }
    }

    fun obtenerUsuarios(): List<Usuario> {
        val list = mutableListOf<Usuario>()
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT id, username FROM usuarios", null)
        while (c.moveToNext()) {
            list.add(Usuario(c.getInt(0), c.getString(1)))
        }
        c.close()
        return list
    }

    fun crearUsuario(username: String, pass: String): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("username", username)
            put("password", pass)
        }
        return db.insert("usuarios", null, values)
    }

    fun actualizarUsuario(id: Int, username: String, pass: String?) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("username", username)
            if (pass != null) put("password", pass)
        }
        db.update("usuarios", values, "id=?", arrayOf(id.toString()))
    }

    fun borrarUsuario(id: Int) {
        val db = dbHelper.writableDatabase
        db.delete("usuarios", "id=?", arrayOf(id.toString()))
        // También borrar sus trayectos asociados
        val cursor = db.rawQuery("SELECT id FROM sesiones WHERE usuario_id=?", arrayOf(id.toString()))
        while (cursor.moveToNext()) {
            borrarTrayecto(cursor.getInt(0))
        }
        cursor.close()
    }

    fun borrarTrayecto(journeyId: Int) {
        val db = dbHelper.writableDatabase
        db.delete("sesiones", "id=?", arrayOf(journeyId.toString()))
        db.delete("datos", "sesion_id=?", arrayOf(journeyId.toString()))
        db.delete("ubicaciones", "sesion_id=?", arrayOf(journeyId.toString()))
        db.delete("trip_sensor_data", "viaje_id=?", arrayOf(journeyId.toString()))
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
            put("dist_min", if (distMin.isNaN()) 0f else distMin)
            put("duracion_seg", duracion)
        }
        db.update("sesiones", values, "id = ?", arrayOf(journeyId.toString()))
    }

    fun guardarTelemetria(journeyId: Long, tipo: String, valor: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("sesion_id", journeyId)
            put("tipo", tipo)
            put("valor", valor)
        }
        db.insert("datos", null, values)
    }

    fun guardarUbicacion(journeyId: Long, lat: Double, lon: Double) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("sesion_id", journeyId)
            put("latitud", lat)
            put("longitud", lon)
        }
        db.insert("ubicaciones", null, values)
    }

    fun obtenerTrayectoPorId(journeyId: Int, userId: Int): TrayectoFull? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("""
            SELECT id, inicio, fin, duracion_seg, temp_media, dist_min 
            FROM sesiones WHERE id = ? AND usuario_id = ?
        """, arrayOf(journeyId.toString(), userId.toString()))
        return if (cursor.moveToFirst()) {
            val t = TrayectoFull(
                id = cursor.getInt(0),
                inicio = cursor.getString(1),
                fin = cursor.getString(2) ?: "---",
                duracion = cursor.getInt(3),
                tempMedia = cursor.getFloat(4),
                distMin = cursor.getFloat(5)
            )
            cursor.close()
            t
        } else {
            cursor.close()
            null
        }
    }

    fun obtenerEstadisticasExtendido(journeyId: Int): StatsExtendido {
        val db = dbHelper.readableDatabase
        val stats = StatsExtendido()
        
        val c = db.rawQuery("""
            SELECT 
                MIN(temperatura), MAX(temperatura), AVG(temperatura), 
                MIN(humedad), MAX(humedad), AVG(humedad),
                MIN(distancia)
            FROM trip_sensor_data WHERE viaje_id = ?
        """, arrayOf(journeyId.toString()))
        
        if (c.moveToFirst()) {
            if (!c.isNull(0)) {
                stats.minTemp = c.getFloat(0)
                stats.maxTemp = c.getFloat(1)
                stats.avgTemp = c.getFloat(2)
                stats.minHum = c.getFloat(3)
                stats.maxHum = c.getFloat(4)
                stats.avgHum = c.getFloat(5)
                stats.minDist = c.getFloat(6)
            }
        }
        c.close()
        return stats
    }

    fun obtenerListaTrayectos(usuarioId: Int): List<Trayecto> {
        val lista = mutableListOf<Trayecto>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT id, duracion_seg, temp_media, dist_min, inicio FROM sesiones WHERE usuario_id = ? AND fin IS NOT NULL ORDER BY id DESC", arrayOf(usuarioId.toString()))
        while (cursor.moveToNext()) {
            lista.add(Trayecto(cursor.getInt(0), cursor.getInt(1), cursor.getFloat(2), cursor.getFloat(3), cursor.getString(4)))
        }
        cursor.close()
        return lista
    }

    fun obtenerPuntosTelemetria(journeyId: Int, tipo: String): List<Float> {
        val puntos = mutableListOf<Float>()
        val db = dbHelper.readableDatabase
        val column = when(tipo.uppercase()) {
            "TEMP" -> "temperatura"
            "HUM" -> "humedad"
            "DIST" -> "distancia"
            else -> "temperatura"
        }
        val cursor = db.rawQuery("SELECT $column FROM trip_sensor_data WHERE viaje_id = ? ORDER BY id ASC", arrayOf(journeyId.toString()))
        while (cursor.moveToNext()) {
            puntos.add(cursor.getFloat(0))
        }
        cursor.close()
        return puntos
    }

    fun obtenerRutaGps(journeyId: Int): List<Pair<Double, Double>> {
        val ruta = mutableListOf<Pair<Double, Double>>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT latitud, longitud FROM ubicaciones WHERE sesion_id = ? ORDER BY id ASC", arrayOf(journeyId.toString()))
        while (cursor.moveToNext()) {
            ruta.add(Pair(cursor.getDouble(0), cursor.getDouble(1)))
        }
        cursor.close()
        return ruta
    }

    fun guardarSnapshot(viajeId: Long, temp: Float, hum: Float, dist: Float, lat: Double, lon: Double) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("viaje_id", viajeId)
                put("temperatura", temp)
                put("humedad", hum)
                put("distancia", dist)
                put("latitud", lat)
                put("longitud", lon)
            }
            val id = db.insert("trip_sensor_data", null, values)
            if (id != -1L) {
                android.util.Log.d("SynCarSQL", "Snapshot INSERT exitoso: ID=$id para Viaje=$viajeId")
            } else {
                android.util.Log.e("SynCarSQL", "Error al insertar snapshot para Viaje=$viajeId")
            }
        } catch (e: Exception) {
            android.util.Log.e("SynCarSQL", "Excepción en guardarSnapshot: ${e.message}")
        }
    }

    fun obtenerSnapshots(viajeId: Int): List<TripSnapshot> {
        val lista = mutableListOf<TripSnapshot>()
        val db = dbHelper.readableDatabase
        val c = db.rawQuery("SELECT temperatura, humedad, distancia, latitud, longitud FROM trip_sensor_data WHERE viaje_id = ? ORDER BY id ASC", arrayOf(viajeId.toString()))
        while (c.moveToNext()) {
            lista.add(TripSnapshot(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getDouble(3), c.getDouble(4)))
        }
        c.close()
        return lista
    }
}

data class TripSnapshot(val temp: Float, val hum: Float, val dist: Float, val lat: Double, val lon: Double)

data class TrayectoFull(
    val id: Int,
    val inicio: String,
    val fin: String,
    val duracion: Int,
    val tempMedia: Float,
    val distMin: Float,
    var stats: StatsExtendido = StatsExtendido()
)

data class StatsExtendido(
    var minTemp: Float = 0f,
    var maxTemp: Float = 0f,
    var avgTemp: Float = 0f,
    var minHum: Float = 0f,
    var maxHum: Float = 0f,
    var avgHum: Float = 0f,
    var minDist: Float = 0f
)
