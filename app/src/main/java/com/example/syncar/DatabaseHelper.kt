package com.example.syncar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * DatabaseHelper: Gestiona la persistencia de usuarios, sesiones (viajes) y telemetría.
 * Versión 4: Añadimos tabla de ubicaciones para GPS.
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "syncar.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        // Tabla de Usuarios para el Login
        db.execSQL("""
            CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                password TEXT
            )
        """)

        // Tabla de Sesiones (Trayectos)
        db.execSQL("""
            CREATE TABLE sesiones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario_id INTEGER,
                inicio DATETIME DEFAULT CURRENT_TIMESTAMP,
                fin DATETIME,
                temp_media REAL,
                dist_min REAL,
                duracion_seg INTEGER,
                FOREIGN KEY(usuario_id) REFERENCES usuarios(id)
            )
        """)

        // Tabla de Telemetría
        db.execSQL("""
            CREATE TABLE datos (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sesion_id INTEGER,
                tipo TEXT,
                valor TEXT,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(sesion_id) REFERENCES sesiones(id)
            )
        """)

        // Tabla de Ubicaciones GPS
        db.execSQL("""
            CREATE TABLE ubicaciones (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sesion_id INTEGER,
                latitud REAL,
                longitud REAL,
                timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(sesion_id) REFERENCES sesiones(id)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS ubicaciones (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sesion_id INTEGER,
                    latitud REAL,
                    longitud REAL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(sesion_id) REFERENCES sesiones(id)
                )
            """)
        }
    }
}
