package com.example.syncar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import android.bluetooth.*
import android.bluetooth.le.*
import android.util.Log
import com.example.syncar.ui.theme.SynCarTheme
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.*
import java.util.*

/**
 * MainActivity: Gestión optimizada para telemetría en tiempo real.
 */
class MainActivity : ComponentActivity() {
    private lateinit var dbHelper: DatabaseHelper

    // --- ESTADOS REACTIVOS (SINGLE SOURCE OF TRUTH) ---
    // Usamos 'val' para que la instancia de la lista nunca cambie, solo su contenido.
    private val listaHistorial = mutableStateListOf<String>()
    private val listaPuntosGrafica = mutableStateListOf<Float>()
    
    // Estados simples para las tarjetas superiores
    private var tempActual by mutableStateOf("--")
    private var humActual by mutableStateOf("--")
    private var distActual by mutableStateOf("--")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) startBleScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = DatabaseHelper(this)

        // Carga inicial desde SQLite
        inicializarDatosDesdeDB()

        setContent {
            SynCarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(
                        temp = tempActual,
                        hum = humActual,
                        dist = distActual,
                        puntos = listaPuntosGrafica, 
                        historial = listaHistorial
                    )
                }
            }
        }

        configurarBluetooth()
    }

    private fun inicializarDatosDesdeDB() {
        // Cargar historial (últimos 10 para que sea visualmente fijo y limpio)
        val db = dbHelper.readableDatabase
        val cHist = db.rawQuery("SELECT tipo, valor FROM datos ORDER BY id DESC LIMIT 10", null)
        while (cHist.moveToNext()) {
            listaHistorial.add("${cHist.getString(0)}: ${cHist.getString(1)}")
        }
        cHist.close()

        // Cargar gráfica (últimos 20 de temperatura)
        val cGraph = db.rawQuery("SELECT valor FROM datos WHERE tipo='TEMP' ORDER BY id DESC LIMIT 20", null)
        val tempPuntos = mutableListOf<Float>()
        while (cGraph.moveToNext()) {
            cGraph.getString(0).replace(",", ".").toFloatOrNull()?.let { tempPuntos.add(it) }
        }
        cGraph.close()
        listaPuntosGrafica.addAll(tempPuntos.reversed())
    }

    private fun configurarBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        checkPermissions()
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else {
            requestPermissionLauncher.launch(perms)
        }
    }

    private fun startBleScan() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            scanner?.startScan(scanCallback)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == "SynCar") {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    scanner?.stopScan(this)
                }
                result.device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))
            val characteristic = service?.getCharacteristic(UUID.fromString("12345678-1234-5678-1234-56789abcdef1"))

            if (characteristic != null && (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)) {
                // 1. Activar notificaciones localmente
                gatt.setCharacteristicNotification(characteristic, true)

                // 2. Activar notificaciones en el PERIFÉRICO (Pico 2W) escribiendo en el descriptor CCCD
                val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                    Log.d("BLE_DEBUG", "Descriptor CCCD escrito. Notificaciones activadas.")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val raw = String(characteristic.value ?: byteArrayOf())
            val limpio = raw.trim().replace("\u0000", "")
            Log.d("BLE_DEBUG", "DATO RECIBIDO: $limpio")
            
            val partes = limpio.split(":")
            val tipo: String
            val valorStr: String

            // Parser flexible: Si la Pico envía "22.5" sin prefijo, asumimos que es TEMP
            if (partes.size == 2 && partes[0].isNotEmpty()) {
                tipo = partes[0].trim()
                valorStr = partes[1].trim()
            } else {
                tipo = "TEMP"
                valorStr = limpio
            }

            if (tipo.isNotEmpty() && valorStr.isNotEmpty()) {
                val valorFloat = valorStr.replace(",", ".").toFloatOrNull()
                // Formateamos siempre a 2 decimales para la UI
                val textoFinal = if (valorFloat != null) String.format(Locale.US, "%.2f", valorFloat) else valorStr

                guardarEnDB(tipo, textoFinal)

                runOnUiThread {
                    actualizarEstadoUI(tipo, textoFinal, valorFloat)
                }
            }
        }
    }

    private fun guardarEnDB(tipo: String, valor: String) {
        try {
            val db = dbHelper.writableDatabase
            val values = android.content.ContentValues().apply {
                put("tipo", tipo)
                put("valor", valor)
            }
            db.insert("datos", null, values)
        } catch (e: Exception) { Log.e("DB", "Error al guardar", e) }
    }

    private fun actualizarEstadoUI(tipo: String, texto: String, valor: Float?) {
        // Actualizar tarjetas
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
        // Actualizar historial (limitado a 10 para evitar scroll infinito y mantenerlo limpio)
        listaHistorial.add(0, "$tipo: $texto")
        if (listaHistorial.size > 10) listaHistorial.removeAt(listaHistorial.size - 1)
    }
}

// --- COMPOSABLES DE INTERFAZ ---

@Composable
fun DashboardScreen(temp: String, hum: String, dist: String, puntos: SnapshotStateList<Float>, historial: SnapshotStateList<String>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("SynCar Dashboard", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        
        SensorCard("🌡 Temperatura", temp, "°C", Color(0xFFE53935))
        SensorCard("💧 Humedad", hum, "%", Color(0xFF1E88E5))
        SensorCard("📏 Distancia", dist, "cm", Color(0xFF43A047))

        Spacer(modifier = Modifier.height(24.dp))
        Text("Evolución Térmica", fontWeight = FontWeight.Bold)
        GraficaRealTime(puntos)

        Spacer(modifier = Modifier.height(24.dp))
        Text("Historial (Real-Time)", fontWeight = FontWeight.Bold)
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(historial) { dato ->
                Text(dato, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun GraficaRealTime(puntos: List<Float>) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        factory = { context ->
            LineChart(context).apply {
                setNoDataText("Esperando telemetría...")
                description.isEnabled = false
                legend.isEnabled = true
                axisRight.isEnabled = false
                xAxis.setDrawLabels(false)
                xAxis.setDrawGridLines(false)
                setTouchEnabled(true)

                // Inicializamos el dataSet vacío una sola vez
                val dataSet = LineDataSet(mutableListOf(), "Temperatura °C").apply {
                    color = android.graphics.Color.RED
                    lineWidth = 3f
                    setDrawValues(false)
                    setDrawCircles(false)
                    mode = LineDataSet.Mode.CUBIC_BEZIER
                }
                data = LineData(dataSet)
            }
        },
        update = { chart ->
            // En lugar de recrear el LineData, actualizamos el DataSet existente
            val dataSet = chart.data?.getDataSetByIndex(0) as? LineDataSet
            if (dataSet != null) {
                dataSet.clear()
                puntos.forEachIndexed { i, v ->
                    dataSet.addEntry(Entry(i.toFloat(), v))
                }
                chart.data.notifyDataChanged()
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        }
    )
}

@Composable
fun SensorCard(titulo: String, valor: String, unidad: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(titulo, color = Color.White, fontSize = 14.sp)
            Text("$valor $unidad", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// Necesario para el historial eficiente
@Composable
fun LazyColumn(modifier: Modifier, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier, content = content)
}

fun <T> androidx.compose.foundation.lazy.LazyListScope.items(items: List<T>, itemContent: @Composable (T) -> Unit) {
    items(items.size) { index -> itemContent(items[index]) }
}
