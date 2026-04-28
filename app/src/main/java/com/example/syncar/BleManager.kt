package com.example.syncar

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.*

/**
 * BleManager: Clase encargada de toda la lógica de comunicación Bluetooth.
 * Separa el hardware de la interfaz de usuario (SOLID).
 */
class BleManager(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onDataReceived: (String) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    private val SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val CHARACTERISTIC_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun startScan() {
        if (!hasPermissions()) {
            Log.e("BLE", "Faltan permisos para escanear")
            return
        }
        scanner?.startScan(scanCallback)
    }

    fun stopScan() {
        if (hasPermissions()) {
            scanner?.stopScan(scanCallback)
        }
    }

    fun disconnect() {
        if (hasPermissions()) {
            bluetoothGatt?.close()
        }
        bluetoothGatt = null
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = if (hasPermissions()) result.device.name else null
            Log.d("BLE", "Dispositivo encontrado: $deviceName")
            if (deviceName == "SynCar") {
                onStatusChanged("Conectando a SynCar...")
                stopScan()
                if (hasPermissions()) {
                    bluetoothGatt = result.device.connectGatt(context, false, gattCallback)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onStatusChanged("Escaneo fallido: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatusChanged("Conectado. Descubriendo servicios...")
                if (hasPermissions()) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatusChanged("Desconectado. Reintentando escaneo...")
                startScan()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                val char = service?.getCharacteristic(CHARACTERISTIC_UUID)

                if (char != null && hasPermissions()) {
                    onStatusChanged("Servicios listos. Recibiendo datos...")
                    gatt.setCharacteristicNotification(char, true)
                    val descriptor = char.getDescriptor(CCCD_UUID)
                    descriptor?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(it)
                        }
                    }
                } else {
                    onStatusChanged("Error: Característica no encontrada")
                }
            } else {
                onStatusChanged("Error al descubrir servicios: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val raw = String(value)
            onDataReceived(raw)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val raw = String(characteristic.value ?: byteArrayOf())
            onDataReceived(raw)
        }
    }
}
