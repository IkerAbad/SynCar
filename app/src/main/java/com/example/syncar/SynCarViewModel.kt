package com.example.syncar

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*

object SynCarFormatter {
    const val NO_DATA = "---"
    const val WAITING = "..."

    fun formatValue(type: String, value: Float?): String {
        if (value == null || value.isNaN()) return NO_DATA
        return "%.1f".format(Locale.US, value)
    }

    fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(Locale.US, h, m, s)
    }
}

enum class TipoEstado { RIESGO_TERMICO, DISTANCIA_PELIGROSA, NORMAL }
data class EstadoTrayecto(val tipo: TipoEstado, val texto: String, val color: Color)
data class Trayecto(val id: Int, val duracion: Int, val tempMedia: Float, val distMin: Float, val inicio: String = "")
data class Usuario(val id: Int, val username: String)

class SynCarViewModel(private val repository: SynCarRepository) : ViewModel() {

    // --- ESTADOS BLE ---
    var bleStatus by mutableStateOf("DISCONNECTED") ; private set
    var lastReceivedData by mutableStateOf("Ninguno") ; private set
    private var lastDataTime: Long = 0L

    // --- ESTADOS TELEMETRIA ---
    var tempActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    var humActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    var distActual by mutableStateOf(SynCarFormatter.WAITING) ; private set
    
    val puntosTemp = mutableStateListOf<Float>()
    val puntosHum = mutableStateListOf<Float>()
    val puntosDist = mutableStateListOf<Float>()
    val listaHistorial = mutableStateListOf<String>()

    // --- ESTADOS SESIÓN/USUARIO ---
    var isLoggedIn by mutableStateOf(false) ; private set
    var isGuest by mutableStateOf(false) ; private set
    var usuarioActivo by mutableStateOf<Usuario?>(null) ; private set
    val listaUsuarios = mutableStateListOf<Usuario>()
    var showUserManagement by mutableStateOf(false)
    var isCreatingAdmin by mutableStateOf(false)

    // --- ESTADOS DASHBOARD ---
    var alertMessage by mutableStateOf<String?>(null) ; private set
    var isCriticalAlert by mutableStateOf(false) ; private set
    var isParkingActive by mutableStateOf(false) ; private set
    var isSystemActive by mutableStateOf(true) ; private set
    var selectedChartType by mutableStateOf("COMBINED") ; private set

    // --- ESTADOS TRAYECTOS ---
    var isJourneyActive by mutableStateOf(false) ; private set
    val listaViajes = mutableStateListOf<Trayecto>()
    var trayectoSeleccionado by mutableStateOf<TrayectoFull?>(null)
    var puntosGpsViaje = mutableStateListOf<Pair<Double, Double>>()
    var puntosTempViaje = mutableStateListOf<Float>()
    var puntosHumViaje = mutableStateListOf<Float>()
    var puntosDistViaje = mutableStateListOf<Float>()

    private var currentJourneyId: Long = -1L
    private var startTimeMillis: Long = 0L
    private val journeyTemps = mutableListOf<Float>()
    private val journeyHums = mutableListOf<Float>()
    private val journeyDists = mutableListOf<Float>()
    
    private val pendingLogs = mutableListOf<String>()

