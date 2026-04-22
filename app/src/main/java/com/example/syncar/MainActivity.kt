package com.example.syncar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.syncar.ui.theme.SynCarTheme
import androidx.core.app.ActivityCompat

// Librerías necesarias para la comunicación Bluetooth Low Energy (BLE)
import android.bluetooth.*
import android.bluetooth.le.*
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * Clase principal de la aplicación.
 * Gestiona el ciclo de vida de la actividad, los permisos y la conexión BLE.
 */
class MainActivity : ComponentActivity() {

    var temperatura by mutableStateOf("Sin datos")

    // Objeto que representa el adaptador Bluetooth del dispositivo (el hardware)
    private var bluetoothAdapter: BluetoothAdapter? = null
    // Escáner para buscar dispositivos BLE cercanos
    private var scanner: BluetoothLeScanner? = null

    /**
     * Gestor de permisos: se encarga de recibir la respuesta del usuario cuando pedimos permisos.
     * Si el usuario acepta todo, llama a startBleScan().
     */
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                startBleScan()
            } else {
                Log.e("BLE", "Permisos no concedidos por el usuario.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BLE", "APP INICIADA")
        
        // Define la interfaz visual usando Compose
        setContent {
            SynCarTheme {
                Text("Temperatura: $temperatura")
            }
        }

        // Obtenemos los servicios del sistema necesarios para Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        /**
         * Bloque de petición inmediata de permisos al arrancar.
         * En Android 12 (API 31) o superior, se necesitan permisos específicos de SCAN y CONNECT.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), 1)
            }

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            }
        }

        // El permiso de localización es obligatorio para el escaneo BLE en versiones anteriores a la 12
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 3)
        }

        // Verificamos de nuevo y empezamos el proceso de escaneo
        checkPermissionsAndStartScan()
    }

    /**
     * Determina qué permisos faltan y los solicita formalmente.
     */
    private fun checkPermissionsAndStartScan() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Si ya tenemos todos los permisos concedidos, iniciamos el escaneo directamente
        if (permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else {
            // Si falta alguno, lanzamos el cuadro de diálogo del sistema
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Inicia la búsqueda de dispositivos Bluetooth cercanos.
     */
    private fun startBleScan() {
        Log.d("BLE", "Intentando iniciar escaneo...")
        // Comprobación de seguridad para evitar crashes por permisos
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e("BLE", "Falta permiso BLUETOOTH_SCAN")
            return
        }
        Log.d("BLE", "VOY A ESCANEAR")
        // Llama al callback cada vez que encuentre un dispositivo
        scanner?.startScan(scanCallback)
    }

    /**
     * Callback que se ejecuta cuando el escáner encuentra un dispositivo.
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Verificamos permiso de conexión (requerido en Android 12+)
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return
            }
            
            // Obtenemos el nombre del dispositivo detectado
            val deviceName = result.device.name

            // Filtramos para conectarnos SOLO al dispositivo llamado "SynCar"
            if (deviceName == "SynCar") {
                Log.d("BLE", "Encontrado SynCar")

                // Detenemos el escaneo para no gastar batería innecesariamente
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    scanner?.stopScan(this)
                }

                // Intentamos conectar con el dispositivo encontrado
                val device = result.device
                device.connectGatt(this@MainActivity, false, gattCallback)
            }
        }
    }

    /**
     * Callback que gestiona la comunicación una vez conectados al dispositivo.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        
        // Se dispara cuando la conexión cambia de estado (se conecta o desconecta)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Conectado al servidor GATT")
                // Una vez conectados, pedimos al dispositivo que nos diga qué servicios tiene
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Desconectado del servidor GATT")
            }
        }

        // Se dispara cuando el dispositivo ha terminado de listar sus servicios
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "Servicios descubiertos con éxito")

                // Accedemos al servicio específico usando su ID único (UUID)
                val service = gatt.getService(UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))
                // Accedemos a la característica de lectura dentro de ese servicio
                val characteristic = service?.getCharacteristic(UUID.fromString("12345678-1234-5678-1234-56789abcdef1"))

                characteristic?.let {
                    // Activamos las "notificaciones": el móvil recibirá datos automáticamente cuando el dispositivo los envíe
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        gatt.setCharacteristicNotification(it, true)
                    }
                }
            }
        }

        /**
         * Se dispara CADA VEZ que recibimos datos nuevos del dispositivo.
         * (Solo funciona si setCharacteristicNotification fue exitoso)
         */
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Obtenemos los datos en bruto (bytes)
            @Suppress("DEPRECATION")
            val data = characteristic.value
            // Convertimos esos bytes a un texto legible
            val text = String(data ?: byteArrayOf())

            Log.d("BLE", "Dato recibido: $text")
            temperatura = text
        }
    }
}

/**
 * Función de interfaz (Compose) para mostrar texto en la pantalla del móvil.
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "SynCar: Buscando y conectando...",
        modifier = modifier
    )
}

/**
 * Permite ver el diseño en la pestaña 'Preview' de Android Studio sin ejecutar la app.
 */
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SynCarTheme {
        Greeting("Android")
    }
}
