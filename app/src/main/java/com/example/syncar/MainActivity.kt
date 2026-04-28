package com.example.syncar

// --- IMPORTACIONES ---
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.syncar.ui.theme.SynCarTheme
import androidx.core.app.ActivityCompat

// Librerías necesarias para la comunicación Bluetooth Low Energy (BLE)
import android.bluetooth.*
import android.bluetooth.le.*
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.UUID

/**
 * MainActivity: Gestiona el ciclo de vida de la app, los permisos y la comunicación BLE.
 */
class MainActivity : ComponentActivity() {
    // dbHelper: Nos permite interactuar con la base de datos SQLite local.
    lateinit var dbHelper: DatabaseHelper
    
    // Estados para el Dashboard (Valores actuales)
    var tempActual by mutableStateOf("--")
    var humActual by mutableStateOf("--")
    var distActual by mutableStateOf("--")

    // bluetoothAdapter: Interfaz principal para el hardware de Bluetooth del móvil.
    private var bluetoothAdapter: BluetoothAdapter? = null
    // scanner: Objeto especializado en buscar señales de dispositivos BLE.
    private var scanner: BluetoothLeScanner? = null

    // listaDatos: Lista observable que almacena los mensajes para mostrar en pantalla.
    var listaDatos = mutableStateListOf<String>()

    /**
     * Gestor de permisos (ActivityResult): Maneja la respuesta del usuario cuando pedimos acceso.
     * Si acepta, iniciamos el escaneo automáticamente.
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                startBleScan()
            } else {
                Log.e("BLE", "Permisos denegados. El escaneo no puede continuar.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BLE", "Iniciando Aplicación SynCar...")

        // Inicializamos la base de datos y cargamos el historial previo.
        dbHelper = DatabaseHelper(this)
        cargarDatos()
        
        // --- INTERFAZ DE USUARIO (Dashboard con Tarjetas) ---
        setContent {
            SynCarTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Dashboard SynCar",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 🔥 TARJETAS PRINCIPALES
                    SensorCard("🌡 Temperatura", tempActual, "°C", Color(0xFFE53935))
                    SensorCard("💧 Humedad", humActual, "%", Color(0xFF1E88E5))
                    SensorCard("📏 Distancia", distActual, "cm", Color(0xFF43A047))

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Historial reciente",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Lista de historial con scroll opcional (simplificado)
                    Column {
                        listaDatos.take(5).forEach {
                            Text(
                                text = it,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Obtención de los servicios de Bluetooth del sistema.
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        // Comprobación de permisos inicial antes de arrancar el proceso.
        checkPermissionsAndStartScan()
    }

    /**
     * Verifica permisos según la versión de Android y solicita los que falten.
     */
    private fun checkPermissionsAndStartScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requiere permisos específicos de Escaneo y Conexión.
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Versiones anteriores dependen del permiso de localización fina.
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Inicia el proceso de búsqueda (Scan) de dispositivos BLE.
     */
    private fun startBleScan() {
        // Validación de seguridad para evitar errores de ejecución por falta de permisos.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return
        }
        Log.d("BLE", "Escaneando dispositivos...")
        scanner?.startScan(scanCallback)
    }

    /**
     * Callback de Escaneo: Se dispara por cada dispositivo detectado.
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return
            }
            
            // Si encontramos el dispositivo con el nombre configurado ("SynCar").
            if (result.device.name == "SynCar") {
                Log.d("BLE", "Dispositivo SynCar encontrado. Deteniendo escaneo y conectando...")
                
                // Detenemos el escaneo para ahorrar batería.
                scanner?.stopScan(this)

                // Establecemos la conexión GATT.
                result.device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    /**
     * Callback GATT: Gestiona la conexión y el intercambio de datos.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        
        // Se dispara al conectar o desconectar del dispositivo.
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Conectado al servidor. Descubriendo servicios...")
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Desconectado del servidor.")
            }
        }

        // Se dispara cuando el dispositivo reporta sus "carpetas" (servicios) disponibles.
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Buscamos el Servicio y Característica por sus identificadores únicos (UUID).
                val service = gatt.getService(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))
                val characteristic = service?.getCharacteristic(UUID.fromString("12345678-1234-5678-1234-56789abcdef1"))

                characteristic?.let {
                    // Habilitamos las notificaciones para recibir datos de forma automática.
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        gatt.setCharacteristicNotification(it, true)
                    }
                }
            }
        }

        /**
         * Se dispara CADA VEZ que el dispositivo envía datos nuevos.
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // 1. Lectura de bytes
            @Suppress("DEPRECATION")
            val data = characteristic.value
            
            // 2. Limpieza de datos (elimina espacios y caracteres nulos)
            val raw = String(data ?: byteArrayOf())
            val limpio = raw.trim().replace("\u0000", "")

            // 3. División de datos (tipo:valor) y formateo
            val partes = limpio.split(":")

            if (partes.size == 2) {
                val tipo = partes[0]
                val valor = partes[1].toFloatOrNull()

                val textoFormateado = if (valor != null) {
                    String.format(Locale.US, "%.2f", valor)
                } else {
                    partes[1]
                }

                val db = dbHelper.writableDatabase
                val values = android.content.ContentValues().apply {
                    put("tipo", tipo)
                    put("valor", textoFormateado)
                }
                db.insert("datos", null, values)

                runOnUiThread {
                    // Actualizamos el estado específico según el tipo para el Dashboard
                    when (tipo) {
                        "TEMP" -> tempActual = textoFormateado
                        "HUM" -> humActual = textoFormateado
                        "DIST" -> distActual = textoFormateado
                    }
                    // Añadimos al historial
                    listaDatos.add(0, "$tipo: $textoFormateado")
                }
            }
        }
    }

    /**
     * Consulta la base de datos para cargar el historial de datos guardados.
     */
    fun cargarDatos() {
        listaDatos.clear()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM datos ORDER BY id DESC", null)

        while (cursor.moveToNext()) {
            val tipo = cursor.getString(1)
            val valor = cursor.getString(2)
            listaDatos.add("$tipo: $valor")
        }
        cursor.close()
    }
}

/**
 * Componente visual reutilizable para mostrar los datos de los sensores.
 */
@Composable
fun SensorCard(titulo: String, valor: String, unidad: String, color: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = titulo,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "$valor $unidad",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * Vista previa para el diseño de Compose.
 */
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SynCarTheme {
        Column {
            Text("Vista Previa SynCar")
        }
    }
}
