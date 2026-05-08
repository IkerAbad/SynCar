package com.example.syncar

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.syncar.ui.theme.SynCarTheme

// --- PALETA DE COLORES PROFESIONAL ---
val DarkBg = Color(0xFF010409)
val SurfaceCard = Color(0xFF0D1117)
val BorderColor = Color(0xFF30363D)
val PrimaryCyan = Color(0xFF58A6FF)
val SuccessGreen = Color(0xFF3FB950)
val ErrorRed = Color(0xFFF85149)
val TextPrimary = Color(0xFFF0F6FC)
val TextSecondary = Color(0xFF8B949E)

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var repository: SynCarRepository

    private val viewModel: SynCarViewModel by viewModels {
        SynCarViewModelFactory(repository)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            Log.d("DEBUG_SYNCAR", "Permisos concedidos: ${perms.all { it.value }}")
            if (perms.all { it.value }) bleManager.startScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val dbHelper = DatabaseHelper(this)
        repository = SynCarRepository(dbHelper)

        bleManager = BleManager(
            this,
            onStatusChanged = { viewModel.setStatus(it) },
            onDataReceived = { viewModel.procesarDatoRecibido(it) }
        )

        setContent {
            SynCarTheme(darkTheme = true) {
                LaunchedEffect(Unit) {
                    viewModel.inicializarApp()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    if (!viewModel.isLoggedIn) {
                        LoginScreen(
                            onLoginSuccess = { user, pass ->
                                if (viewModel.login(user, pass)) {
                                    checkPermissions()
                                }
                            }
                        )
                    } else {
                        DashboardScreen(
                            viewModel = viewModel,
                            onLogout = {
                                if (viewModel.isJourneyActive) viewModel.finalizarTrayecto()
                                bleManager.disconnect()
                                viewModel.logout()
                            }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        val perms = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(perms)
    }

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.disconnect()
        super.onDestroy()
    }
}

@Composable
fun LoginScreen(onLoginSuccess: (String, String) -> Unit) {
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("1234") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.DirectionsCar,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = PrimaryCyan
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "SynCar System",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text("Gestión de Telemetría", color = TextSecondary, fontSize = 14.sp)
        
        Spacer(modifier = Modifier.height(48.dp))
        
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Usuario") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryCyan,
                unfocusedBorderColor = BorderColor,
                focusedLabelColor = PrimaryCyan,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Contraseña") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryCyan,
                unfocusedBorderColor = BorderColor,
                focusedLabelColor = PrimaryCyan,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        
        Spacer(modifier = Modifier.height(40.dp))
        
        Button(
            onClick = { onLoginSuccess(user, pass) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ENTRAR AL PANEL", fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun DashboardScreen(viewModel: SynCarViewModel, onLogout: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "SYNCAR DASHBOARD",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (viewModel.bleStatus.contains("Conectado")) SuccessGreen else ErrorRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        viewModel.bleStatus,
                        fontSize = 12.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            IconButton(
                onClick = onLogout,
                modifier = Modifier.background(SurfaceCard, CircleShape).border(1.dp, BorderColor, CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SENSOR GRID ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SensorCard(
                modifier = Modifier.weight(1f),
                title = "TEMPERATURA",
                value = "${viewModel.tempActual}°C",
                icon = Icons.Default.Thermostat,
                color = PrimaryCyan
            )
            SensorCard(
                modifier = Modifier.weight(1f),
                title = "HUMEDAD",
                value = "${viewModel.humActual}%",
                icon = Icons.Default.WaterDrop,
                color = PrimaryCyan
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        SensorCard(
            modifier = Modifier.fillMaxWidth(),
            title = "DISTANCIA DE SEGURIDAD",
            value = "${viewModel.distActual} cm",
            icon = Icons.Default.Radar,
            color = if ((viewModel.distActual.toFloatOrNull() ?: 100f) < 30f) ErrorRed else SuccessGreen
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- GRÁFICA EN TIEMPO REAL ---
        Text("GRÁFICA DE TEMPERATURA", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        RealTimeGraph(
            puntos = viewModel.listaPuntosGrafica,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- CONTROLES DE VIAJE ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ESTADO DEL VIAJE", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                    Text(
                        if (viewModel.isJourneyActive) "EN TRAYECTO" else "DETENIDO",
                        fontWeight = FontWeight.ExtraBold,
                        color = if (viewModel.isJourneyActive) SuccessGreen else TextPrimary,
                        fontSize = 18.sp
                    )
                }
                
                Button(
                    onClick = { if (viewModel.isJourneyActive) viewModel.finalizarTrayecto() else viewModel.iniciarTrayecto() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isJourneyActive) ErrorRed else SuccessGreen
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Icon(if (viewModel.isJourneyActive) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (viewModel.isJourneyActive) "FINALIZAR" else "INICIAR", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- SECCIÓN DE HISTORIAL Y LOGS ---
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = PrimaryCyan,
            divider = {},
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = PrimaryCyan
                )
            }
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                Text("VIAJES", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                Text("LOGS VIVO", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            if (selectedTab == 0) {
                JourneyList(viewModel)
            } else {
                LiveLogList(viewModel.listaHistorial)
            }
        }
    }
}

@Composable
fun SensorCard(modifier: Modifier, title: String, value: String, icon: ImageVector, color: Color) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceCard)
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
            Text(
                value,
                fontSize = 24.sp,
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun RealTimeGraph(puntos: List<Float>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (puntos.size < 2) return@Canvas
        
        val maxVal = (puntos.maxOrNull() ?: 50f).coerceAtLeast(40f)
        val minVal = (puntos.minOrNull() ?: 0f).coerceAtMost(10f)
        val range = (maxVal - minVal).coerceAtLeast(1f)
        
        val width = size.width
        val height = size.height
        val stepX = width / (puntos.size - 1)
        
        val path = Path().apply {
            puntos.forEachIndexed { index, valFloat ->
                val x = index * stepX
                val y = height - ((valFloat - minVal) / range * height)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = PrimaryCyan,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
fun JourneyList(viewModel: SynCarViewModel) {
    if (viewModel.listaViajes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No hay viajes registrados", color = TextSecondary, fontSize = 14.sp)
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(viewModel.listaViajes) { viaje ->
                val estados = evaluarTrayecto(viaje)
                val colorEstado = obtenerColorPrioritario(estados)
                
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.cargarGraficaViaje(viaje.id) },
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, if (viewModel.viajeSeleccionadoId == viaje.id) PrimaryCyan else BorderColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colorEstado))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Viaje #${viaje.id}", fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("${viaje.duracion}s | Media: ${viaje.tempMedia}°C", color = TextSecondary, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                    }
                    if (viewModel.viajeSeleccionadoId == viaje.id) {
                        RealTimeGraph(
                            puntos = viewModel.puntosGraficaViaje,
                            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LiveLogList(logs: List<String>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(logs) { text ->
            val type = text.split(":")[0].trim()
            val value = if (text.contains(":")) text.split(":")[1].trim() else text
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when(type) {
                        "TEMP" -> Icons.Default.Thermostat
                        "DIST" -> Icons.Default.Radar
                        "HUM" -> Icons.Default.WaterDrop
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = PrimaryCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(type, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(value, fontSize = 11.sp, color = TextSecondary)
            }
            HorizontalDivider(color = BorderColor.copy(alpha = 0.5f))
        }
    }
}
