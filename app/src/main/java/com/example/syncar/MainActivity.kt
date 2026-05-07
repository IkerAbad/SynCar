package com.example.syncar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import android.util.Log
import com.example.syncar.ui.theme.SynCarTheme
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import android.content.ContentValues
import android.widget.Toast

/**
 * MainActivity: Sistema de telemetría vinculado a Trayectos (Journeys).
 */
class MainActivity : ComponentActivity() {
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var bleManager: BleManager

    // --- ESTADOS REACTIVOS UI ---
    private val listaHistorial = mutableStateListOf<String>()
    private val listaPuntosGrafica = mutableStateListOf<Float>()
    private val listaViajes = mutableStateListOf<Trayecto>()
    private var viajeSeleccionadoId by mutableStateOf(-1)
    private val puntosGraficaViaje = mutableStateListOf<Float>()
    private var tempActual by mutableStateOf("--")
    private var humActual by mutableStateOf("--")
    private var distActual by mutableStateOf("--")
    private var bleStatus by mutableStateOf("Desconectado")
    
    // --- ESTADOS DE ALERTA ---
    private var alertMessage by mutableStateOf<String?>(null)
    private var isCriticalAlert by mutableStateOf(false)

    // --- ESTADOS DE SESIÓN Y TRAYECTO ---
    private var isLoggedIn by mutableStateOf(false)
    private var currentUserId by mutableStateOf(-1)

    private var isJourneyActive by mutableStateOf(false)
    private var currentJourneyId by mutableStateOf(-1L)
    private var startTimeMillis by mutableLongStateOf(0L)
    
