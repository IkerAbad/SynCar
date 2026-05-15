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
    
    // Valores numéricos raw para cálculos y persistencia segura
    private var rawTemp: Float = 0f
    private var rawHum: Float = 0f
    private var rawDist: Float = 0f
    private var lastValidGps: Pair<Double, Double>? = null

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
    
    val isAdmin: Boolean get() = usuarioActivo?.id == 1 || usuarioActivo?.username?.lowercase() == "admin"
    
    // --- COOLDOWN ALERTAS ---
    private var lastDismissTime: Long = 0L
    private val ALERT_COOLDOWN_MS = 5000L

    // --- ESTADOS DASHBOARD ---
    var alertMessage by mutableStateOf<String?>(null) ; private set
    var isCriticalAlert by mutableStateOf(false) ; private set
    var isParkingActive by mutableStateOf(false) ; private set
    var isSystemActive by mutableStateOf(true) ; private set
    var selectedChartType by mutableStateOf("COMBINED") ; private set

    // --- ESTADOS TRAYECTOS ---
    var isJourneyActive by mutableStateOf(false) ; private set
    var isWaitingForFirstGps by mutableStateOf(false) ; private set
    val listaViajes = mutableStateListOf<Trayecto>()
    var trayectoSeleccionado by mutableStateOf<TrayectoFull?>(null)
    var puntosGpsViaje = mutableStateListOf<Pair<Double, Double>>()
    var puntosTempViaje = mutableStateListOf<Float>()
    var puntosHumViaje = mutableStateListOf<Float>()
    var puntosDistViaje = mutableStateListOf<Float>()

    private var currentJourneyId: Long = -1L
    private var startTimeMillis: Long = 0L
    private var snapshotJob: kotlinx.coroutines.Job? = null
    
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val users = repository.obtenerUsuarios()
            launch(kotlinx.coroutines.Dispatchers.Main) {
                listaUsuarios.clear()
                listaUsuarios.addAll(users)
                isCreatingAdmin = listaUsuarios.isEmpty()
            }
        }
    }

    fun crearUsuario(nombre: String, pass: String): Boolean {
        if (nombre.isBlank() || pass.isBlank()) return false
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existentes = repository.obtenerUsuarios()
            if (existentes.any { it.username.equals(nombre, true) }) {
                return@launch
            }
            
            val id = repository.crearUsuario(nombre, pass)
            if (id != -1L) {
                cargarUsuarios()
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (isCreatingAdmin) {
                        isCreatingAdmin = false
                        login(nombre, pass)
                    }
                }
            }
        }
        return true
    }

    fun login(user: String, pass: String): Boolean {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val u = repository.login(user, pass)
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (u != null) {
                    iniciarSesionUsuario(u)
                }
            }
        }
        return true
    }

    private fun iniciarSesionUsuario(u: Usuario) {
        usuarioActivo = u
        isLoggedIn = true
        isGuest = false
        trayectoSeleccionado = null
        showUserManagement = false
        cargarHistorial()
    }

    fun cambiarUsuarioAdmin(u: Usuario) {
        if (isAdmin) {
            iniciarSesionUsuario(u)
        }
    }

    fun loginAsGuest() {
        usuarioActivo = Usuario(-1, "Invitado")
        isLoggedIn = true
        isGuest = true
        listaViajes.clear()
    }

    fun logout() {
        if (isJourneyActive) finalizarTrayecto()
        
        // --- SEGURIDAD: Apagar parking y encender sistema al salir ---
        onSendCommand?.invoke("PARKING_OFF")
        onSendCommand?.invoke("SYSTEM_ON")
        
        // Reset estados locales
        isParkingActive = false
        isSystemActive = true
        alertMessage = null
        isCriticalAlert = false

        isLoggedIn = false
        isGuest = false
        usuarioActivo = null
        trayectoSeleccionado = null
        showUserManagement = false
        listaViajes.clear()
    }

    fun borrarUsuario(id: Int): String? {
        if (listaUsuarios.size <= 1) return "No puedes borrar el último usuario"
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.borrarUsuario(id)
            cargarUsuarios()
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (usuarioActivo?.id == id) logout()
            }
        }
        return null
    }

    fun editarUsuario(id: Int, nombre: String, pass: String?) {
        if (nombre.isNotBlank()) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                repository.actualizarUsuario(id, nombre, pass)
                cargarUsuarios()
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (usuarioActivo?.id == id) usuarioActivo = Usuario(id, nombre)
                }
            }
        }
    }

    // --- BLE & TELEMETRIA ---

    fun setStatus(status: String) { bleStatus = status }

    fun procesarDatoRecibido(raw: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val limpio = raw.trim().replace("\u0000", "")
            
            launch(kotlinx.coroutines.Dispatchers.Main) {
                lastReceivedData = limpio
                lastDataTime = System.currentTimeMillis()
                if (bleStatus != "RECEIVING DATA") bleStatus = "RECEIVING DATA"
            }

            val partes = limpio.split(":")
            val tipo = if (partes.size == 2) partes[0].trim().uppercase() else return@launch
            val valorStr = partes[1].trim()
            val valorFloat = valorStr.replace(",", ".").toFloatOrNull()

            if (isJourneyActive && !isGuest && currentJourneyId != -1L) {
                repository.guardarTelemetria(currentJourneyId, tipo, valorStr)
            }
            
            launch(kotlinx.coroutines.Dispatchers.Main) {
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
    }

    private fun actualizarUI(tipo: String, valor: Float?) {
        val v = valor ?: 0f
        val displayValue = SynCarFormatter.formatValue(tipo, valor)
        
        when (tipo) {
            "TEMP" -> {
                rawTemp = v
                tempActual = displayValue
                puntosTemp.add(v)
                if (puntosTemp.size > 100) puntosTemp.removeAt(0)
                
                if (v > 38f) {
                    if (System.currentTimeMillis() - lastDismissTime > ALERT_COOLDOWN_MS) {
                        alertMessage = "TEMPERATURA CRÍTICA: $displayValue°C"
                        isCriticalAlert = true
                    }
                } else if (v < 36f && alertMessage?.contains("TEMPERATURA") == true) {
                    alertMessage = null
                    isCriticalAlert = false
                }
            }
            "DIST" -> {
                rawDist = v
                distActual = displayValue
                puntosDist.add(v)
                if (puntosDist.size > 100) puntosDist.removeAt(0)
                
                if (v < 15f && v > 0f) {
                    if (System.currentTimeMillis() - lastDismissTime > ALERT_COOLDOWN_MS) {
                        alertMessage = "COLISIÓN INMINENTE: $displayValue cm"
                        isCriticalAlert = true
                    }
                } else if (v < 30f && v >= 15f) {
                    if (isCriticalAlert && v > 20f) {
                        alertMessage = "PROXIMIDAD: $displayValue cm"
                        isCriticalAlert = false
                    } else if (!isCriticalAlert && alertMessage?.contains("PROXIMIDAD") == false) {
                        if (System.currentTimeMillis() - lastDismissTime > ALERT_COOLDOWN_MS) {
                            alertMessage = "PROXIMIDAD: $displayValue cm"
                            isCriticalAlert = false
                        }
                    }
                } else if (v > 40f && alertMessage?.contains("cm") == true) {
                    alertMessage = null
                    isCriticalAlert = false
                }
            }
            "HUM" -> {
                rawHum = v
                humActual = displayValue
                puntosHum.add(v)
                if (puntosHum.size > 100) puntosHum.removeAt(0)
            }
        }
        
        // Solo mostrar logs de sensores reales en la consola técnica
        if (tipo == "TEMP" || tipo == "HUM" || tipo == "DIST") {
            synchronized(pendingLogs) {
                pendingLogs.add("${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} - $tipo: $displayValue")
            }
        }
    }

    // --- TRAYECTOS & GPS ---

    fun iniciarTrayecto() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (isGuest) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    isJourneyActive = true
                    isWaitingForFirstGps = true
                    startTimeMillis = System.currentTimeMillis()
                    journeyTemps.clear()
                    journeyHums.clear()
                    journeyDists.clear()
                    puntosGpsViaje.clear()
                    startSnapshotTask(-1)
                }
                return@launch
            }
            val uid = usuarioActivo?.id ?: return@launch
            val jid = repository.crearNuevoTrayecto(uid)
            launch(kotlinx.coroutines.Dispatchers.Main) {
                if (jid != -1L) {
                    currentJourneyId = jid
                    isJourneyActive = true
                    isWaitingForFirstGps = true
                    startTimeMillis = System.currentTimeMillis()
                    journeyTemps.clear()
                    journeyHums.clear()
                    journeyDists.clear()
                    puntosGpsViaje.clear()
                    startSnapshotTask(jid)
                }
            }
        }
    }

    private fun startSnapshotTask(jid: Long) {
        snapshotJob?.cancel()
        if (isGuest || jid == -1L) return
        
        snapshotJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            android.util.Log.d("SynCar", "Iniciando tarea de snapshots para viaje: $jid")
            while (isJourneyActive) {
                // Capturar valores actuales (raw)
                val t = rawTemp
                val h = rawHum
                val d = rawDist
                val lastGps = lastValidGps ?: Pair(0.0, 0.0)
                
                android.util.Log.d("SynCar", "Guardando snapshot: T=$t, H=$h, D=$d, GPS=${lastGps.first},${lastGps.second}")
                repository.guardarSnapshot(jid, t, h, d, lastGps.first, lastGps.second)
                
                kotlinx.coroutines.delay(2000) // Snapshot cada 2 segundos según requerimiento
            }
            android.util.Log.d("SynCar", "Tarea de snapshots finalizada para viaje: $jid")
        }
    }

    fun guardarGps(lat: Double, lon: Double) {
        lastValidGps = Pair(lat, lon)
        if (isJourneyActive) {
            if (isWaitingForFirstGps) isWaitingForFirstGps = false
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (!isGuest && currentJourneyId != -1L) {
                    android.util.Log.d("SynCarSQL", "Guardando ubicación SQL: $lat, $lon")
                    repository.guardarUbicacion(currentJourneyId, lat, lon)
                }
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    puntosGpsViaje.add(Pair(lat, lon))
                }
            }
        }
    }

    fun finalizarTrayecto() {
        if (!isJourneyActive) return
        if (isWaitingForFirstGps) {
            // Opcional: Podríamos mostrar un Toast diciendo "Espera al GPS"
            // Por ahora, simplemente reseteamos el estado si el usuario fuerza el cierre
            isWaitingForFirstGps = false
        }
        snapshotJob?.cancel()
        val duration = ((System.currentTimeMillis() - startTimeMillis) / 1000).toInt()
        val tMed = if (journeyTemps.isEmpty()) 0f else journeyTemps.average().toFloat()
        val dMin = if (journeyDists.isEmpty()) 0f else journeyDists.minOrNull() ?: 0f
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!isGuest) {
                repository.finalizarTrayecto(currentJourneyId, duration.toLong(), tMed, dMin)
                currentJourneyId = -1L
                cargarHistorial()
            } else {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    val fakeId = (System.currentTimeMillis() % 10000).toInt()
                    val nowStr = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    listaViajes.add(0, Trayecto(fakeId, duration, tMed, dMin, nowStr))
                }
            }
            launch(kotlinx.coroutines.Dispatchers.Main) {
                isJourneyActive = false
            }
        }
    }

    fun cargarHistorial() {
        if (isGuest) return
        val uid = usuarioActivo?.id ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val history = repository.obtenerListaTrayectos(uid)
            launch(kotlinx.coroutines.Dispatchers.Main) {
                listaViajes.clear()
                listaViajes.addAll(history)
            }
        }
    }

    fun verDetalleTrayecto(id: Int) {
        val uid = usuarioActivo?.id ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (isGuest) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    listaViajes.find { it.id == id }?.let { t ->
                        trayectoSeleccionado = TrayectoFull(t.id, t.inicio, "---", t.duracion, t.tempMedia, t.distMin)
                    }
                }
                return@launch
            }
            val trayecto = repository.obtenerTrayectoPorId(id, uid)
            if (trayecto != null) {
                val temps = repository.obtenerPuntosTelemetria(id, "TEMP")
                val hums = repository.obtenerPuntosTelemetria(id, "HUM")
                val dists = repository.obtenerPuntosTelemetria(id, "DIST")
                val gps = repository.obtenerRutaGps(id)
                val stats = repository.obtenerEstadisticasExtendido(id)
                
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    trayecto.stats = stats
                    trayectoSeleccionado = trayecto
                    puntosTempViaje.clear()
                    puntosTempViaje.addAll(temps)
                    puntosHumViaje.clear()
                    puntosHumViaje.addAll(hums)
                    puntosDistViaje.clear()
                    puntosDistViaje.addAll(dists)
                    puntosGpsViaje.clear()
                    puntosGpsViaje.addAll(gps)
                }
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
    fun dismissAlert() { 
        alertMessage = null
        isCriticalAlert = false
        lastDismissTime = System.currentTimeMillis()
    }

    fun borrarTrayecto(id: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.borrarTrayecto(id)
            launch(kotlinx.coroutines.Dispatchers.Main) {
                cargarHistorial()
                if (trayectoSeleccionado?.id == id) trayectoSeleccionado = null
            }
        }
    }
    
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
