package com.example.syncar

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.*

/**
 * Helper de formateo centralizado para garantizar coherencia en toda la App.
 */
object SynCarFormatter {
    const val NO_DATA = "Sin datos"
    const val WAITING = "Esperando sensor"

    fun formatValue(type: String, value: Float?): String {
        if (value == null || value.isNaN()) return NO_DATA
        return when (type.uppercase()) {
            "TEMP" -> "%.1f".format(Locale.US, value)
            "HUM" -> "%.0f".format(Locale.US, value)
            "DIST" -> "%.0f".format(Locale.US, value)
            else -> "%.1f".format(Locale.US, value)
        }
    }

    fun formatLog(rawLog: String): String {
        val parts = rawLog.split(":")
        if (parts.size != 2) return rawLog
        val type = parts[0].trim()
        val value = parts[1].trim().replace(",", ".").toFloatOrNull()
        return "$type: ${formatValue(type, value)}"
    }
}

enum class TipoEstado { RIESGO_TERMICO, DISTANCIA_PELIGROSA, NORMAL }
data class EstadoTrayecto(val tipo: TipoEstado, val texto: String, val color: Color)
data class Trayecto(val id: Int, val duracion: Int, val tempMedia: Float, val distMin: Float)

class SynCarViewModel(private val repository: SynCarRepository) : ViewModel() {

    // --- ESTADOS REACTIVOS ---
    var tempActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    var humActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    var distActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    var bleStatus by mutableStateOf("Desconectado") ; private set

    var alertMessage by mutableStateOf<String?>(null) ; private set
    var isCriticalAlert by mutableStateOf(false) ; private set
    var isLoggedIn by mutableStateOf(false) ; private set

    val listaHistorial = mutableStateListOf<String>()
    val listaPuntosGrafica = mutableStateListOf<Float>()
    val listaViajes = mutableStateListOf<Trayecto>()

    var viajeSeleccionadoId by mutableStateOf(-1) ; private set
    val puntosGraficaViaje = mutableStateListOf<Float>()

    // --- LÓGICA DE TRAYECTO ---
    var isJourneyActive by mutableStateOf(false) ; private set
    private var currentJourneyId: Long = -1L
    private var currentUserId: Int = -1
    private var startTimeMillis: Long = 0L
    private val journeyTemps = mutableListOf<Float>()
    private val journeyDists = mutableListOf<Float>()

    fun setStatus(status: String) { bleStatus = status }

    fun login(user: String, pass: String): Boolean {
        repository.login(user, pass)?.let { id ->
            currentUserId = id
            isLoggedIn = true
            return true
        }
        return false
    }

    fun procesarDatoRecibido(raw: String) {
        val limpio = raw.trim().replace("\u0000", "")
        val partes = limpio.split(":")
        val tipo = if (partes.size == 2) partes[0].trim().uppercase() else "TEMP"
        val valorStr = if (partes.size == 2) partes[1].trim() else limpio
        val valorFloat = valorStr.replace(",", ".").toFloatOrNull()

        if (isJourneyActive) {
            repository.guardarTelemetría(currentJourneyId, tipo, valorStr)
            valorFloat?.let {
                if (tipo == "TEMP") journeyTemps.add(it)
                else if (tipo == "DIST") journeyDists.add(it)
            }
        }
        actualizarUI(tipo, valorFloat)
    }

    private fun actualizarUI(tipo: String, valor: Float?) {
        val v = valor ?: 0f
        val displayValue = SynCarFormatter.formatValue(tipo, valor)
        
        when (tipo) {
            "TEMP" -> {
                tempActual = displayValue
                listaPuntosGrafica.add(v)
                if (listaPuntosGrafica.size > 20) listaPuntosGrafica.removeAt(0)
                if (v > 35f) { 
                    alertMessage = "¡PELIGRO! Temperatura crítica: $displayValue°C"
                    isCriticalAlert = true 
                }
            }
            "DIST" -> {
                distActual = displayValue
                if (v < 30f && v > 0f) {
                    alertMessage = "¡AVISO! Distancia reducida: $displayValue cm"
                    isCriticalAlert = v < 15f
                }
            }
            "HUM" -> humActual = displayValue
        }
        
        listaHistorial.add(0, "$tipo: $displayValue")
        if (listaHistorial.size > 15) listaHistorial.removeAt(listaHistorial.size - 1)
    }

    fun iniciarTrayecto() {
        if (isJourneyActive) return
        currentJourneyId = repository.crearNuevoTrayecto(currentUserId)
        if (currentJourneyId != -1L) {
            isJourneyActive = true
            startTimeMillis = System.currentTimeMillis()
            journeyTemps.clear() ; journeyDists.clear()
        }
    }

    fun finalizarTrayecto() {
        if (!isJourneyActive) return
        val duration = (System.currentTimeMillis() - startTimeMillis) / 1000
        repository.finalizarTrayecto(currentJourneyId, duration, journeyTemps.average().toFloat(), journeyDists.minOrNull() ?: 0f)
        isJourneyActive = false
        cargarHistorial()
    }

    fun cargarGraficaViaje(id: Int) {
        if (viajeSeleccionadoId == id) {
            viajeSeleccionadoId = -1 ; puntosGraficaViaje.clear() ; return
        }
        viajeSeleccionadoId = id
        puntosGraficaViaje.clear()
        puntosGraficaViaje.addAll(repository.obtenerDetalleGraficaViaje(id))
    }

    fun inicializarApp() {
        if (listaViajes.isEmpty()) cargarHistorial()
        // Cargamos y formateamos el historial reciente
        val logs = repository.obtenerHistorialTelemetriaReciente()
        listaHistorial.clear()
        listaHistorial.addAll(logs.map { SynCarFormatter.formatLog(it) })
        listaPuntosGrafica.addAll(repository.obtenerPuntosTemperaturaGrafica())
    }

    private fun cargarHistorial() {
        listaViajes.clear()
        listaViajes.addAll(repository.obtenerListaTrayectos())
    }

    fun logout() {
        isLoggedIn = false 
        alertMessage = null 
        tempActual = SynCarFormatter.WAITING
        humActual = SynCarFormatter.WAITING
        distActual = SynCarFormatter.WAITING
        listaHistorial.clear()
    }

    fun dismissAlert() { alertMessage = null ; isCriticalAlert = false }
}

class SynCarViewModelFactory(private val repository: SynCarRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SynCarViewModel(repository) as T
}

fun evaluarTrayecto(viaje: Trayecto): List<EstadoTrayecto> {
    val estados = mutableListOf<EstadoTrayecto>()
    if (viaje.tempMedia > 35f) estados.add(EstadoTrayecto(TipoEstado.RIESGO_TERMICO, "Riesgo térmico", Color(0xFFD32F2F)))
    if (viaje.distMin < 20f) estados.add(EstadoTrayecto(TipoEstado.DISTANCIA_PELIGROSA, "Distancia peligrosa", Color(0xFFE65100)))
    if (estados.isEmpty()) estados.add(EstadoTrayecto(TipoEstado.NORMAL, "Normal", Color(0xFF2E7D32)))
    return estados
}

fun obtenerColorPrioritario(estados: List<EstadoTrayecto>): Color {
    return estados.find { it.tipo == TipoEstado.RIESGO_TERMICO }?.color
        ?: estados.find { it.tipo == TipoEstado.DISTANCIA_PELIGROSA }?.color
        ?: Color(0xFF2E7D32)
}