    // Acumuladores para cálculos del trayecto
    private val journeyTemps = mutableListOf<Float>()
    private val journeyDists = mutableListOf<Float>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)

        bleManager = BleManager(
            this,
            onStatusChanged = { status ->
                runOnUiThread { bleStatus = status }
            },
            onDataReceived = { dato ->
                procesarDatoRecibido(dato)
            }
        )

        inicializarDatosDesdeDB()

        setContent {
            SynCarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!isLoggedIn) {
                        LoginScreen(onLoginSuccess = { userId ->
                            currentUserId = userId
                            isLoggedIn = true
                            checkPermissions()
                        })
                    } else {
                        DashboardScreen(
                            temp = tempActual,
                            hum = humActual,
                            dist = distActual,
                            status = bleStatus,
                            puntos = listaPuntosGrafica,
                            historial = listaHistorial,
                            listaViajes = listaViajes,
                            viajeSeleccionadoId = viajeSeleccionadoId,
                            puntosViaje = puntosGraficaViaje,
                            isJourneyActive = isJourneyActive,
                            alertMsg = alertMessage,
                            isAlertCritical = isCriticalAlert,
                            onDismissAlert = { 
                                alertMessage = null
                                isCriticalAlert = false
                            },
                            onStartJourney = { iniciarTrayecto() },
                            onStopJourney = { finalizarTrayecto() },
                            onLogout = { cerrarSesionCompleta() },
                            onViajeClick = { cargarGraficaViaje(it) }
                        )
                    }
                }
            }
        }
    }

    // --- LÓGICA DE TRAYECTO (MODO CONDUCCIÓN) ---

    private fun iniciarTrayecto() {
        if (isJourneyActive) return
        
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("usuario_id", currentUserId)
                put("inicio", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            }
            // Insertamos en la tabla sesiones, que representa el Trayecto real
            currentJourneyId = db.insert("sesiones", null, values)
            
            if (currentJourneyId != -1L) {
                isJourneyActive = true
                startTimeMillis = System.currentTimeMillis()
                journeyTemps.clear()
                journeyDists.clear()
                Toast.makeText(this, "Trayecto iniciado correctamente", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("JOURNEY", "Error al iniciar trayecto", e)
        }
    }

    private fun finalizarTrayecto() {
        if (!isJourneyActive || currentJourneyId == -1L) return

        try {
            val durationSec = (System.currentTimeMillis() - startTimeMillis) / 1000
            
            // Cálculos robustos (evitan errores si no hubo datos durante el trayecto)
            val avgTemp = if (journeyTemps.isNotEmpty()) journeyTemps.average().toFloat() else 0f
            val minDist = if (journeyDists.isNotEmpty()) journeyDists.minOrNull() ?: 0f else 0f

            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("fin", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                put("temp_media", if (avgTemp.isNaN()) 0f else avgTemp)
                put("dist_min", minDist)
                put("duracion_seg", durationSec)
            }
            
            db.update("sesiones", values, "id = ?", arrayOf(currentJourneyId.toString()))
            
            cargarHistorialViajes() // Actualizar lista tras finalizar
            Toast.makeText(this, "Trayecto finalizado: ${durationSec}s", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("JOURNEY", "Error al finalizar trayecto", e)
        } finally {
            isJourneyActive = false
            currentJourneyId = -1L
            journeyTemps.clear()
            journeyDists.clear()
        }
    }

    private fun cerrarSesionCompleta() {
        if (isJourneyActive) finalizarTrayecto()
        bleManager.disconnect()
        
        // Limpieza de estados
        isLoggedIn = false
        currentUserId = -1
        alertMessage = null
        tempActual = "--"
        humActual = "--"
        distActual = "--"
        listaHistorial.clear()
        listaPuntosGrafica.clear()
    }

    // --- PERSISTENCIA DE TELEMETRÍA ---

    private fun guardarEnDB(tipo: String, valor: String) {
        // CORRECCIÓN: Solo guardamos si hay un trayecto activo. 
        // Esto vincula los datos al Journey ID correcto.
        if (!isJourneyActive || currentJourneyId == -1L) return

        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("sesion_id", currentJourneyId) // Vinculado al Trayecto
                put("tipo", tipo)
                put("valor", valor)
            }
            db.insert("datos", null, values)
        } catch (e: Exception) { 
            Log.e("DB", "Error al guardar telemetría", e) 
        }
    }

    // --- PROCESAMIENTO Y UI ---

    private fun procesarDatoRecibido(raw: String) {
        val limpio = raw.trim().replace("\u0000", "")
        val partes = limpio.split(":")
        
        val tipo: String
        val valorStr: String

        if (partes.size == 2) {
            tipo = partes[0].trim().uppercase()
            valorStr = partes[1].trim()
        } else {
            tipo = "TEMP"
            valorStr = limpio
        }

        val valorFloat = valorStr.replace(",", ".").toFloatOrNull()
        val textoFinal = if (valorFloat != null) String.format(Locale.US, "%.2f", valorFloat) else valorStr

        // Intentar guardar (la función validará si hay trayecto activo)
        guardarEnDB(tipo, textoFinal)

        runOnUiThread {
            actualizarEstadoUI(tipo, textoFinal, valorFloat)
        }
    }

    private fun actualizarEstadoUI(tipo: String, texto: String, valor: Float?) {
        val v = valor ?: 0f
        var currentAlert: String? = null
        var isCritical = false

        when (tipo) {
            "TEMP" -> {
                tempActual = texto
                listaPuntosGrafica.add(v)
                if (listaPuntosGrafica.size > 20) listaPuntosGrafica.removeAt(0)
                if (isJourneyActive) journeyTemps.add(v)
                
                if (v > 35f) {
                    currentAlert = "¡PELIGRO! Temperatura motor crítica: $texto°C"
                    isCritical = true
                }
            }
            "HUM" -> humActual = texto
            "DIST" -> {
                distActual = texto
                if (isJourneyActive) journeyDists.add(v)
                
                if (v < 30f && v > 0f) {
                    currentAlert = "¡AVISO! Distancia de seguridad reducida: $texto cm"
                    isCritical = v < 15f
                }
            }
        }
        
        if (currentAlert != null) {
            alertMessage = currentAlert
            isCriticalAlert = isCritical
        } else if (!isCriticalAlert) {
            alertMessage = null 
        }

        listaHistorial.add(0, "$tipo: $texto")
        if (listaHistorial.size > 15) listaHistorial.removeAt(listaHistorial.size - 1)
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            bleManager.startScan()
        } else {
            requestPermissionLauncher.launch(perms)
        }
    }

    private fun inicializarDatosDesdeDB() {
        try {
            cargarHistorialViajes()
            val db = dbHelper.readableDatabase
            val cHist = db.rawQuery("SELECT tipo, valor FROM datos ORDER BY id DESC LIMIT 10", null)
            while (cHist.moveToNext()) {
                listaHistorial.add("${cHist.getString(0)}: ${cHist.getString(1)}")
            }
            cHist.close()

            val cGraph = db.rawQuery("SELECT valor FROM datos WHERE tipo='TEMP' ORDER BY id DESC LIMIT 20", null)
            val tempPuntos = mutableListOf<Float>()
            while (cGraph.moveToNext()) {
                cGraph.getString(0).replace(",", ".").toFloatOrNull()?.let { tempPuntos.add(it) }
            }
            cGraph.close()
            listaPuntosGrafica.addAll(tempPuntos.reversed())
        } catch (e: Exception) { Log.e("DB", "Error init", e) }
    }

    private fun cargarHistorialViajes() {
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT id, duracion_seg, temp_media, dist_min FROM sesiones WHERE fin IS NOT NULL ORDER BY id DESC", null)
            listaViajes.clear()
            while (cursor.moveToNext()) {
                listaViajes.add(Trayecto(
                    id = cursor.getInt(0),
                    duracion = cursor.getInt(1),
                    tempMedia = cursor.getFloat(2),
                    distMin = cursor.getFloat(3)
                ))
            }
            cursor.close()
        } catch (e: Exception) { Log.e("DB", "Error cargando viajes", e) }
    }

    private fun cargarGraficaViaje(journeyId: Int) {
        if (viajeSeleccionadoId == journeyId) {
            viajeSeleccionadoId = -1
            puntosGraficaViaje.clear()
            return
        }

        viajeSeleccionadoId = journeyId
        puntosGraficaViaje.clear()

        try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT valor FROM datos WHERE sesion_id = ? AND tipo = 'TEMP' ORDER BY id ASC", arrayOf(journeyId.toString()))
            val puntos = mutableListOf<Float>()
            while (cursor.moveToNext()) {
                cursor.getString(0).replace(",", ".").toFloatOrNull()?.let { puntos.add(it) }
            }
            cursor.close()
            puntosGraficaViaje.addAll(puntos)
        } catch (e: Exception) { Log.e("DB", "Error cargando gráfica de viaje", e) }
    }

    override fun onDestroy() {
        if (isJourneyActive) finalizarTrayecto()
        bleManager.disconnect()
        super.onDestroy()
    }
}

