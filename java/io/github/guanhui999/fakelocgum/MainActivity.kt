package io.github.guanhui999.fakelocgum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.guanhui999.fakelocgum.engine.JoystickController
import io.github.guanhui999.fakelocgum.overlay.JoystickOverlayService
import io.github.guanhui999.fakelocgum.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FakeLocTheme { FakeLocApp() } }
    }
}

private enum class Screen { HOME, MAP, SETTINGS }

@Composable
private fun FakeLocApp(viewModel: MainViewModel = viewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsState()
    val serviceBound by viewModel.serviceBound.collectAsState()
    val delivery by viewModel.delivery.collectAsStateWithLifecycle()
    val addressState by viewModel.addressState.collectAsState()
    val addressDetail by viewModel.addressDetail.collectAsState()
    val overlayMessage by JoystickOverlayService.state.collectAsState()
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = (context.applicationContext as App).store
    val pageJoystick = remember { JoystickController(store) }
    // This stores permission prompt history only. Spoofing/selection still use RemoteStore.
    val permissionHistory = remember { context.getSharedPreferences("permission_prompts", 0) }
    val snack = remember { SnackbarHostState() }
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    val screen = runCatching { Screen.valueOf(screenName) }.getOrDefault(Screen.HOME)
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var pendingLocation by rememberSaveable { mutableStateOf("") }
    var pendingOverlay by rememberSaveable { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    var denialMessage by remember { mutableStateOf("") }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val fine = remember(permissionRefresh) { granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val coarse = remember(permissionRefresh) { granted(Manifest.permission.ACCESS_COARSE_LOCATION) }
    val overlayAllowed = remember(permissionRefresh) { Settings.canDrawOverlays(context) }
    val locationLabel = when {
        fine -> "已授权（精确位置）"
        coarse -> "已授权（大致位置）"
        permissionHistory.getBoolean("location_requested", false) -> "已拒绝（可到设置授权）"
        else -> "未授权"
    }
    val overlayLabel = when {
        overlayAllowed -> "已授权"
        permissionHistory.getBoolean("overlay_requested", false) -> "已拒绝（可到设置授权）"
        else -> "未授权"
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionRefresh++
        if (pendingOverlay) {
            pendingOverlay = false
            if (Settings.canDrawOverlays(context)) {
                store.update { it.copy(overlayEnabled = true, joystickSpeedMps = 0f) }
            } else {
                store.update { it.copy(overlayEnabled = false, joystickSpeedMps = 0f) }
                denialMessage = "未获得悬浮窗权限，仍可使用页面内摇杆。"
                showDenied = true
            }
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRefresh++
        val allowed = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val action = pendingLocation
        pendingLocation = ""
        if (allowed && action == "gps") viewModel.useRealLocation()
        if (action == "map") screenName = Screen.MAP.name
        if (!allowed) {
            denialMessage = "位置权限未授权。可继续手动输入坐标或地图选点；真实 GPS / 地图定位点需要授权。"
            showDenied = true
        }
    }
    fun requestLocation(action: String) {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            if (action == "gps") viewModel.useRealLocation()
            if (action == "map") screenName = Screen.MAP.name
            return
        }
        val asked = permissionHistory.getBoolean("location_requested", false)
        val canExplain = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) ||
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (asked && !canExplain) {
            denialMessage = "位置权限已拒绝或被系统限制，请到应用设置中开启。手动选点仍可使用。"
            showDenied = true
            if (action == "map") screenName = Screen.MAP.name
            return
        }
        pendingLocation = action
        permissionHistory.edit().putBoolean("location_requested", true).apply()
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun toggleOverlay(enabled: Boolean) {
        pageJoystick.stop()
        if (!enabled || Settings.canDrawOverlays(context)) {
            store.update { it.copy(overlayEnabled = enabled, joystickSpeedMps = 0f) }
        } else {
            pendingOverlay = true
            permissionHistory.edit().putBoolean("overlay_requested", true).apply()
            settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { permissionRefresh++; resumed = true }
            if (event == Lifecycle.Event.ON_STOP) { resumed = false; pageJoystick.stop() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pageJoystick.stop() }
    }
    LaunchedEffect(screen) { if (screen != Screen.HOME) pageJoystick.stop() }
    LaunchedEffect(Unit) {
        if (!permissionHistory.getBoolean("location_requested", false) && !fine && !coarse) requestLocation("home")
    }
    LaunchedEffect(Unit) { viewModel.messages.collect { snack.showSnackbar(context.getString(it)) } }
    // Stop is also observed in the Service, so it works while Activity is backgrounded.
    LaunchedEffect(config.playing, config.overlayEnabled, overlayAllowed, resumed) {
        if (config.playing && config.overlayEnabled && overlayAllowed && resumed) {
            pageJoystick.stop()
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                !permissionHistory.getBoolean("notification_requested", false)) {
                permissionHistory.edit().putBoolean("notification_requested", true).apply()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            JoystickOverlayService.show(context)
        }
    }
    BackHandler(enabled = screen != Screen.HOME) { screenName = Screen.HOME.name }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when (screen) {
                Screen.MAP -> MapPickerScreen(
                    initialLatitude = config.latitude, initialLongitude = config.longitude, config = config,
                    onPicked = { lat, lon, address -> viewModel.applyMapPick(lat, lon, address); screenName = Screen.HOME.name },
                    onOpenSettings = { screenName = Screen.SETTINGS.name }, onBack = { screenName = Screen.HOME.name },
                )
                Screen.SETTINGS -> SettingsScreen(config = config, onUpdate = viewModel::update,
                    onBack = { screenName = Screen.HOME.name })
                Screen.HOME -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) {
                            Text("位置权限：$locationLabel", style = MaterialTheme.typography.bodyMedium)
                            Text("悬浮窗：$overlayLabel", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                if (!fine && !coarse) TextButton(onClick = { requestLocation("home") }) { Text("申请位置权限") }
                                TextButton(onClick = {
                                    settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")))
                                }) { Text("系统设置") }
                            }
                        }
                    }
                    HomeScreen(config = config, installedApps = installedApps, serviceBound = serviceBound,
                        delivery = delivery, addressState = addressState, addressDetail = addressDetail, onUpdate = viewModel::update,
                        onTogglePlaying = viewModel::togglePlaying, onSelectTarget = viewModel::setTargetPackage,
                        onClearTarget = viewModel::clearTargetPackage, onSetTargets = viewModel::setTargetPackages, onUseRealGps = { requestLocation("gps") },
                        onPaste = viewModel::applyClipboard, onOpenMap = { requestLocation("map") },
                        onOpenSettings = { screenName = Screen.SETTINGS.name }, onSaveCoordinates = viewModel::commitCoordinates,
                        onPickSavedPlace = viewModel::pickSavedPlace, onTogglePin = viewModel::togglePinned,
                        onRetryAddress = viewModel::retryAddress, onRetryDelivery = viewModel::reload,
                        overlayAllowed = overlayAllowed, overlayStatus = overlayMessage,
                        onOverlayToggle = ::toggleOverlay, onShowOverlay = { JoystickOverlayService.show(context) },
                        onJoystick = pageJoystick::input, onJoystickStop = pageJoystick::stop)
                }
            }
        }
    }
    if (showDenied) AlertDialog(onDismissRequest = { showDenied = false }, title = { Text("权限未授权") },
        text = { Text(denialMessage) }, confirmButton = {
            TextButton(onClick = {
                showDenied = false
                settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            }) { Text("打开系统设置") }
        }, dismissButton = { TextButton(onClick = { showDenied = false }) { Text("继续使用") } })
}
