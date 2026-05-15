package com.example.syncar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.syncar.ui.theme.SynCarTheme
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

// --- PALETA DE COLORES (SynCar Branding Refinado) ---
val DarkBg = Color(0xFF010409)
val SurfaceCard = Color(0xFF0D1117)
val BorderColor = Color(0xFF30363D)
val PrimaryCyan = Color(0xFF00E5FF)
val SuccessGreen = Color(0xFF00E676)
val WarningOrange = Color(0xFFFFAB40)
val ErrorRed = Color(0xFFFF5252)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF94A3B8)
val ChartTemp = Color(0xFF00E5FF)
val ChartHum = Color(0xFFE040FB)

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var repository: SynCarRepository
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val viewModel: SynCarViewModel by viewModels {
        SynCarViewModelFactory(repository)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) {
                bleManager.startScan()
                startLocationUpdates()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = SynCarRepository(DatabaseHelper(this))
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        bleManager = BleManager(
            this,
            onStatusChanged = { viewModel.setStatus(it) },
            onDataReceived = { viewModel.procesarDatoRecibido(it) }
        )
        
        viewModel.onSendCommand = { bleManager.sendData(it) }

        setContent {
            SynCarTheme(darkTheme = true) {
                LaunchedEffect(Unit) {
                    viewModel.inicializarApp()
                    checkPermissions()
                }

                Surface(modifier = Modifier.fillMaxSize(), color = DarkBg) {
                    when {
                        viewModel.isCreatingAdmin -> CreateAdminScreen(viewModel)
                        viewModel.showUserManagement -> UserManagementScreen(viewModel)
                        !viewModel.isLoggedIn -> LoginScreen(viewModel)
                        viewModel.trayectoSeleccionado != null -> JourneyDetailScreen(viewModel)
                        else -> DashboardScreen(viewModel)
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lr = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).build()
        fusedLocationClient.requestLocationUpdates(lr, object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                res.lastLocation?.let { 
                    android.util.Log.d("SynCarGPS", "Update: ${it.latitude}, ${it.longitude}")
                    viewModel.guardarGps(it.latitude, it.longitude) 
                }
            }
        }, mainLooper)
    }

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
fun CreateAdminScreen(vm: SynCarViewModel) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(72.dp), PrimaryCyan)
        Spacer(Modifier.height(16.dp))
        Text("Configurar Administrador", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
        Text("Crea el primer usuario del sistema", color = TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))
        SynCarTextField(user, { user = it }, "Usuario")
        Spacer(Modifier.height(12.dp))
        SynCarTextField(pass, { pass = it }, "Contraseña", true)
        Spacer(Modifier.height(12.dp))
        SynCarTextField(conf, { conf = it }, "Confirmar Contraseña", true)
        Spacer(Modifier.height(32.dp))
        Button(onClick = { if (pass == conf && user.isNotBlank() && pass.isNotBlank()) vm.crearUsuario(user, pass) }, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(PrimaryCyan), shape = RoundedCornerShape(12.dp)) {
            Text("CREAR CUENTA", fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun LoginScreen(vm: SynCarViewModel) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.DirectionsCar, null, Modifier.size(80.dp), PrimaryCyan)
        Spacer(Modifier.height(16.dp))
        Text("SynCar Login", style = MaterialTheme.typography.headlineLarge, color = TextPrimary, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(48.dp))
        SynCarTextField(user, { user = it; error = false }, "Usuario")
        Spacer(Modifier.height(16.dp))
        SynCarTextField(pass, { pass = it; error = false }, "Contraseña", true)
        if (error) Text("Credenciales incorrectas", color = ErrorRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(32.dp))
        Button(onClick = { if (!vm.login(user, pass)) error = true }, Modifier.fillMaxWidth().height(60.dp), colors = ButtonDefaults.buttonColors(PrimaryCyan), shape = RoundedCornerShape(16.dp)) {
            Text("ACCEDER", fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, color = DarkBg)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.loginAsGuest() }) { Text("MODO INVITADO", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { vm.showUserManagement = true }) { Text("NUEVO USUARIO", color = PrimaryCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun AlertPopup(visible: Boolean, message: String?, isCritical: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(
        visible = visible && message != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        val color = if (isCritical) ErrorRed else WarningOrange
        
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
            label = "alpha"
        )

        // Movido un poco más abajo para no tapar los botones superiores
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 60.dp).zIndex(100f)) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(SurfaceCard.copy(alpha = 0.98f)),
                border = BorderStroke(1.dp, color.copy(alpha = alpha)),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(16.dp)
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(
                            if (isCritical) Icons.Default.Warning else Icons.Default.Info, 
                            null, 
                            tint = color, 
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (isCritical) "SISTEMA CRÍTICO" else "AVISO DE SEGURIDAD", fontSize = 11.sp, fontWeight = FontWeight.Black, color = color, letterSpacing = 1.2.sp)
                        Text(message ?: "", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss, Modifier.background(Color.White.copy(0.05f), CircleShape).size(36.dp)) {
                        Icon(Icons.Default.Close, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: SynCarViewModel) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column {
                    Text("SYNCAR", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.usuarioActivo?.username ?: "Invitado", fontSize = 12.sp, color = PrimaryCyan, fontWeight = FontWeight.Bold)
                        if (vm.isGuest) Text(" (MODO INVITADO)", fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if(vm.bleStatus=="RECEIVING DATA") SuccessGreen else if(vm.bleStatus=="CONNECTED") PrimaryCyan else ErrorRed))
                        Spacer(Modifier.width(6.dp))
                        Text(vm.bleStatus, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                    }
                }
                Row {
                    if (vm.isAdmin) {
                        IconButton(onClick = { vm.showUserManagement = true }, Modifier.background(SurfaceCard, CircleShape).border(1.dp, BorderColor, CircleShape).size(40.dp)) { Icon(Icons.Default.People, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { vm.logout() }, Modifier.background(SurfaceCard, CircleShape).border(1.dp, BorderColor, CircleShape).size(40.dp)) { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                }
            }
            
            Spacer(Modifier.height(32.dp))
            
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                SensorCard(Modifier.weight(1f), "TEMPERATURA", vm.tempActual, "°C", Icons.Default.Thermostat, PrimaryCyan)
                SensorCard(Modifier.weight(1f), "HUMEDAD", vm.humActual, "%", Icons.Default.WaterDrop, PrimaryCyan)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                val d = vm.distActual.toFloatOrNull() ?: 100f
                SensorCard(Modifier.weight(1f), "DISTANCIA", vm.distActual, "cm", Icons.Default.Radar, if(d < 30f) ErrorRed else SuccessGreen)
                StatusCard(Modifier.weight(1f), "PARKING", vm.isParkingActive, "ACTIVADO", "INACTIVO", Icons.Default.LocalParking, SuccessGreen)
            }
            
            Spacer(Modifier.height(28.dp))
            Text("CONTROLES DE SISTEMA", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlButton(if(vm.isParkingActive) "Park Off" else "Park On", Icons.Default.LocalParking, { vm.toggleParking() }, if(vm.isParkingActive) ErrorRed else SuccessGreen)
                ControlButton(if(vm.isSystemActive) "Apagar" else "Encender", Icons.Default.PowerSettingsNew, { vm.toggleSystem() }, if(vm.isSystemActive) ErrorRed else SuccessGreen)
                ControlButton("Buzzer Test", Icons.AutoMirrored.Filled.VolumeUp, { vm.testBuzzer() }, PrimaryCyan)
            }
            
            Spacer(Modifier.height(32.dp))
            Text("TELEMETRÍA EN TIEMPO REAL", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))

            val safeTempPoints by remember { derivedStateOf { vm.puntosTemp.toList() } }
            val safeHumPoints by remember { derivedStateOf { vm.puntosHum.toList() } }

            Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)).background(SurfaceCard).border(1.dp, BorderColor, RoundedCornerShape(24.dp)).padding(16.dp)) {
                CombinedTelemetryGraph(safeTempPoints, safeHumPoints, Modifier.fillMaxSize())
            }
            
            Spacer(Modifier.height(32.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(SurfaceCard), border = BorderStroke(1.dp, if(vm.isJourneyActive) SuccessGreen.copy(0.3f) else BorderColor), shape = RoundedCornerShape(24.dp)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("SESIÓN DE TRAYECTO", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            text = if (vm.isWaitingForFirstGps) "ESPERANDO GPS..." else if (vm.isJourneyActive) "REGISTRANDO..." else "LISTO PARA INICIAR",
                            color = if (vm.isWaitingForFirstGps) WarningOrange else if (vm.isJourneyActive) SuccessGreen else TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp
                        )
                    }
                    Button(
                        onClick = { if(vm.isJourneyActive) vm.finalizarTrayecto() else vm.iniciarTrayecto() },
                        enabled = !vm.isWaitingForFirstGps || !vm.isJourneyActive, // Permitir iniciar siempre, pero no detener si esperamos el primer fix
                        colors = ButtonDefaults.buttonColors(if(vm.isJourneyActive) ErrorRed else SuccessGreen),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(if(vm.isJourneyActive) Icons.Default.Stop else Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if(vm.isJourneyActive) "DETENER" else "INICIAR", fontWeight = FontWeight.Black)
                    }
                }
            }
            
            Spacer(Modifier.height(32.dp))
            var showConsole by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("HISTORIAL DE VIAJES", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                TextButton(onClick = { showConsole = !showConsole }) {
                    Text(if(showConsole) "CERRAR CONSOLA" else "CONSOLA TÉCNICA", color = PrimaryCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (showConsole) {
                val safeLogs by remember { derivedStateOf { vm.listaHistorial.toList() } }
                Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(0.3f)).border(1.dp, BorderColor, RoundedCornerShape(16.dp)).padding(12.dp)) {
                    LiveLogList(safeLogs)
                }
                Spacer(Modifier.height(16.dp))
            }
            
            JourneyList(vm)
            Spacer(Modifier.height(32.dp))
        }
        AlertPopup(visible = vm.alertMessage != null, message = vm.alertMessage, isCritical = vm.isCriticalAlert, onDismiss = { vm.dismissAlert() })
    }
}

