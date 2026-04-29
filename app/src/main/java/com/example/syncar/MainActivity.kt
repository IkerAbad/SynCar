package com.example.syncar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
 * MainActivity: Refactorizada para usar BleManager y gestionar sesiones de usuario.
 */
class MainActivity : ComponentActivity() {
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var bleManager: BleManager

    // --- ESTADOS REACTIVOS ---
    private val listaHistorial = mutableStateListOf<String>()
    private val listaPuntosGrafica = mutableStateListOf<Float>()
    private var tempActual by mutableStateOf("--")
    private var humActual by mutableStateOf("--")
    private var distActual by mutableStateOf("--")
    private var bleStatus by mutableStateOf("Desconectado")
    
    // --- ESTADOS DE ALERTA ---
    private var alertMessage by mutableStateOf<String?>(null)
    private var isCriticalAlert by mutableStateOf(false)

    // --- ESTADOS DE SESIÓN (MODO CONDUCCIÓN) ---
    private var isJourneyActive by mutableStateOf(false)
    private var currentJourneyId by mutableStateOf(-1L)
    private val journeyDataPoints = mutableListOf<Triple<Float, Float, Float>>() // Temp, Hum, Dist
    private var startTimeMillis by mutableLongStateOf(0L)

    private var isLoggedIn by mutableStateOf(false)
    private var currentUserId by mutableStateOf(-1)
    private var currentSessionId by mutableStateOf(-1L)

    private fun iniciarTrayecto() {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("usuario_id", currentUserId)
        }
        currentJourneyId = db.insert("sesiones", null, values)
        isJourneyActive = true
        startTimeMillis = System.currentTimeMillis()
        journeyDataPoints.clear()
        Toast.makeText(this, "Trayecto iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun finalizarTrayecto() {
        if (!isJourneyActive) return

        val durationSec = (System.currentTimeMillis() - startTimeMillis) / 1000
        val avgTemp = if (journeyDataPoints.isNotEmpty()) journeyDataPoints.map { it.first }.average().toFloat() else 0f
        val minDist = if (journeyDataPoints.isNotEmpty()) journeyDataPoints.map { it.third }.minOrNull() ?: 0f else 0f

        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("fin", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("temp_media", avgTemp)
            put("dist_min", minDist)
            put("duracion_seg", durationSec)
        }
        db.update("sesiones", values, "id = ?", arrayOf(currentJourneyId.toString()))

        isJourneyActive = false
        currentJourneyId = -1
        Toast.makeText(this, "Trayecto finalizado: ${durationSec}s, Media: $avgTemp°C", Toast.LENGTH_LONG).show()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Habilitamos el modo Edge-to-Edge para que la app use toda la pantalla,
        // pero respetando las barras del sistema mediante WindowInsets en Compose.
        enableEdgeToEdge()

        dbHelper = DatabaseHelper(this)

        // Inicializar BleManager con lógica de estado y recepción
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
                            iniciarSesion(userId)
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
                            isJourneyActive = isJourneyActive,
                            alertMsg = alertMessage,
                            isAlertCritical = isCriticalAlert,
                            onDismissAlert = { 
                                alertMessage = null
                                isCriticalAlert = false
                            },
                            onStartJourney = { iniciarTrayecto() },
                            onStopJourney = { finalizarTrayecto() },
                            onLogout = {
                                if (isJourneyActive) finalizarTrayecto()
                                bleManager.disconnect()
                                isLoggedIn = false
                                alertMessage = null
                                // Resetear estados
                                tempActual = "--"
                                humActual = "--"
                                distActual = "--"
                                listaHistorial.clear()
                                listaPuntosGrafica.clear()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun procesarDatoRecibido(raw: String) {
        val limpio = raw.trim().replace("\u0000", "")
        Log.d("BLE_DEBUG", "DATO RECIBIDO: $limpio")
        
        val partes = limpio.split(":")
        val tipo: String
        val valorStr: String

        if (partes.size == 2 && partes[0].isNotEmpty()) {
            tipo = partes[0].trim()
            valorStr = partes[1].trim()
        } else {
            tipo = "TEMP"
            valorStr = limpio
        }

        if (tipo.isNotEmpty() && valorStr.isNotEmpty()) {
            val valorFloat = valorStr.replace(",", ".").toFloatOrNull()
            val textoFinal = if (valorFloat != null) String.format(Locale.US, "%.2f", valorFloat) else valorStr

            guardarEnDB(tipo, textoFinal)

            runOnUiThread {
                actualizarEstadoUI(tipo, textoFinal, valorFloat)
            }
        }
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
    }

    private fun iniciarSesion(userId: Int) {
        try {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("usuario_id", userId)
            }
            currentSessionId = db.insert("sesiones", null, values)
        } catch (e: Exception) { Log.e("SESSION", "Error inicio", e) }
    }

    private fun finalizarSesion() {
        if (currentSessionId != -1L) {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put("fin", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                }
                db.update("sesiones", values, "id = ?", arrayOf(currentSessionId.toString()))
                currentSessionId = -1L
            } catch (e: Exception) { Log.e("SESSION", "Error fin", e) }
        }
    }

    private fun guardarEnDB(tipo: String, valor: String) {
        try {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val values = ContentValues().apply {
                    if (currentSessionId != -1L) put("sesion_id", currentSessionId)
                    put("tipo", tipo)
                    put("valor", valor)
                }
                db.insert("datos", null, values)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) { Log.e("DB", "Error insert", e) }
    }