    var onSendCommand: ((String) -> Unit)? = null

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (bleStatus == "RECEIVING DATA" && System.currentTimeMillis() - lastDataTime > 4000) {
                    bleStatus = "CONNECTED"
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(800) // Actualizar logs cada 800ms para evitar spam visual
                if (pendingLogs.isNotEmpty()) {
                    val logs = synchronized(pendingLogs) {
                        val items = pendingLogs.toList()
                        pendingLogs.clear()
                        items
                    }
                    logs.forEach { listaHistorial.add(0, it) }
                    while (listaHistorial.size > 50) listaHistorial.removeAt(listaHistorial.size - 1)
                }
            }
        }
        cargarUsuarios()
        isCreatingAdmin = listaUsuarios.isEmpty()
    }

    // --- GESTIÓN USUARIOS ---

    fun cargarUsuarios() {
        listaUsuarios.clear()
        listaUsuarios.addAll(repository.obtenerUsuarios())
    }

    fun crearUsuario(nombre: String, pass: String): Boolean {
        if (nombre.isBlank() || pass.isBlank()) return false
        val id = repository.crearUsuario(nombre, pass)
        cargarUsuarios()
        isCreatingAdmin = false
        return id != -1L
    }

    fun login(user: String, pass: String): Boolean {
        repository.login(user, pass)?.let { u ->
            usuarioActivo = u
            isLoggedIn = true
            isGuest = false
            cargarHistorial()
            return true
        }
        return false
    }

    fun loginAsGuest() {
        usuarioActivo = Usuario(-1, "Invitado")
        isLoggedIn = true
        isGuest = true
        listaViajes.clear()
    }

    fun logout() {
        if (isJourneyActive) finalizarTrayecto()
        isLoggedIn = false
        isGuest = false
        usuarioActivo = null
        trayectoSeleccionado = null
        listaViajes.clear()
    }

    fun borrarUsuario(id: Int): String? {
        if (listaUsuarios.size <= 1) return "No puedes borrar el último usuario"
        repository.borrarUsuario(id)
        cargarUsuarios()
        if (usuarioActivo?.id == id) logout()
        return null
    }

    fun editarUsuario(id: Int, nombre: String, pass: String?) {
        if (nombre.isNotBlank()) {
            repository.actualizarUsuario(id, nombre, pass)
            cargarUsuarios()
            if (usuarioActivo?.id == id) usuarioActivo = Usuario(id, nombre)
        }
    }

    // --- BLE & TELEMETRIA ---

    fun setStatus(status: String) { bleStatus = status }

    fun procesarDatoRecibido(raw: String) {
        viewModelScope.launch {
            val limpio = raw.trim().replace("\u0000", "")
            lastReceivedData = limpio
            lastDataTime = System.currentTimeMillis()
            
            if (bleStatus != "RECEIVING DATA") bleStatus = "RECEIVING DATA"

            val partes = limpio.split(":")
            val tipo = if (partes.size == 2) partes[0].trim().uppercase() else return@launch
            val valorStr = partes[1].trim()
            val valorFloat = valorStr.replace(",", ".").toFloatOrNull()

            if (isJourneyActive && !isGuest && currentJourneyId != -1L) {
                repository.guardarTelemetria(currentJourneyId, tipo, valorStr)
            }
            
            if (isJourneyActive) {
                valorFloat?.let {
                    when(tipo) {
                        "TEMP" -> journeyTemps.add(it)
                        "HUM" -> journeyHums.add(it)
                        "DIST" -> journeyDists.add(it)
                    }
                }
            }
            actualizarUI(tipo, valorFloat)
        }
    }

    private fun actualizarUI(tipo: String, valor: Float?) {
        val v = valor ?: 0f
        val displayValue = SynCarFormatter.formatValue(tipo, valor)
        
        when (tipo) {
            "TEMP" -> {
                tempActual = displayValue
                puntosTemp.add(v)
                if (puntosTemp.size > 100) puntosTemp.removeAt(0)
                
                if (v > 38f) {
                    alertMessage = "TEMPERATURA CRÍTICA: $displayValue°C"
                    isCriticalAlert = true
                } else if (v < 36f && alertMessage?.contains("TEMPERATURA") == true) {
                    alertMessage = null
                    isCriticalAlert = false
                }
            }
            "DIST" -> {
                distActual = displayValue
                puntosDist.add(v)
                if (puntosDist.size > 100) puntosDist.removeAt(0)
                
                if (v < 15f && v > 0f) {
                    alertMessage = "COLISIÓN INMINENTE: $displayValue cm"
                    isCriticalAlert = true
                } else if (v < 30f && v >= 15f) {
                    if (isCriticalAlert && v > 20f) {
                        alertMessage = "PROXIMIDAD: $displayValue cm"
                        isCriticalAlert = false
                    } else if (!isCriticalAlert && alertMessage?.contains("PROXIMIDAD") == false) {
                        alertMessage = "PROXIMIDAD: $displayValue cm"
                        isCriticalAlert = false
                    }
                } else if (v > 40f && alertMessage?.contains("cm") == true) {
                    alertMessage = null
                    isCriticalAlert = false
                }
            }
            "HUM" -> {
                humActual = displayValue
                puntosHum.add(v)
                if (puntosHum.size > 100) puntosHum.removeAt(0)
            }
        }
        
        synchronized(pendingLogs) {
            pendingLogs.add("${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} - $tipo: $displayValue")
        }
    }

    // --- TRAYECTOS & GPS ---

    fun iniciarTrayecto() {
        if (isGuest) {
            isJourneyActive = true
            startTimeMillis = System.currentTimeMillis()
            journeyTemps.clear()
            journeyHums.clear()
            journeyDists.clear()
            puntosGpsViaje.clear()
            return
        }
        val uid = usuarioActivo?.id ?: return
        currentJourneyId = repository.crearNuevoTrayecto(uid)
        if (currentJourneyId != -1L) {
            isJourneyActive = true
            startTimeMillis = System.currentTimeMillis()
            journeyTemps.clear()
            journeyHums.clear()
            journeyDists.clear()
            puntosGpsViaje.clear()
        }
    }

    fun guardarGps(lat: Double, lon: Double) {
        if (isJourneyActive) {
            if (!isGuest && currentJourneyId != -1L) {
                repository.guardarUbicacion(currentJourneyId, lat, lon)
            }
            puntosGpsViaje.add(Pair(lat, lon))
        }
    }

    fun finalizarTrayecto() {
        if (!isJourneyActive) return
        val duration = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
        val tMed = if (journeyTemps.isEmpty()) 0f else journeyTemps.average().toFloat()
        val dMin = if (journeyDists.isEmpty()) 0f else journeyDists.minOrNull() ?: 0f
        
        if (!isGuest) {
            repository.finalizarTrayecto(currentJourneyId, duration.toLong(), tMed, dMin)
            currentJourneyId = -1L
            cargarHistorial()
        } else {
            val fakeId = (System.currentTimeMillis() % 10000).toInt()
            val nowStr = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            listaViajes.add(0, Trayecto(fakeId, duration, tMed, dMin, nowStr))
        }
        isJourneyActive = false
    }

    fun cargarHistorial() {
        if (isGuest) return
        val uid = usuarioActivo?.id ?: return
        listaViajes.clear()
        listaViajes.addAll(repository.obtenerListaTrayectos(uid))
    }

    fun verDetalleTrayecto(id: Int) {
        viewModelScope.launch {
            if (isGuest) {
                listaViajes.find { it.id == id }?.let { t ->
                    trayectoSeleccionado = TrayectoFull(t.id, "Invitado", "---", t.duracion, t.tempMedia, t.distMin)
                }
                return@launch
            }
            val trayecto = repository.obtenerTrayectoPorId(id)
            if (trayecto != null) {
                trayectoSeleccionado = trayecto
                puntosTempViaje.clear()
                puntosTempViaje.addAll(repository.obtenerPuntosTelemetria(id, "TEMP"))
                puntosHumViaje.clear()
                puntosHumViaje.addAll(repository.obtenerPuntosTelemetria(id, "HUM"))
                puntosDistViaje.clear()
                puntosDistViaje.addAll(repository.obtenerPuntosTelemetria(id, "DIST"))
                puntosGpsViaje.clear()
                puntosGpsViaje.addAll(repository.obtenerRutaGps(id))
            }
        }
    }

    // --- COMANDOS ---
    fun toggleParking() {
        isParkingActive = !isParkingActive
        onSendCommand?.invoke(if (isParkingActive) "PARKING_ON" else "PARKING_OFF")
    }
    fun toggleSystem() {
        isSystemActive = !isSystemActive
        onSendCommand?.invoke(if (isSystemActive) "SYSTEM_ON" else "SYSTEM_OFF")
    }
    fun testBuzzer() { onSendCommand?.invoke("BUZZER_TEST") }
    fun setChartType(t: String) { selectedChartType = t }
    fun dismissAlert() { alertMessage = null; isCriticalAlert = false }
    
    fun inicializarApp() {
        cargarUsuarios()
        isCreatingAdmin = listaUsuarios.isEmpty()
    }
}

class SynCarViewModelFactory(private val repository: SynCarRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SynCarViewModel(repository) as T
}

fun evaluarTrayecto(viaje: Trayecto): List<EstadoTrayecto> {
    val estados = mutableListOf<EstadoTrayecto>()
    if (viaje.tempMedia > 35f) estados.add(EstadoTrayecto(TipoEstado.RIESGO_TERMICO, "Riesgo térmico", Color(0xFFF85149)))
    if (viaje.distMin < 25f && viaje.distMin > 0f) estados.add(EstadoTrayecto(TipoEstado.DISTANCIA_PELIGROSA, "Cercanía crítica", Color(0xFFD29922)))
    if (estados.isEmpty()) estados.add(EstadoTrayecto(TipoEstado.NORMAL, "Normal", Color(0xFF3FB950)))
    return estados
}

fun obtenerColorPrioritario(estados: List<EstadoTrayecto>): Color {
    return if (estados.any { it.tipo == TipoEstado.RIESGO_TERMICO }) Color(0xFFF85149)
    else if (estados.any { it.tipo == TipoEstado.DISTANCIA_PELIGROSA }) Color(0xFFD29922)
    else Color(0xFF3FB950)
}