// --- COMPOSABLES ---

@Composable
fun LoginScreen(onLoginSuccess: (Int) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val dbHelper = DatabaseHelper(LocalContext.current)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SynCar", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("Gestión de Telemetría", fontSize = 14.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(48.dp))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Usuario") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Contraseña") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            if (error.isNotEmpty()) Text(error, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    val db = dbHelper.readableDatabase
                    val cursor = db.rawQuery("SELECT id FROM usuarios WHERE username=? AND password=?", arrayOf(user, pass))
                    if (cursor.moveToFirst()) onLoginSuccess(cursor.getInt(0)) else error = "Credenciales incorrectas"
                    cursor.close()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) { Text("ENTRAR") }
        }
    }
}

@Composable
fun DashboardScreen(
    temp: String, hum: String, dist: String, status: String,
    puntos: SnapshotStateList<Float>, historial: SnapshotStateList<String>,
    listaViajes: SnapshotStateList<Trayecto>,
    viajeSeleccionadoId: Int, puntosViaje: SnapshotStateList<Float>,
    isJourneyActive: Boolean, alertMsg: String?, isAlertCritical: Boolean,
    onDismissAlert: () -> Unit, onStartJourney: () -> Unit,
    onStopJourney: () -> Unit, onLogout: () -> Unit,
    onViajeClick: (Int) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).background(Color(0xFF1E1E1E))) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("SynCar Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onLogout) { Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.LightGray) }
                }
                Text(text = "• $status", fontSize = 13.sp, color = if (status.contains("Conectado") || status.contains("Recibiendo")) Color.Green else Color.Yellow, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            if (alertMsg != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (isAlertCritical) Color(0xFFB71C1C) else Color(0xFFE65100)),
                        onClick = onDismissAlert
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isAlertCritical) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(alertMsg, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("Cerrar", fontSize = 10.sp, color = Color.White.copy(0.7f))
                        }
                    }
                }
            }
            
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isJourneyActive) Color(0xFF2E7D32) else Color(0xFF263238)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isJourneyActive) "TRAYECTO ACTIVO" else "MODO CONDUCCIÓN", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(if (isJourneyActive) "Registrando viaje..." else "Pulsa para iniciar registro", fontSize = 12.sp, color = Color.White.copy(0.7f))
                        }
                        Button(
                            onClick = if (isJourneyActive) onStopJourney else onStartJourney,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isJourneyActive) Color(0xFFD32F2F) else Color(0xFF43A047))
                        ) { Text(if (isJourneyActive) "PARAR" else "INICIAR") }
                    }
                }
            }

            item {
                val tVal = temp.replace(",", ".").toFloatOrNull() ?: 0f
                SensorCardItem("🌡 Temperatura", temp, "°C", when { tVal < 20f -> Color(0xFF0288D1); tVal < 30f -> Color(0xFF43A047); else -> Color(0xFFD32F2F) })
            }
            item { SensorCardItem("💧 Humedad", hum, "%", Color(0xFF0097A7)) }
            item { 
                val dVal = dist.replace(",", ".").toFloatOrNull() ?: 100f
                SensorCardItem("📏 Distancia", dist, "cm", if (dVal < 30) Color(0xFFC62828) else Color(0xFF6A1B9A)) 
            }

            item {
                Text("Evolución Térmica", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
                GraficaRealTime(puntos)
            }

            item {
                Text("Telemetría en Vivo", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
                Card(modifier = Modifier.fillMaxWidth().height(120.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(historial.size) { i -> Text(historial[i], fontSize = 11.sp, color = Color.DarkGray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
                    }
                }
            }

            item {
                Text("Historial de Trayectos", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
            }

            if (listaViajes.isEmpty()) {
                item {
                    Text("No hay trayectos registrados", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            } else {
                items(listaViajes.size) { index ->
                    val viaje = listaViajes[index]
                    ViajeItem(
                        viaje = viaje,
                        isSelected = viaje.id == viajeSeleccionadoId,
                        puntos = puntosViaje,
                        onClick = { onViajeClick(viaje.id) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

const val ESTADO_RIESGO_TERMICO = "Riesgo térmico"
const val ESTADO_DISTANCIA_PELIGROSA = "Distancia peligrosa"
const val ESTADO_NORMAL = "Trayecto normal"

data class EstadoTrayecto(val texto: String, val color: Color)

fun evaluarTrayecto(viaje: Trayecto): List<EstadoTrayecto> {
    val estados = mutableListOf<EstadoTrayecto>()
    if (viaje.tempMedia > 35f) {
        estados.add(EstadoTrayecto(ESTADO_RIESGO_TERMICO, Color(0xFFD32F2F)))
    }
    if (viaje.distMin < 20f) {
        estados.add(EstadoTrayecto(ESTADO_DISTANCIA_PELIGROSA, Color(0xFFE65100)))
    }
    if (estados.isEmpty()) {
        estados.add(EstadoTrayecto(ESTADO_NORMAL, Color(0xFF2E7D32)))
    }
    return estados
}

fun obtenerColorPrioritario(estados: List<EstadoTrayecto>): Color {
    // Prioridad por búsqueda explícita para garantizar consistencia total
    return estados.find { it.texto == ESTADO_RIESGO_TERMICO }?.color
        ?: estados.find { it.texto == ESTADO_DISTANCIA_PELIGROSA }?.color
        ?: Color(0xFF2E7D32) // Verde normal por defecto
}

@Composable
fun ViajeItem(viaje: Trayecto, isSelected: Boolean, puntos: List<Float>, onClick: () -> Unit) {
    val estados = evaluarTrayecto(viaje)
    val colorBorde = obtenerColorPrioritario(estados)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, if (isSelected) Color.Cyan.copy(0.5f) else colorBorde.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(), 
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ID: #${viaje.id}", fontWeight = FontWeight.Bold, color = if (isSelected) Color.Cyan else Color.White, fontSize = 14.sp)
                
                // Fila de badges dinámicos
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    estados.forEach { estado ->
                        Surface(
                            color = estado.color.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, estado.color.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = estado.texto.uppercase(),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = estado.color
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Duración: ${viaje.duracion}s", color = Color.Cyan, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Media: ${String.format(Locale.US, "%.1f", viaje.tempMedia)}°C", color = Color.LightGray, fontSize = 12.sp)
                    Text("Mín: ${String.format(Locale.US, "%.1f", viaje.distMin)}cm", color = Color.LightGray, fontSize = 12.sp)
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Análisis Térmico del Trayecto", fontWeight = FontWeight.Bold, color = Color.Cyan, fontSize = 12.sp)
                if (puntos.isEmpty()) {
                    Text("No hay datos registrados para este viaje.", color = Color.DarkGray, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    GraficaRealTime(puntos)
                }
            }
        }
    }
}

data class Trayecto(
    val id: Int,
    val duracion: Int,
    val tempMedia: Float,
    val distMin: Float
)

@Composable
fun SensorCardItem(titulo: String, valor: String, unidad: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(titulo, color = Color.LightGray, fontSize = 16.sp)
            Text("$valor $unidad", color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun GraficaRealTime(puntos: List<Float>) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp),
        factory = { context ->
            LineChart(context).apply {
                setNoDataText("Cargando...")
                setNoDataTextColor(android.graphics.Color.WHITE)
                description.isEnabled = false
                legend.textColor = android.graphics.Color.WHITE
                axisLeft.textColor = android.graphics.Color.WHITE
                axisRight.isEnabled = false
                xAxis.isEnabled = false
                val dataSet = LineDataSet(mutableListOf(), "Temp °C").apply {
                    color = android.graphics.Color.RED
                    lineWidth = 2f
                    setDrawCircles(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                data = LineData(dataSet)
            }
        },
        update = { chart ->
            val dataSet = chart.data?.getDataSetByIndex(0) as? LineDataSet
            if (dataSet != null) {
                dataSet.clear()
                puntos.forEachIndexed { i, v -> dataSet.addEntry(Entry(i.toFloat(), v)) }
                chart.data.notifyDataChanged()
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        }
    )
}
