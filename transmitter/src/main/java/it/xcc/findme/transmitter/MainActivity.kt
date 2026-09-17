package it.xcc.findme.transmitter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import it.xcc.findme.core.AppConfig
import it.xcc.findme.core.ConnectionRecoveryPolicy
import it.xcc.findme.core.DeviceIdentity
import it.xcc.findme.core.DeviceRole
import it.xcc.findme.core.FindMeRepository
import it.xcc.findme.transmitter.screen.ScreenProjectionRuntime
import it.xcc.findme.transmitter.screen.ScreenProjectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val FindMeColorScheme = darkColorScheme(
    primary = Color(0xFF00D9FF),
    onPrimary = Color(0xFF001B24),
    secondary = Color(0xFF4D8DFF),
    background = Color(0xFF020812),
    onBackground = Color(0xFFE8F7FF),
    surface = Color(0xFF091522),
    onSurface = Color(0xFFE8F7FF),
    surfaceVariant = Color(0xFF10263A),
    onSurfaceVariant = Color(0xFFAAC9D8),
    error = Color(0xFFFF6B8A),
)

class MainActivity : ComponentActivity() {
    private lateinit var identity: DeviceIdentity
    private var repository: FindMeRepository? = null
    private var message by mutableStateOf("")
    private var ready by mutableStateOf(false)
    private var monitoring by mutableStateOf(false)
    private var notificationsEnabled by mutableStateOf(false)
    private var monitoringPermissionsGranted by mutableStateOf(false)
    private var backgroundLocationGranted by mutableStateOf(false)
    private var batteryOptimizationDisabled by mutableStateOf(false)
    private var accessQuestion by mutableStateOf<String?>(null)
    private var accessGranted by mutableStateOf(false)
    private var accessLoading by mutableStateOf(false)
    private var initializationJob: Job? = null
    private var screenProjectionState by mutableStateOf(ScreenProjectionState.UNAVAILABLE)
    private var pendingScreenProjectionRequest = false

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            refreshPermissionStatus()
            message = if (result.values.all { it }) {
                "Permessi concessi. Puoi avviare il monitoraggio."
            } else {
                "Servono camera, microfono e posizione per avviare il monitoraggio."
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPermissionStatus()
            message = if (granted) {
                "Posizione in background abilitata."
            } else {
                "Posizione sempre non concessa; il servizio usa comunque la posizione quando attivo."
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshNotificationStatus()
            message = if (notificationsEnabled) {
                "Notifiche FindMe abilitate."
            } else {
                "Notifiche FindMe disabilitate."
            }
        }

    private val screenProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            identity.screenProjectionOnboardingAttempted = true
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    MonitoringService.screenAuthorizationIntent(this, result.resultCode, data),
                )
                message = "Autorizzazione mirroring ricevuta."
            } else {
                message = "Mirroring non autorizzato. Puoi riattivarlo quando vuoi."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identity = DeviceIdentity(this)
        monitoring = identity.monitoringEnabled
        pendingScreenProjectionRequest =
            intent.getBooleanExtra(EXTRA_REQUEST_SCREEN_PROJECTION, false)
        if (AppConfig.isConfigured) {
            repository = runCatching { FindMeRepository() }.getOrNull()
        }
        setContent {
            MaterialTheme(colorScheme = FindMeColorScheme) {
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "FindMe",
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                        )
                        if (!AppConfig.isConfigured) {
                            Text("Configurazione mancante: completa local.properties seguendo README.md.")
                            return@Column
                        }
                        if (!ready || accessLoading && accessQuestion == null) {
                            Text("Inizializzazione sicura del dispositivo…")
                        } else if (!accessGranted) {
                            AccessGate()
                        } else {
                            DeviceDashboard()
                        }
                        if (message.isNotBlank()) Text(message)
                    }
                }
            }
        }
        lifecycleScope.launch {
            ScreenProjectionRuntime.state.collect { screenProjectionState = it }
        }
        initializeDevice()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_SCREEN_PROJECTION, false)) {
            pendingScreenProjectionRequest = true
            if (accessGranted) requestScreenProjection()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    override fun onDestroy() {
        initializationJob?.cancel()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun AccessGate() {
        var answer by androidx.compose.runtime.remember { mutableStateOf("") }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (accessQuestion != null) {
                    Text(
                        accessQuestion.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp),
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fieldColor = MaterialTheme.colorScheme.onSurface
                        BasicTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = fieldColor),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (answer.isEmpty()) {
                                        Text(
                                            "La tua risposta",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(
                                    enabled = answer.isNotBlank() && !accessLoading,
                                    onClick = { verifyAccess(answer) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val playColor = MaterialTheme.colorScheme.onPrimary
                            Canvas(Modifier.size(20.dp)) {
                                val triangle = Path().apply {
                                    moveTo(size.width * 0.25f, 0f)
                                    lineTo(size.width, size.height / 2f)
                                    lineTo(size.width * 0.25f, size.height)
                                    close()
                                }
                                drawPath(
                                    path = triangle,
                                    color = playColor,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        "Provisioning incompleto",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "Il ricevitore non è stato configurato durante l’installazione. " +
                            "Completa il provisioning e riprova.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !accessLoading,
                        onClick = ::loadAccessChallenge,
                    ) {
                        Text("Riprova")
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DeviceDashboard() {
        var name by androidx.compose.runtime.remember { mutableStateOf(identity.name) }
        var pairingCode by androidx.compose.runtime.remember { mutableStateOf("") }
        val isOwner = DeviceOwnerSupport.isDeviceOwner(this)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Dispositivo",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        identity.name = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nome dispositivo") },
                )
                Text(
                    if (isOwner) "Device Owner attivo" else "Modalità standard",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "ID ${identity.id}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Ricevitore",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = { pairingCode = it.uppercase().take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nuovo codice ricevitore") },
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pairingCode.length == 10,
                    onClick = { pairReceiver(pairingCode) },
                ) {
                    Text("Cambia ricevitore")
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Autorizzazioni",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                )
                PermissionSwitch(
                    title = "Monitoraggio attivo",
                    subtitle = if (monitoring) {
                        "Servizio FindMe in esecuzione"
                    } else {
                        "Servizio FindMe fermo"
                    },
                    checked = monitoring,
                    onChange = { enabled ->
                        if (enabled) startMonitoring() else stopMonitoring()
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Mirroring schermo", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (screenProjectionState) {
                                ScreenProjectionState.UNAVAILABLE ->
                                    "Non autorizzato: richiede conferma Android"
                                ScreenProjectionState.STARTING -> "Attivazione in corso…"
                                ScreenProjectionState.READY -> "Pronto per le richieste remote"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        enabled = monitoring &&
                            monitoringPermissionsGranted &&
                            screenProjectionState == ScreenProjectionState.UNAVAILABLE,
                        onClick = ::requestScreenProjection,
                    ) {
                        Text(
                            if (screenProjectionState == ScreenProjectionState.READY) {
                                "Pronto"
                            } else {
                                "Riattiva"
                            },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                PermissionSwitch(
                    title = "Camera, microfono e GPS",
                    subtitle = "Necessari per il monitoraggio",
                    checked = monitoringPermissionsGranted,
                    onChange = { if (it) requestPermissions() else openAppSettings() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                PermissionSwitch(
                    title = "Posizione sempre",
                    subtitle = "Aggiornamento anche in background",
                    checked = backgroundLocationGranted,
                    onChange = { if (it) requestBackgroundLocation() else openAppSettings() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                PermissionSwitch(
                    title = "Nessuna restrizione batteria",
                    subtitle = "Evita che Android sospenda connessione e heartbeat",
                    checked = batteryOptimizationDisabled,
                    onChange = {
                        if (it) requestBatteryOptimizationExemption()
                        else openBatteryOptimizationSettings()
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                PermissionSwitch(
                    title = "Notifiche",
                    subtitle = "Gestisce solo le notifiche FindMe",
                    checked = notificationsEnabled,
                    onChange = {
                        if (it) requestNotificationPermission() else openNotificationSettings()
                    },
                )
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PermissionSwitch(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    private fun initializeDevice() {
        initializationJob?.cancel()
        initializationJob = lifecycleScope.launch {
            var failures = 0
            while (isActive && !ready) {
                try {
                    repository!!.ensureAuthenticated()
                    repository!!.registerDevice(
                        identity.id,
                        identity.name,
                        DeviceRole.TRANSMITTER,
                    )
                    ready = true
                    message = ""
                    loadAccessChallenge()
                    if (monitoring) {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            MonitoringService.intent(this@MainActivity),
                        )
                    }
                } catch (error: Throwable) {
                    failures++
                    Log.e(TAG, "Device initialization failed; retrying", error)
                    message = "Connessione temporaneamente assente. Riprovo automaticamente…"
                    delay(ConnectionRecoveryPolicy.retryDelayMs(failures))
                }
            }
        }
    }

    private fun pairReceiver(pairingCode: String) {
        lifecycleScope.launch {
            accessLoading = true
            runCatching { repository!!.pairWithReceiver(identity.id, pairingCode) }
                .onSuccess {
                    message = "Sincronizzato con ${it.receiverName}."
                    accessGranted = false
                    loadAccessChallenge()
                }
                .onFailure { message = it.message ?: "Sincronizzazione non riuscita." }
            accessLoading = false
        }
    }

    private fun loadAccessChallenge() {
        lifecycleScope.launch {
            accessLoading = true
            runCatching { repository!!.receiverAccess(identity.id) }
                .onSuccess {
                    accessQuestion = it.question
                    message = ""
                }
                .onFailure {
                    Log.e(TAG, "Unable to load receiver access challenge", it)
                    accessQuestion = null
                    val provisionedCode = identity.provisionedReceiverCode
                    if (provisionedCode != null) {
                        pairProvisionedReceiver(provisionedCode)
                    } else {
                        message = "Provisioning ricevitore mancante."
                    }
                }
            accessLoading = false
        }
    }

    private fun pairProvisionedReceiver(pairingCode: String) {
        lifecycleScope.launch {
            accessLoading = true
            runCatching { repository!!.pairWithReceiver(identity.id, pairingCode) }
                .onSuccess {
                    identity.provisionedReceiverCode = null
                    loadAccessChallenge()
                }
                .onFailure {
                    Log.e(TAG, "Unable to apply provisioned receiver", it)
                    message = "Provisioning ricevitore non riuscito."
                }
            accessLoading = false
        }
    }

    private fun verifyAccess(answer: String) {
        lifecycleScope.launch {
            accessLoading = true
            runCatching { repository!!.receiverAccess(identity.id, answer) }
                .onSuccess {
                    if (it.unlocked == true) {
                        accessGranted = true
                        message = ""
                        requestInitialScreenProjectionIfNeeded()
                    } else {
                        message = ""
                    }
                }
                .onFailure { message = it.message ?: "Verifica non riuscita." }
            accessLoading = false
        }
    }

    private fun requestPermissions() {
        DeviceOwnerSupport.grantMonitoringPermissions(this)
        permissionsLauncher.launch(MonitoringService.REQUIRED_PERMISSIONS)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
        )
    }

    private fun refreshNotificationStatus() {
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun refreshPermissionStatus() {
        refreshNotificationStatus()
        monitoringPermissionsGranted = MonitoringService.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        backgroundLocationGranted =
            Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        batteryOptimizationDisabled =
            getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 29) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun startMonitoring() {
        if (!monitoringPermissionsGranted) {
            requestPermissions()
            message = "Concedi prima camera, microfono e GPS."
            return
        }
        identity.monitoringEnabled = true
        monitoring = true
        ContextCompat.startForegroundService(this, MonitoringService.intent(this))
        message = "Monitoraggio avviato."
        requestInitialScreenProjectionIfNeeded()
    }

    private fun stopMonitoring() {
        identity.monitoringEnabled = false
        monitoring = false
        stopService(MonitoringService.intent(this))
        message = "Monitoraggio fermato."
    }

    private fun requestInitialScreenProjectionIfNeeded() {
        if (!accessGranted || !monitoring || !monitoringPermissionsGranted) return
        if (screenProjectionState != ScreenProjectionState.UNAVAILABLE) return
        if (identity.screenProjectionOnboardingAttempted && !pendingScreenProjectionRequest) return
        requestScreenProjection()
    }

    private fun requestScreenProjection() {
        pendingScreenProjectionRequest = false
        if (!monitoring || !monitoringPermissionsGranted) {
            message = "Avvia prima il monitoraggio e concedi i permessi richiesti."
            return
        }
        identity.screenProjectionOnboardingAttempted = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        Log.i(TAG, "Requesting MediaProjection authorization")
        screenProjectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        const val TAG = "FindMeTransmitter"
        const val EXTRA_REQUEST_SCREEN_PROJECTION = "request_screen_projection"
    }
}