    override fun onDestroy() {
        super.onDestroy()
        finalizarSesion()
        bleManager.disconnect()
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
                
                if (v > 35f) {
                    currentAlert = "¡PELIGRO! Temperatura motor crítica: $texto°C"
                    isCritical = true
                }
            }
            "HUM" -> humActual = texto
            "DIST" -> {
                distActual = texto
                if (v < 30f && v > 0f) {
                    currentAlert = "¡AVISO! Distancia de seguridad reducida: $texto cm"
                    isCritical = v < 15f // Crítico si está muy cerca
                }
            }
        }
        
        // Gestión de alertas prioritaria
        if (currentAlert != null) {
            alertMessage = currentAlert
            isCriticalAlert = isCritical
        } else if (!isCriticalAlert) {
            // Solo limpiamos la alerta si no es una crítica que requiere atención
            alertMessage = null 
        }

        // Si hay trayecto activo...
        if (isJourneyActive) {
            val t = tempActual.replace(",", ".").toFloatOrNull() ?: 0f
            val h = humActual.replace(",", ".").toFloatOrNull() ?: 0f
            val d = distActual.replace(",", ".").toFloatOrNull() ?: 0f
            journeyDataPoints.add(Triple(t, h, d))
        }

        listaHistorial.add(0, "$tipo: $texto")
        if (listaHistorial.size > 10) listaHistorial.removeAt(listaHistorial.size - 1)
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
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SynCar Login", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Usuario", color = Color.LightGray) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.Gray
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = pass, 
                onValueChange = { pass = it }, 
                label = { Text("Contraseña", color = Color.LightGray) }, 
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.Gray
                )
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
                modifier = Modifier.fillMaxWidth()
            ) { Text("Entrar") }
        }
    }
}

@Composable
fun DashboardScreen(
    temp: String,
    hum: String,
    dist: String,
    status: String,
    puntos: SnapshotStateList<Float>,
    historial: SnapshotStateList<String>,
    isJourneyActive: Boolean,
    alertMsg: String?,
    isAlertCritical: Boolean,
    onDismissAlert: () -> Unit,
    onStartJourney: () -> Unit,
    onStopJourney: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(Color(0xFF1E1E1E))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SynCar Dashboard", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Logout", tint = Color.LightGray)
                    }
                }
                Text(
                    text = "• $status",
                    fontSize = 13.sp,
                    color = if (status.contains("Recibiendo") || status.contains("Conectado")) Color(0xFF4CAF50) else Color(0xFFFFC107),
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                )
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // SECCIÓN DE ALERTAS DINÁMICAS
            if (alertMsg != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAlertCritical) Color(0xFFB71C1C) else Color(0xFFE65100)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        onClick = onDismissAlert
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isAlertCritical) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(alertMsg, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("Tocar para cerrar", fontSize = 10.sp, color = Color.White.copy(0.7f))
                        }
                    }
                }
            }
            
            // MODO CONDUCCIÓN (REQUISITO TFG)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isJourneyActive) Color(0xFF2E7D32) else Color(0xFF263238)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isJourneyActive) "TRAYECTO ACTIVO" else "MODO CONDUCCIÓN", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(if (isJourneyActive) "Registrando viaje..." else "Pulsa para iniciar registro", fontSize = 12.sp, color = Color.White.copy(0.7f))
                        }
                        Button(
                            onClick = if (isJourneyActive) onStopJourney else onStartJourney,
                            colors = ButtonDefaults.buttonColors(containerColor = if (isJourneyActive) Color(0xFFD32F2F) else Color(0xFF43A047)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isJourneyActive) "PARAR" else "INICIAR")
                        }
                    }
                }
            }

            // TARJETAS SENSORES
            item {
                val tVal = temp.replace(",", ".").toFloatOrNull() ?: 0f
                val tColor = when {
                    tVal < 20f -> Color(0xFF0288D1)
                    tVal < 30f -> Color(0xFF43A047)
                    else -> Color(0xFFD32F2F)
                }
                SensorCardItem("🌡 Temperatura", temp, "°C", tColor)
            }
            item { SensorCardItem("💧 Humedad", hum, "%", Color(0xFF0097A7)) }
            item { 
                val dVal = dist.replace(",", ".").toFloatOrNull() ?: 100f
                SensorCardItem("📏 Distancia", dist, "cm", if (dVal < 30) Color(0xFFC62828) else Color(0xFF6A1B9A)) 
            }

            // GRÁFICA
            item {
                Text("Análisis Térmico", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
                GraficaRealTime(puntos)
            }

            // RAW DATA
            item {
                Text("Telemetría en Vivo", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 14.sp)
                Card(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(historial.size) { index ->
                            Text(historial[index], fontSize = 11.sp, color = Color.DarkGray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun SensorCardItem(titulo: String, valor: String, unidad: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                setNoDataText("Esperando telemetría...")
                setNoDataTextColor(android.graphics.Color.WHITE)
                description.isEnabled = false
                legend.textColor = android.graphics.Color.WHITE
                axisLeft.textColor = android.graphics.Color.WHITE
                axisLeft.gridColor = android.graphics.Color.DKGRAY
                axisRight.isEnabled = false
                xAxis.textColor = android.graphics.Color.WHITE
                xAxis.gridColor = android.graphics.Color.DKGRAY
                xAxis.setDrawLabels(false)
                xAxis.setDrawGridLines(true)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val dataSet = LineDataSet(mutableListOf(), "Temp °C").apply {
                    color = android.graphics.Color.parseColor("#FF5252")
                    lineWidth = 3f
                    setDrawValues(false)
                    setDrawCircles(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                    setDrawFilled(true)
                    fillDrawable = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(android.graphics.Color.parseColor("#4DFF5252"), android.graphics.Color.TRANSPARENT)
                    )
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