@Composable
fun JourneyDetailScreen(vm: SynCarViewModel) {
    val t = vm.trayectoSeleccionado ?: return
    Column(Modifier.fillMaxSize().padding(16.dp).padding(top = 32.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.trayectoSeleccionado = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
            Text("Detalles del Viaje", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(24.dp))
        
        // Mapa con Polyline
        Card(Modifier.fillMaxWidth().height(260.dp), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, BorderColor)) {
            if (vm.puntosGpsViaje.isEmpty()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.2f)), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Map, null, tint = TextSecondary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Trayecto demasiado corto para registrar GPS", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            } else {
                val startPos = LatLng(vm.puntosGpsViaje.first().first, vm.puntosGpsViaje.first().second)
                val lastPos = LatLng(vm.puntosGpsViaje.last().first, vm.puntosGpsViaje.last().second)
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(startPos, 16f) },
                    properties = MapProperties(mapType = MapType.TERRAIN, isMyLocationEnabled = false),
                    uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false)
                ) {
                    Polyline(points = vm.puntosGpsViaje.map { LatLng(it.first, it.second) }, color = PrimaryCyan, width = 10f)
                    Marker(state = rememberMarkerState(position = startPos), title = "Inicio", icon = com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(com.google.android.gms.maps.model.BitmapDescriptorFactory.HUE_CYAN))
                    Marker(state = rememberMarkerState(position = lastPos), title = "Fin")
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Text("TIEMPOS Y DURACIÓN", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            DetailCard(Modifier.weight(1f), "INICIO", t.inicio.takeLast(8), Icons.Default.Schedule)
            DetailCard(Modifier.weight(1f), "FIN", t.fin.takeLast(8), Icons.Default.EventAvailable)
            DetailCard(Modifier.weight(1f), "DURACIÓN", SynCarFormatter.formatDuration(t.duracion), Icons.Default.Timer)
        }

        Spacer(Modifier.height(24.dp))
        Text("ESTADÍSTICAS DE TEMPERATURA", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            DetailCard(Modifier.weight(1f), "MÍNIMA", "${SynCarFormatter.formatValue("TEMP", t.stats.minTemp)}°C", Icons.Default.ArrowDownward)
            DetailCard(Modifier.weight(1f), "MEDIA", "${SynCarFormatter.formatValue("TEMP", t.stats.avgTemp)}°C", Icons.Default.Thermostat)
            DetailCard(Modifier.weight(1f), "MÁXIMA", "${SynCarFormatter.formatValue("TEMP", t.stats.maxTemp)}°C", Icons.Default.ArrowUpward)
        }

        Spacer(Modifier.height(24.dp))
        Text("ESTADÍSTICAS DE HUMEDAD", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            DetailCard(Modifier.weight(1f), "MÍNIMA", "${SynCarFormatter.formatValue("HUM", t.stats.minHum)}%", Icons.Default.ArrowDownward)
            DetailCard(Modifier.weight(1f), "MEDIA", "${SynCarFormatter.formatValue("HUM", t.stats.avgHum)}%", Icons.Default.WaterDrop)
            DetailCard(Modifier.weight(1f), "MÁXIMA", "${SynCarFormatter.formatValue("HUM", t.stats.maxHum)}%", Icons.Default.ArrowUpward)
        }
        
        Spacer(Modifier.height(24.dp))
        DetailCard(Modifier.fillMaxWidth(), "DISTANCIA MÍNIMA DETECTADA", "${SynCarFormatter.formatValue("DIST", t.stats.minDist)}cm", Icons.Default.Radar)

        Spacer(Modifier.height(32.dp))
        Text("TELEMETRÍA DEL TRAYECTO", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(16.dp))

        val safeTempViaje by remember { derivedStateOf { vm.puntosTempViaje.toList() } }
        val safeHumViaje by remember { derivedStateOf { vm.puntosHumViaje.toList() } }

        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceCard).padding(16.dp)) {
            CombinedTelemetryGraph(safeTempViaje, safeHumViaje, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun UserManagementScreen(vm: SynCarViewModel) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var editId by remember { mutableIntStateOf(-1) }
    val ctx = LocalContext.current
    
    Column(Modifier.fillMaxSize().padding(16.dp).padding(top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.showUserManagement = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextPrimary) }
            Text("Gestión de Usuarios", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(SurfaceCard), border = BorderStroke(1.dp, BorderColor), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(if(editId == -1) "Añadir Nuevo Usuario" else "Editar Usuario", color = PrimaryCyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                SynCarTextField(user, { user = it }, "Nombre de Usuario")
                Spacer(Modifier.height(8.dp))
                SynCarTextField(pass, { pass = it }, "Contraseña", true)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (editId == -1) { 
                            if(vm.crearUsuario(user, pass)) { 
                                user=""; pass=""; 
                                if (!vm.isLoggedIn) vm.showUserManagement = false 
                            } else {
                                Toast.makeText(ctx, "Datos inválidos o usuario duplicado", Toast.LENGTH_SHORT).show()
                            }
                        } else { 
                            vm.editarUsuario(editId, user, pass.ifBlank { null }); 
                            user=""; pass=""; editId=-1 
                        }
                    }, 
                    Modifier.fillMaxWidth(), 
                    colors = ButtonDefaults.buttonColors(PrimaryCyan)
                ) { Text(if(editId == -1) "CREAR CUENTA" else "ACTUALIZAR") }
            }
        }
        
        if (vm.isLoggedIn && vm.isAdmin) {
            Spacer(Modifier.height(24.dp))
            Text("Usuarios Registrados", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            val users by remember { derivedStateOf { vm.listaUsuarios.toList() } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(users) { u ->
                    UserCard(u, vm.usuarioActivo?.id == u.id, { 
                        // Cambio rápido solo para admin
                        vm.cambiarUsuarioAdmin(u)
                    }, { editId = u.id; user = u.username }, {
                        val err = vm.borrarUsuario(u.id)
                        if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

@Composable
fun SynCarTextField(v: String, onV: (String) -> Unit, l: String, pass: Boolean = false) {
    OutlinedTextField(v, onV, label = { Text(l) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), visualTransformation = if (pass) PasswordVisualTransformation() else VisualTransformation.None, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryCyan, unfocusedBorderColor = BorderColor, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedLabelColor = PrimaryCyan, unfocusedLabelColor = TextSecondary))
}

@Composable
fun SensorCard(m: Modifier, t: String, v: String, u: String, i: ImageVector, c: Color) {
    Card(m.shadow(12.dp, RoundedCornerShape(20.dp), spotColor = c.copy(0.2f)), colors = CardDefaults.cardColors(SurfaceCard), border = BorderStroke(1.dp, BorderColor), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Icon(i, null, tint = c, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(12.dp))
            Text(t, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(v, fontSize = 24.sp, color = TextPrimary, fontWeight = FontWeight.ExtraBold)
                if (v != SynCarFormatter.WAITING) Text(" $u", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun StatusCard(m: Modifier, t: String, active: Boolean, aText: String, iText: String, i: ImageVector, c: Color) {
    Card(m, colors = CardDefaults.cardColors(SurfaceCard), border = BorderStroke(1.dp, if(active) c.copy(0.4f) else BorderColor), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Icon(i, null, tint = if(active) c else TextSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(12.dp))
            Text(t, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
            Text(if(active) aText else iText, fontSize = 18.sp, color = if(active) c else TextSecondary, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun DetailCard(modifier: Modifier, t: String, v: String, i: ImageVector) {
    Card(modifier, colors = CardDefaults.cardColors(SurfaceCard), border = BorderStroke(1.dp, BorderColor), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Icon(i, null, tint = PrimaryCyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(t, fontSize = 9.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            Text(v, fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun ControlButton(t: String, i: ImageVector, onClick: () -> Unit, c: Color) {
    OutlinedButton(onClick, Modifier.height(42.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, c.copy(0.5f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = c)) {
        Icon(i, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(t, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun UserCard(u: Usuario, active: Boolean, onS: () -> Unit, onE: () -> Unit, onD: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onS() }, colors = CardDefaults.cardColors(if(active) PrimaryCyan.copy(0.1f) else SurfaceCard), border = BorderStroke(1.dp, if(active) PrimaryCyan else BorderColor), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(if(active) PrimaryCyan else BorderColor), Alignment.Center) { Text(u.username.take(1).uppercase(), color = if(active) DarkBg else TextPrimary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Text(u.username, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = TextPrimary)
            IconButton(onE) { Icon(Icons.Default.Edit, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) }
            IconButton(onD) { Icon(Icons.Default.Delete, null, tint = ErrorRed, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun CombinedTelemetryGraph(puntosTemp: List<Float>, puntosHum: List<Float>, modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (puntosTemp.isEmpty() && puntosHum.isEmpty()) return@Canvas
            
            val tempSnapshot = puntosTemp
            val humSnapshot = puntosHum

            // Guías de fondo (sutiles)
            val guides = 4
            for (i in 0..guides) {
                val y = size.height * i / guides
                drawLine(BorderColor.copy(0.3f), start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }

            // Dibujar Temperatura (Cyan)
            if (tempSnapshot.size >= 2) {
                val maxT = (tempSnapshot.maxOrNull() ?: 50f).coerceAtLeast(40f) + 5f
                val minT = (tempSnapshot.minOrNull() ?: 10f).coerceAtMost(20f) - 5f
                val rangeT = maxT - minT
                val stepX = size.width / (tempSnapshot.size - 1).toFloat()
                val path = Path().apply {
                    tempSnapshot.forEachIndexed { i, v ->
                        val x = i * stepX
                        val y = size.height - ((v.coerceIn(minT, maxT) - minT) / rangeT * size.height)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, ChartTemp, style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawPath(path, ChartTemp.copy(0.15f), style = Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            
            // Dibujar Humedad (Violeta)
            if (humSnapshot.size >= 2) {
                val maxH = 100f
                val minH = 0f
                val rangeH = maxH - minH
                val stepX = size.width / (humSnapshot.size - 1).toFloat()
                val path = Path().apply {
                    humSnapshot.forEachIndexed { i, v ->
                        val x = i * stepX
                        val y = size.height - ((v.coerceIn(minH, maxH) - minH) / rangeH * size.height)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(path, ChartHum, style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        
        // Leyenda
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartTemp))
                Spacer(Modifier.width(4.dp))
                Text("TEMP", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ChartHum))
                Spacer(Modifier.width(4.dp))
                Text("HUM", fontSize = 8.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun JourneyList(vm: SynCarViewModel) {
    val journeys by remember { derivedStateOf { vm.listaViajes.toList() } }
    if (journeys.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) {
            Text("No hay viajes para este usuario", color = TextSecondary, fontSize = 12.sp)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            journeys.forEach { v ->
                val estados = evaluarTrayecto(v)
                val colorPrioritario = obtenerColorPrioritario(estados)
                
                Card(
                    Modifier.fillMaxWidth().clickable { vm.verDetalleTrayecto(v.id) },
                    colors = CardDefaults.cardColors(SurfaceCard),
                    border = BorderStroke(1.dp, BorderColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(colorPrioritario.copy(0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Route, null, tint = colorPrioritario, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#${v.id} • ${v.inicio.takeLast(8)}", fontWeight = FontWeight.Black, color = TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.size(6.dp).clip(CircleShape).background(colorPrioritario))
                            }
                            Text("${SynCarFormatter.formatDuration(v.duracion)} • ${SynCarFormatter.formatValue("TEMP", v.tempMedia)}°C • ${SynCarFormatter.formatValue("DIST", v.distMin)}cm", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { vm.borrarTrayecto(v.id) }) {
                            Icon(Icons.Default.Delete, null, tint = ErrorRed.copy(0.7f), modifier = Modifier.size(20.dp))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LiveLogList(logs: List<String>) {
    if (logs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Esperando datos...", color = TextSecondary) }
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(logs) { l -> Text(l, color = TextSecondary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRow(modifier: Modifier, horizontalArrangement: Arrangement.Horizontal = Arrangement.Start, verticalArrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement, verticalArrangement, content = { content() })
}
