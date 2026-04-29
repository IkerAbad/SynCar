package com.example.syncar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * DatabaseHelper: Gestiona la persistencia de usuarios, sesiones (viajes) y telemetría.
 * Versión 3: Añadimos campos de resumen a sesiones para evitar cálculos pesados en UI.
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "syncar.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        // Tabla de Usuarios para el Login
        db.execSQL("""
            CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE,
                password TEXT
            )
        """)

        // Tabla de Sesiones (Trayectos) - Mejorada para resumen
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

        // Tabla de Telemetría (vinculada a una sesión)
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
        
        // Insertamos un usuario de prueba por defecto
        db.execSQL("INSERT INTO usuarios (username, password) VALUES ('admin', '1234')")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // En un proyecto real haríamos una migración, aquí reseteamos para simplificar el TFG
        db.execSQL("DROP TABLE IF EXISTS datos")
        db.execSQL("DROP TABLE IF EXISTS sesiones")
        db.execSQL("DROP TABLE IF EXISTS usuarios")
        onCreate(db)
    }
}
