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

    private var isLoggedIn by mutableStateOf(false)
    private var currentUserId by mutableStateOf(-1)
    private var currentSessionId by mutableStateOf(-1L)

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
                            onLogout = {
                                finalizarSesion()
                                bleManager.disconnect()
                                isLoggedIn = false
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
            val values = android.content.ContentValues().apply {
                put("usuario_id", userId)
            }
            currentSessionId = db.insert("sesiones", null, values)
        } catch (e: Exception) { Log.e("SESSION", "Error inicio", e) }
    }

    private fun finalizarSesion() {
        if (currentSessionId != -1L) {
            try {
                val db = dbHelper.writableDatabase
                val values = android.content.ContentValues().apply {
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
                val values = android.content.ContentValues().apply {
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
        when (tipo) {
            "TEMP" -> {
                tempActual = texto
                valor?.let {
                    listaPuntosGrafica.add(it)
                    if (listaPuntosGrafica.size > 20) listaPuntosGrafica.removeAt(0)
                }
            }
            "HUM" -> humActual = texto
            "DIST" -> distActual = texto
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
fun DashboardScreen(temp: String, hum: String, dist: String, status: String, puntos: SnapshotStateList<Float>, historial: SnapshotStateList<String>, onLogout: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("SynCar Dashboard", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Cerrar Sesión", fontSize = 12.sp, color = Color.White)
                    }
                }
                Text(
                    text = "Estado: $status",
                    fontSize = 12.sp,
                    color = if (status.contains("Recibiendo") || status.contains("Conectado")) Color.Green else Color.Yellow,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            val tempValor = temp.replace(",", ".").toFloatOrNull() ?: 0f
            val tempColor = when {
                tempValor < 20f -> Color(0xFF1E88E5)
                tempValor < 30f -> Color(0xFF43A047)
                else -> Color(0xFFE53935)
            }
            SensorCard("🌡 Temperatura", temp, "°C", tempColor)
            SensorCard("💧 Humedad", hum, "%", Color(0xFF0288D1))
            SensorCard("📏 Distancia", dist, "cm", Color(0xFF7B1FA2))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Evolución Térmica", fontWeight = FontWeight.Bold, color = Color.White)
            GraficaRealTime(puntos)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Historial (Real-Time)", fontWeight = FontWeight.Bold, color = Color.White)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(historial) { dato ->
                    Text(dato, fontSize = 14.sp, color = Color.LightGray, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
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

@Composable
fun SensorCard(titulo: String, valor: String, unidad: String, color: Color) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = color), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(titulo, color = Color.White, fontSize = 14.sp)
            Text("$valor $unidad", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LazyColumn(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier, content = content)
}

fun <T> androidx.compose.foundation.lazy.LazyListScope.items(items: List<T>, itemContent: @Composable (T) -> Unit) {
    items(items.size) { index -> itemContent(items[index]) }
}
