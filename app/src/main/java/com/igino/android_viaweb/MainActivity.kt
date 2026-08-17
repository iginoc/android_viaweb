package com.igino.android_viaweb

import android.Manifest
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.content.Context
import android.content.pm.PackageManager
import android.content.ComponentName
import android.view.Surface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.set
import com.igino.android_viaweb.ui.theme.Android_viawebTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.tooling.preview.Preview
import com.google.gson.Gson
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import android.app.WallpaperManager
import android.graphics.BitmapFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

private const val SCREEN_MIRROR_TAG = "INTERNAL://SCREEN_MIRROR"
private const val NOTIFICATIONS_TAG = "INTERNAL://NOTIFICATIONS"
private const val GPS_TAG = "INTERNAL://GPS"
private const val BATTERY_TAG = "INTERNAL://BATTERY"

class MainActivity : ComponentActivity() {
    private var server: EmbeddedServer<*, *>? = null
    private var currentServedPath by mutableStateOf("")
    private var isFlashlightOn = false
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraImageReader: ImageReader? = null
    private var previewImageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null

    private var photoDeferred: CompletableDeferred<File>? = null
    private var lastPhotoFile: File? = null
    private var lastLocation: Location? = null
    private val favoritePaths = mutableStateListOf<String>()
    private lateinit var favoritesManager: FavoritesManager

    private fun startLocationUpdates() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                2000L,
                1f,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lastLocation = location
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            )
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && pkgName == cn.packageName) return true
            }
        }
        return false
    }

    private fun getBatteryInfo(): Map<String, Any> {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val batteryPct = if (level != -1 && scale != -1) level / scale.toFloat() * 100 else -1f

        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
            else -> "Unknown"
        }

        val healthStr = when (health) {
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
            else -> "Unknown"
        }

        val pluggedStr = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Battery"
        }

        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // Try to get CURRENT_NOW, fallback to CURRENT_AVERAGE if 0
        var currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentNow == 0 || currentNow == Int.MIN_VALUE) {
            currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        }
        
        // On some devices current is in mA, on others in uA. 
        // If it's very large, it's likely uA.
        val currentMA = if (kotlin.math.abs(currentNow) > 10000) currentNow / 1000 else currentNow
        
        val currentAmp = currentMA / 1000.0
        val powerWatt = (voltage / 1000.0) * currentAmp

        // Detection of some common battery charging limits/protections
        val isPlugged = plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val isProtected = isPlugged && status == BatteryManager.BATTERY_STATUS_NOT_CHARGING && batteryPct > 70

        return mapOf(
            "percentage" to batteryPct.toInt(),
            "status" to statusStr,
            "health" to healthStr,
            "voltage" to if (voltage > 0) voltage else 0,
            "temperature" to temperature / 10.0,
            "technology" to technology,
            "plugged" to pluggedStr,
            "current" to kotlin.math.abs(currentMA),
            "power" to String.format(Locale.US, "%.2f", kotlin.math.abs(powerWatt)),
            "isProtected" to isProtected
        )
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ProjectionService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("resultData", result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        if (!Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startCamera()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            startLocationUpdates()
        }

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch("android.permission.POST_NOTIFICATIONS")
            }
        }

        if (!Settings.System.canWrite(this)) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:" + packageName)
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val defaultDir = File(downloadDir, "serverweb")
        if (!defaultDir.exists()) defaultDir.mkdirs()
        currentServedPath = defaultDir.absolutePath

        favoritesManager = FavoritesManager(this)
        val loadedFavorites = favoritesManager.loadFavorites()
        favoritePaths.clear()
        if (loadedFavorites.isNotEmpty()) {
            favoritePaths.addAll(loadedFavorites)
        }
        if (!favoritePaths.contains(currentServedPath)) favoritePaths.add(currentServedPath)
        if (!favoritePaths.contains(SCREEN_MIRROR_TAG)) favoritePaths.add(SCREEN_MIRROR_TAG)
        if (!favoritePaths.contains(NOTIFICATIONS_TAG)) favoritePaths.add(NOTIFICATIONS_TAG)
        if (!favoritePaths.contains(GPS_TAG)) favoritePaths.add(GPS_TAG)
        if (!favoritePaths.contains(BATTERY_TAG)) favoritePaths.add(BATTERY_TAG)

        if (!isNotificationServiceEnabled()) {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) { e.printStackTrace() }
        }

        createDefaultIndexHtml(defaultDir)
        startServer(currentServedPath)

        setContent {
            Android_viawebTheme {
                Android_viawebApp(
                    ipAddress = getIpAddress(),
                    currentServedPath = currentServedPath,
                    favoritePaths = favoritePaths,
                    onPathChanged = { newPath ->
                        if (newPath != SCREEN_MIRROR_TAG && currentServedPath == SCREEN_MIRROR_TAG) {
                            stopService(Intent(this, ProjectionService::class.java))
                        }
                        
                        when (newPath) {
                            SCREEN_MIRROR_TAG -> startScreenProjection()
                            NOTIFICATIONS_TAG -> startNotificationsServing()
                            GPS_TAG -> startGpsServing()
                            BATTERY_TAG -> startBatteryServing()
                            else -> {
                                currentServedPath = newPath
                                restartServer(newPath)
                            }
                        }
                    },
                    onToggleFavorite = { path ->
                        if (path == SCREEN_MIRROR_TAG || path == NOTIFICATIONS_TAG || path == GPS_TAG || path == BATTERY_TAG) return@Android_viawebApp
                        if (favoritePaths.contains(path)) {
                            if (path != defaultDir.absolutePath) {
                                favoritePaths.remove(path)
                                favoritesManager.saveFavorites(favoritePaths)
                            }
                        } else {
                            favoritePaths.add(path)
                            favoritesManager.saveFavorites(favoritePaths)
                        }
                    },
                    onExit = { exitApp() }
                )
            }
        }
    }

    private fun startScreenProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
        currentServedPath = SCREEN_MIRROR_TAG
    }

    private fun startNotificationsServing() {
        currentServedPath = NOTIFICATIONS_TAG
    }

    private fun startGpsServing() {
        currentServedPath = GPS_TAG
    }

    private fun startBatteryServing() {
        currentServedPath = BATTERY_TAG
    }

    private fun getSidebarHtml(): String {
        val favoritesHtml = favoritePaths.joinToString("") { path ->
            val label = when (path) {
                SCREEN_MIRROR_TAG -> "Screen Mirror"
                NOTIFICATIONS_TAG -> "Notifications"
                GPS_TAG -> "GPS Tracker"
                BATTERY_TAG -> "Battery Status"
                else -> File(path).name.ifEmpty { "Root" }
            }
            val activeClass = if (path == currentServedPath) "active" else ""
            """<div class="sidebar-item $activeClass" onclick="changeMode('$path')">$label</div>"""
        }

        return """
            <div id="sidebar">
                <div class="sidebar-header">Android via Web</div>
                $favoritesHtml
            </div>
            <style>
                #sidebar { width: 220px; height: 100vh; background: #2c3e50; color: white; position: fixed; left: 0; top: 0; overflow-y: auto; z-index: 1000; border-right: 1px solid #34495e; transition: transform 0.3s; }
                .sidebar-header { padding: 20px; font-size: 1.2rem; font-weight: bold; background: #1a252f; text-align: center; }
                .sidebar-item { padding: 15px 20px; cursor: pointer; border-bottom: 1px solid #34495e; transition: background 0.2s; font-size: 0.95rem; }
                .sidebar-item:hover { background: #34495e; }
                .sidebar-item.active { background: #3498db; border-left: 5px solid #2980b9; }
                body { margin-left: 220px !important; transition: margin-left 0.3s; }
                @media (max-width: 768px) {
                    #sidebar { transform: translateX(-220px); }
                    body { margin-left: 0 !important; }
                    #sidebar.open { transform: translateX(0); }
                    .sidebar-toggle { display: block !important; }
                }
                .sidebar-toggle { display: none; position: fixed; top: 10px; right: 10px; z-index: 1100; background: #2c3e50; color: white; border: none; padding: 10px 15px; border-radius: 5px; cursor: pointer; font-size: 1.2rem; box-shadow: 0 2px 5px rgba(0,0,0,0.3); }
            </style>
            <button class="sidebar-toggle" onclick="document.getElementById('sidebar').classList.toggle('open')">☰ MENU</button>
            <script>
                async function changeMode(path) {
                    try {
                        const resp = await fetch('/api/change-mode?path=' + encodeURIComponent(path), { method: 'POST' });
                        if (resp.ok) window.location.href = '/';
                    } catch (e) { console.error(e); }
                }
            </script>
        """.trimIndent()
    }

    private fun getWatchdogScript(mode: String): String {
        return """
            <script>
                setInterval(async () => {
                    try {
                        const resp = await fetch('/api/current-mode');
                        if (resp.ok) {
                            const currentMode = await resp.text();
                            if (currentMode !== "$mode") {
                                window.location.href = '/';
                            }
                        }
                    } catch (e) { }
                }, 2000);
            </script>
        """.trimIndent()
    }

    private fun getBatteryHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Battery Monitor</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; padding: 20px; background-color: #f8f9fa; color: #212529; }
                    .card { background: white; padding: 25px; border-radius: 15px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); max-width: 400px; margin: 0 auto; text-align: center; }
                    h1 { color: #28a745; margin-bottom: 20px; }
                    .battery-level { font-size: 3rem; font-weight: bold; margin: 15px 0; color: #333; }
                    .battery-icon { font-size: 5rem; color: #28a745; }
                    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; margin-top: 25px; text-align: left; }
                    .info-item { background: #f1f3f5; padding: 10px; border-radius: 8px; }
                    .label { font-size: 0.8rem; color: #6c757d; display: block; }
                    .val { font-weight: bold; font-size: 1rem; }
                </style>
            </head>
            <body>
                ${getSidebarHtml()}
                <div class="card">
                    <h1>Battery Status</h1>
                    <div id="levelIcon" class="battery-icon">🔋</div>
                    <div id="pct" class="battery-level">--%</div>
                    <div id="status" style="font-size: 1.2rem; margin-bottom: 20px;">Loading...</div>
                    <div class="info-grid">
                        <div class="info-item"><span class="label">Health</span><span id="health" class="val">--</span></div>
                        <div class="info-item"><span class="label">Temperature</span><span id="temp" class="val">--</span></div>
                        <div class="info-item"><span class="label">Voltage</span><span id="volt" class="val">--</span></div>
                        <div class="info-item"><span class="label">Source</span><span id="plug" class="val">--</span></div>
                        <div class="info-item"><span class="label">Current</span><span id="curr" class="val">--</span></div>
                        <div class="info-item"><span class="label">Power</span><span id="pow" class="val">--</span></div>
                    </div>
                    <div id="protectionWarn" style="margin-top: 20px; color: #d63301; font-weight: bold; display: none;">
                        ⚠️ Charging Limit / Protection Active
                    </div>
                </div>
                <script>
                    function updateBattery() {
                        fetch('/api/battery')
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('pct').innerText = data.percentage + '%';
                                document.getElementById('status').innerText = data.status;
                                document.getElementById('health').innerText = data.health;
                                document.getElementById('temp').innerText = data.temperature + ' °C';
                                document.getElementById('volt').innerText = data.voltage + ' mV';
                                document.getElementById('plug').innerText = data.plugged;
                                document.getElementById('curr').innerText = data.current + ' mA';
                                document.getElementById('pow').innerText = data.power + ' W';
                                document.getElementById('protectionWarn').style.display = data.isProtected ? 'block' : 'none';
                                
                                const icon = document.getElementById('levelIcon');
                                if (data.status === 'Charging') icon.innerText = '⚡';
                                else if (data.percentage > 80) icon.innerText = '🔋';
                                else if (data.percentage > 20) icon.innerText = '🪫';
                                else icon.innerText = '⚠️';
                            })
                            .catch(err => console.error(err));
                    }
                    updateBattery();
                    setInterval(updateBattery, 5000);
                </script>
                ${getWatchdogScript(BATTERY_TAG)}
            </body>
            </html>
        """.trimIndent()
    }

    private fun getGpsHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>GPS Tracker</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                <style>
                    body { font-family: sans-serif; padding: 20px; background-color: #f0f2f5; color: #1c1e21; }
                    .card { background: white; padding: 20px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); max-width: 600px; margin: 0 auto; }
                    h1 { color: #1877f2; text-align: center; margin-bottom: 25px; }
                    .data-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 20px; }
                    .data-row { display: flex; flex-direction: column; padding: 10px; background: #f8f9fa; border-radius: 8px; }
                    .label { font-weight: bold; color: #65676b; font-size: 0.8rem; }
                    .value { font-family: monospace; color: #050505; font-size: 1rem; }
                    #map { height: 300px; width: 100%; border-radius: 12px; margin-top: 20px; border: 1px solid #ddd; }
                    .map-link { display: block; text-align: center; margin-top: 20px; padding: 12px; background: #1877f2; color: white; text-decoration: none; border-radius: 8px; font-weight: bold; }
                </style>
            </head>
            <body>
                ${getSidebarHtml()}
                <div class="card">
                    <h1>Live GPS Data</h1>
                    <div class="data-grid">
                        <div class="data-row"><span class="label">Latitude</span><span class="value" id="lat">--</span></div>
                        <div class="data-row"><span class="label">Longitude</span><span class="value" id="lng">--</span></div>
                        <div class="data-row"><span class="label">Altitude</span><span class="value" id="alt">--</span></div>
                        <div class="data-row"><span class="label">Speed</span><span class="value" id="spd">--</span></div>
                        <div class="data-row"><span class="label">Accuracy</span><span class="value" id="acc">--</span></div>
                        <div class="data-row"><span class="label">Last Update</span><span class="value" id="time">--</span></div>
                    </div>
                    <div id="map"></div>
                    <a id="mapLink" href="#" target="_blank" class="map-link">Open in Google Maps</a>
                </div>
                <script>
                    let map, marker;
                    function initMap() {
                        map = L.map('map').setView([0, 0], 2);
                        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                            attribution: '© OpenStreetMap contributors'
                        }).addTo(map);
                        marker = L.marker([0, 0]).addTo(map);
                    }
                    function updateGps() {
                        fetch('/api/gps')
                            .then(response => response.json())
                            .then(data => {
                                if (data.error) return;
                                const lat = data.latitude;
                                const lng = data.longitude;
                                document.getElementById('lat').innerText = lat.toFixed(6);
                                document.getElementById('lng').innerText = lng.toFixed(6);
                                document.getElementById('alt').innerText = data.altitude.toFixed(2) + ' m';
                                document.getElementById('spd').innerText = (data.speed * 3.6).toFixed(1) + ' km/h';
                                document.getElementById('acc').innerText = data.accuracy.toFixed(1) + ' m';
                                document.getElementById('time').innerText = new Date(data.time).toLocaleTimeString();
                                document.getElementById('mapLink').href = `https://www.google.com/maps?q=${"$"}{lat},${"$"}{lng}`;
                                
                                if (!map) initMap();
                                const pos = [lat, lng];
                                map.setView(pos, 15);
                                marker.setLatLng(pos);
                            })
                            .catch(err => console.error(err));
                    }
                    updateGps();
                    setInterval(updateGps, 3000);
                </script>
                ${getWatchdogScript(GPS_TAG)}
            </body>
            </html>
        """.trimIndent()
    }

    private fun getProjectionHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Phone Screen Mirror</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; margin: 0; background-color: #000; color: white; height: 100%; width: 100%; overflow: hidden; position: fixed; }
                    #stream { position: absolute; top: 0; left: 0; width: 100%; height: 100%; object-fit: contain; }
                    .overlay { position: absolute; bottom: 10px; right: 10px; background: rgba(0,0,0,0.5); padding: 8px 20px; border-radius: 20px; font-size: 0.9rem; z-index: 100; opacity: 0.7; pointer-events: none; }
                    .res-controls { position: absolute; top: 15px; left: 15px; display: flex; gap: 10px; z-index: 100; flex-wrap: wrap; max-width: 90vw; }
                    .res-btn { background: rgba(0,0,0,0.6); border: 2px solid white; color: white; padding: 12px 20px; border-radius: 10px; cursor: pointer; font-size: 1rem; font-weight: bold; transition: all 0.2s; white-space: nowrap; }
                    .res-btn:hover { background: rgba(255,255,255,0.2); transform: scale(1.05); }
                    .res-btn.active { background: #2196F3; border-color: #2196F3; box-shadow: 0 0 15px rgba(33, 150, 243, 0.5); }
                </style>
            </head>
            <body>
                ${getSidebarHtml()}
                <img id="stream" src="/api/projection" alt="Screen Mirror">
                <div class="res-controls">
                    <button class="res-btn" onclick="setRes(426, 240, this)">240p</button>
                    <button class="res-btn active" onclick="setRes(640, 360, this)">360p</button>
                    <button class="res-btn" onclick="setRes(854, 480, this)">480p</button>
                    <button class="res-btn" onclick="setRes(1280, 720, this)">720p</button>
                    <button class="res-btn" style="background: #f44336;" onclick="rotateScreen()">🔄 ROTATE</button>
                </div>
                <div class="overlay">Screen Mirror Mode</div>
                <script>
                    async function rotateScreen() {
                        try {
                            const response = await fetch('/api/rotate-screen', { method: 'POST' });
                            const data = await response.json();
                            if (data.error) alert(data.error);
                            else {
                                // Reloading stream might be needed to adapt to new aspect ratio
                                setTimeout(() => {
                                    const stream = document.getElementById('stream');
                                    stream.src = '/api/projection?t=' + new Date().getTime();
                                }, 1000);
                            }
                        } catch (e) { console.error(e); }
                    }
                    async function setRes(w, h, btn) {
                        try {
                            await fetch(`/api/projection-res?w=${"$"}{w}&h=${"$"}{h}`, { method: 'POST' });
                            document.querySelectorAll('.res-btn').forEach(b => b.classList.remove('active'));
                            btn.classList.add('active');
                        } catch (e) { console.error(e); }
                    }
                    document.getElementById('stream').onerror = function() {
                        setTimeout(() => { this.src = '/api/projection?t=' + new Date().getTime(); }, 1000);
                    };
                </script>
                ${getWatchdogScript(SCREEN_MIRROR_TAG)}
            </body>
            </html>
        """.trimIndent()
    }

    private fun getNotificationsHtml(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Smartphone Notifications</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: sans-serif; padding: 20px; background-color: #f4f4f9; color: #333; }
                    .notification { background: white; padding: 15px; margin-bottom: 10px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    .title { font-weight: bold; font-size: 1.1rem; color: #007bff; }
                    .text { margin-top: 5px; color: #555; }
                    .meta { font-size: 0.8rem; color: #888; margin-top: 10px; }
                    h1 { text-align: center; color: #333; }
                </style>
            </head>
            <body>
                ${getSidebarHtml()}
                <h1>Last 10 Notifications</h1>
                <div id="notifications">Loading...</div>
                <script>
                    function fetchNotifications() {
                        fetch('/api/notifications')
                            .then(response => response.json())
                            .then(data => {
                                const container = document.getElementById('notifications');
                                container.innerHTML = data.length === 0 ? '<p>No notifications received yet.</p>' :
                                    data.map(n => `
                                    <div class="notification">
                                        <div class="title">${"$"}{n.title || 'No Title'}</div>
                                        <div class="text">${"$"}{n.text || ''}</div>
                                        <div class="meta">${"$"}{n.packageName} • ${"$"}{new Date(n.timestamp).toLocaleString()}</div>
                                    </div>
                                `).join('');
                            })
                            .catch(err => {
                                document.getElementById('notifications').innerHTML = 'Error loading notifications: ' + err;
                            });
                    }
                    fetchNotifications();
                    setInterval(fetchNotifications, 5000);
                </script>
                ${getWatchdogScript(NOTIFICATIONS_TAG)}
            </body>
            </html>
        """.trimIndent()
    }

    private fun exitApp() {
        stopService(Intent(this, ProjectionService::class.java))
        server?.stop(500, 1000)
        captureSession?.close()
        cameraDevice?.close()
        cameraImageReader?.close()
        previewImageReader?.close()
        backgroundThread?.quitSafely()
        finishAffinity()
        exitProcess(0)
    }

    private fun startCamera() {
        if (cameraId == null) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        cameraImageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2)
        cameraImageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val deferred = photoDeferred
            if (deferred == null || deferred.isCompleted) { image.close(); return@setOnImageAvailableListener }
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val photoFile = File(currentServedPath, "capture.jpg")
                FileOutputStream(photoFile).use { it.write(bytes) }
                deferred.complete(photoFile)
                photoDeferred = null
            } catch (e: Exception) { deferred.completeExceptionally(e)
            } finally { image.close() }
        }, backgroundHandler)

        previewImageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        previewImageReader?.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.close()
        }, backgroundHandler)

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager?.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        val surfaces = listOf(cameraImageReader!!.surface, previewImageReader!!.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                updateCaptureRequest()
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        }, backgroundHandler)
                    }
                    override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                    override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
                }, backgroundHandler)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateCaptureRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(previewImageReader!!.surface)
            builder.set(CaptureRequest.FLASH_MODE, if (isFlashlightOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun setScreenTimeout(minutes: Int) {
        if (!Settings.System.canWrite(this)) return
        val timeoutMs = if (minutes <= 0) 24 * 60 * 60 * 1000 else minutes * 60 * 1000
        try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeoutMs)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private suspend fun takePhoto(): File {
        val session = captureSession ?: throw Exception("Camera not ready")
        val device = cameraDevice ?: throw Exception("Camera not ready")
        photoDeferred = CompletableDeferred()
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(cameraImageReader!!.surface)
        if (isFlashlightOn) builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        session.capture(builder.build(), null, backgroundHandler)
        return photoDeferred!!.await()
    }

    private fun createDefaultIndexHtml(dir: File) {
        val indexFile = File(dir, "index.html")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Android Remote Photo</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 0; background-color: #000; color: white; overflow: hidden; height: 100vh; display: flex; flex-direction: column; }
                    .controls { position: absolute; bottom: 30px; left: 0; right: 0; display: flex; justify-content: center; gap: 15px; z-index: 100; padding: 0 20px; flex-wrap: wrap; }
                    button, .btn-link { padding: 18px 25px; font-size: 0.9rem; cursor: pointer; border: none; border-radius: 50px; font-weight: bold; box-shadow: 0 4px 20px rgba(0,0,0,0.6); transition: background-color 0.2s, transform 0.1s; text-decoration: none; display: flex; align-items: center; justify-content: center; }
                    button:active { transform: scale(0.95); }
                    .btn-photo { background-color: #fff; color: #000; min-width: 140px; }
                    .btn-download { background-color: #2196F3; color: #fff; min-width: 140px; display: none; }
                    .btn-torch { background-color: #333; color: #fff; min-width: 110px; border: 1px solid #444; }
                    .btn-torch.active { background-color: #ffc107; color: #000; border-color: #ffc107; }
                    .btn-wallpaper { background-color: #4CAF50; color: white; display: none; }
                    .upload-section { position: absolute; top: 130px; left: 0; right: 0; display: flex; flex-direction: column; align-items: center; z-index: 100; gap: 10px; }
                    .btn-upload { background-color: #9C27B0; color: white; padding: 10px 20px; border-radius: 20px; font-size: 0.8rem; }
                    #fileInput { display: none; }
                    #fullScreenImage { width: 100vw; height: 100vh; object-fit: contain; display: none; background-color: #000; }
                    .welcome-msg { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); text-align: center; width: 100%; }
                    .loading { display: none; position: absolute; top: 30px; left: 50%; transform: translateX(-50%); background: rgba(255,255,255,0.9); color: #000; padding: 12px 25px; border-radius: 30px; font-weight: bold; z-index: 200; }
                    .timeout-container { position: absolute; top: 80px; left: 0; right: 0; display: flex; flex-direction: column; align-items: center; z-index: 100; }
                    input[type=range] { width: 80%; max-width: 300px; margin: 10px 0; }
                    .timeout-label { font-size: 0.8rem; background: rgba(0,0,0,0.5); padding: 5px 15px; border-radius: 15px; }
                </style>
            </head>
            <body>
                ${getSidebarHtml()}
                <div id="welcome" class="welcome-msg"><h1>REMOTE CAMERA</h1><p>Ready to capture</p></div>
                <div id="loading" class="loading">CAPTURING...</div>
                <div class="timeout-container">
                    <div class="timeout-label">Screen Timeout: <span id="timeoutVal">1</span> min</div>
                    <input type="range" min="0" max="30" value="1" id="timeoutSlider" oninput="updateTimeout(this.value)">
                </div>
                <div class="upload-section">
                    <input type="file" id="fileInput" accept="image/*" onchange="uploadWallpaper(this)">
                    <button class="btn-upload" onclick="document.getElementById('fileInput').click()">UPLOAD & SET WALLPAPER</button>
                </div>
                <img id="fullScreenImage">
                <div class="controls">
                    <button id="torchBtn" class="btn-torch" onclick="toggleFlashlight()">TORCH: OFF</button>
                    <button class="btn-photo" onclick="capturePhoto()">TAKE PHOTO</button>
                    <button id="setWallpaperBtn" class="btn-wallpaper btn-link" onclick="setWallpaperFromPhoto()">SET AS WALLPAPER</button>
                    <a id="downloadBtn" href="#" download="capture.jpg" class="btn-link btn-download">DOWNLOAD</a>
                </div>
                <script>
                    let currentImageUrl = '';
                    async function toggleFlashlight() {
                        const btn = document.getElementById('torchBtn');
                        try {
                            const response = await fetch('/api/flashlight', { method: 'POST' });
                            const data = await response.json();
                            if (data.status === 'on') { btn.classList.add('active'); btn.innerText = 'TORCH: ON'; }
                            else { btn.classList.remove('active'); btn.innerText = 'TORCH: OFF'; }
                        } catch (e) { console.error(e); }
                    }
                    async function updateTimeout(val) {
                        document.getElementById('timeoutVal').innerText = val == 0 ? 'ALWAYS ON' : val;
                        try { await fetch('/api/screen-timeout?minutes=' + val, { method: 'POST' }); } catch (e) { console.error(e); }
                    }
                    async function capturePhoto() {
                        const loader = document.getElementById('loading');
                        loader.style.display = 'block';
                        try {
                            const response = await fetch('/api/take-photo', { method: 'POST' });
                            const data = await response.json();
                            if (data.url) {
                                currentImageUrl = data.url + '?t=' + new Date().getTime();
                                document.getElementById('fullScreenImage').src = currentImageUrl;
                                document.getElementById('fullScreenImage').style.display = 'block';
                                document.getElementById('downloadBtn').href = currentImageUrl;
                                document.getElementById('downloadBtn').style.display = 'flex';
                                document.getElementById('setWallpaperBtn').style.display = 'flex';
                                document.getElementById('welcome').style.display = 'none';
                            }
                        } catch (e) { alert('Error taking photo'); } finally { loader.style.display = 'none'; }
                    }
                    async function setWallpaperFromPhoto() {
                        try {
                            const response = await fetch('/api/set-wallpaper-last', { method: 'POST' });
                            const data = await response.json();
                            if (data.status === 'success') alert('Wallpaper set!');
                            else alert('Error: ' + data.error);
                        } catch (e) { alert('Error setting wallpaper'); }
                    }
                    async function uploadWallpaper(input) {
                        if (!input.files || !input.files[0]) return;
                        const formData = new FormData();
                        formData.append('image', input.files[0]);
                        try {
                            const response = await fetch('/api/set-wallpaper-upload', {
                                method: 'POST',
                                body: formData
                            });
                            const data = await response.json();
                            if (data.status === 'success') alert('Wallpaper set from upload!');
                            else alert('Error: ' + data.error);
                        } catch (e) { alert('Error uploading wallpaper'); }
                        input.value = '';
                    }
                </script>
                ${getWatchdogScript(currentServedPath)}
            </body>
            </html>
        """.trimIndent()
        try { indexFile.writeText(html) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startServer(path: String) {
        val dir = File(path)
        server = embeddedServer(Netty, port = 8080) {
            routing {
                post("/api/flashlight") {
                    try {
                        isFlashlightOn = !isFlashlightOn
                        updateCaptureRequest()
                        call.respondText("{\"status\": \"${if (isFlashlightOn) "on" else "off"}\"}", ContentType.Application.Json)
                    } catch (e: Exception) { call.respond(HttpStatusCode.InternalServerError, "{\"error\": \"${e.message}\"}") }
                }

                post("/api/screen-timeout") {
                    val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: 1
                    setScreenTimeout(minutes)
                    call.respondText("{\"timeout\": $minutes}", ContentType.Application.Json)
                }

                post("/api/take-photo") {
                    try {
                        val photoFile = takePhoto()
                        lastPhotoFile = photoFile
                        call.respondText("{\"url\": \"/${photoFile.name}\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "{\"error\": \"${e.message}\"}")
                    }
                }

                post("/api/set-wallpaper-last") {
                    try {
                        val file = lastPhotoFile ?: throw Exception("No photo taken yet")
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        WallpaperManager.getInstance(this@MainActivity).setBitmap(bitmap)
                        call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "{\"error\": \"${e.message}\"}")
                    }
                }

                post("/api/set-wallpaper-upload") {
                    try {
                        val multipart = call.receiveMultipart()
                        var bitmap: android.graphics.Bitmap? = null
                        multipart.forEachPart { part ->
                            if (part is PartData.FileItem) {
                                val bytes = part.streamProvider().readBytes()
                                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            part.dispose()
                        }
                        if (bitmap != null) {
                            WallpaperManager.getInstance(this@MainActivity).setBitmap(bitmap)
                            call.respondText("{\"status\": \"success\"}", ContentType.Application.Json)
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "{\"error\": \"No image uploaded\"}")
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, "{\"error\": \"${e.message}\"}")
                    }
                }

                get("/api/projection") {
                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--frame")) {
                        try {
                            while (true) {
                                val frame = ProjectionService.lastFrame
                                if (frame != null) {
                                    writeStringUtf8("--frame\r\n")
                                    writeStringUtf8("Content-Type: image/jpeg\r\n")
                                    writeStringUtf8("Content-Length: ${frame.size}\r\n\r\n")
                                    writeFully(frame)
                                    writeStringUtf8("\r\n")
                                    flush()
                                }
                                delay(60) // High FPS
                            }
                        } catch (e: Exception) { }
                    }
                }

                post("/api/projection-res") {
                    val w = call.request.queryParameters["w"]?.toIntOrNull() ?: 640
                    val h = call.request.queryParameters["h"]?.toIntOrNull() ?: 360
                    val intent = Intent(this@MainActivity, ProjectionService::class.java).apply {
                        putExtra("width", w)
                        putExtra("height", h)
                    }
                    startService(intent)
                    call.respondText("{\"status\": \"updated\"}", ContentType.Application.Json)
                }

                post("/api/rotate-screen") {
                    if (Settings.System.canWrite(this@MainActivity)) {
                        try {
                            val currentRotation = Settings.System.getInt(contentResolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
                            val nextRotation = (currentRotation + 1) % 4
                            Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                            Settings.System.putInt(contentResolver, Settings.System.USER_ROTATION, nextRotation)
                            call.respondText("{\"status\": \"rotated\", \"value\": $nextRotation}", ContentType.Application.Json)
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, "{\"error\": \"${e.message}\"}")
                        }
                    } else {
                        call.respond(HttpStatusCode.Forbidden, "{\"error\": \"Write Settings permission not granted\"}")
                    }
                }

                get("/api/notifications") {
                    val list = NotificationReceiverService.notifications
                    val json = Gson().toJson(list)
                    call.respondText(json, ContentType.Application.Json)
                }

                get("/api/current-mode") {
                    call.respondText(currentServedPath)
                }

                post("/api/change-mode") {
                    val path = call.request.queryParameters["path"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                    runOnUiThread {
                        if (path != SCREEN_MIRROR_TAG && currentServedPath == SCREEN_MIRROR_TAG) {
                            stopService(Intent(this@MainActivity, ProjectionService::class.java))
                        }
                        when (path) {
                            SCREEN_MIRROR_TAG -> startScreenProjection()
                            NOTIFICATIONS_TAG -> startNotificationsServing()
                            GPS_TAG -> startGpsServing()
                            BATTERY_TAG -> startBatteryServing()
                            else -> {
                                currentServedPath = path
                                restartServer(path)
                            }
                        }
                    }
                    call.respondText("OK")
                }

                get("/api/gps") {
                    val loc = lastLocation
                    if (loc != null) {
                        val map = mapOf(
                            "latitude" to loc.latitude,
                            "longitude" to loc.longitude,
                            "altitude" to loc.altitude,
                            "speed" to loc.speed,
                            "accuracy" to loc.accuracy,
                            "time" to loc.time
                        )
                        call.respondText(Gson().toJson(map), ContentType.Application.Json)
                    } else {
                        call.respondText("{\"error\": \"Waiting for GPS signal...\"}", ContentType.Application.Json)
                    }
                }

                get("/api/battery") {
                    val info = getBatteryInfo()
                    call.respondText(Gson().toJson(info), ContentType.Application.Json)
                }

                get("/") {
                    when (currentServedPath) {
                        SCREEN_MIRROR_TAG -> call.respondText(getProjectionHtml(), ContentType.Text.Html)
                        NOTIFICATIONS_TAG -> call.respondText(getNotificationsHtml(), ContentType.Text.Html)
                        GPS_TAG -> call.respondText(getGpsHtml(), ContentType.Text.Html)
                        BATTERY_TAG -> call.respondText(getBatteryHtml(), ContentType.Text.Html)
                        else -> {
                            val indexHtml = File(dir, "index.html")
                            if (indexHtml.exists()) call.respondFile(indexHtml)
                            else {
                                val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                                call.respondText(generateDirectoryListing("", files), ContentType.Text.Html)
                            }
                        }
                    }
                }
                
                get("/{path...}") {
                    val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    if (relativePath == "index.html" || relativePath == "index.htm") {
                        when (currentServedPath) {
                            SCREEN_MIRROR_TAG -> { call.respondText(getProjectionHtml(), ContentType.Text.Html); return@get }
                            NOTIFICATIONS_TAG -> { call.respondText(getNotificationsHtml(), ContentType.Text.Html); return@get }
                            GPS_TAG -> { call.respondText(getGpsHtml(), ContentType.Text.Html); return@get }
                            BATTERY_TAG -> { call.respondText(getBatteryHtml(), ContentType.Text.Html); return@get }
                        }
                    }
                    val requestedFile = File(dir, relativePath)
                    if (!requestedFile.exists()) { call.respond(HttpStatusCode.NotFound, "File not found"); return@get }
                    if (requestedFile.isDirectory) {
                        val indexHtml = File(requestedFile, "index.html")
                        val indexHtm = File(requestedFile, "index.htm")
                        when {
                            indexHtml.exists() -> call.respondFile(indexHtml)
                            indexHtm.exists() -> call.respondFile(indexHtm)
                            else -> {
                                val files = requestedFile.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                                call.respondText(generateDirectoryListing(relativePath, files), ContentType.Text.Html)
                            }
                        }
                    } else { call.respondFile(requestedFile) }
                }
            }
        }.start(wait = false)
    }

    private fun generateDirectoryListing(relativePath: String, files: List<File>): String {
        return buildString {
            append("<html><head><title>Index of /$relativePath</title>")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            append("<style>body{font-family:sans-serif;padding:20px;} li{margin:10px 0;} a{text-decoration:none;color:#007bff;} a:hover{text-decoration:underline;}</style>")
            append("</head><body>")
            append(getSidebarHtml())
            append("<h1>Index of /$relativePath</h1><hr><ul>")
            if (relativePath.isNotEmpty()) append("<li><a href=\"..\">.. (Parent Directory)</a></li>")
            files.forEach { file ->
                val name = file.name + (if (file.isDirectory) "/" else "")
                append("<li><a href=\"$name\">$name</a></li>")
            }
            append("</ul><hr>")
            append(getWatchdogScript(currentServedPath))
            append("</body></html>")
        }
    }

    private fun restartServer(newPath: String) {
        server?.stop(1000, 2000)
        startServer(newPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, ProjectionService::class.java))
        server?.stop(1000, 5000)
        captureSession?.close()
        cameraDevice?.close()
        cameraImageReader?.close()
        previewImageReader?.close()
        backgroundThread?.quitSafely()
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && (address is InetAddress) && (address.address.size == 4)) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "127.0.0.1"
    }
}

@Composable
fun Android_viawebApp(
    ipAddress: String,
    currentServedPath: String,
    favoritePaths: List<String>,
    onPathChanged: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onExit: () -> Unit
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val serverUrl = "http://$ipAddress:8080"

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = { Icon(painterResource(it.icon), contentDescription = it.label) },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it },
                )
            }
            item(
                icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Exit") },
                label = { Text("Exit") },
                selected = false,
                onClick = onExit,
            )
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.HOME -> HomeScreen(serverUrl, currentServedPath, innerPadding)
                AppDestinations.FILES -> FileBrowserScreen(currentServedPath, onPathChanged, onToggleFavorite, favoritePaths, innerPadding)
                AppDestinations.FAVORITES -> FavoritesScreen(favoritePaths, currentServedPath, onPathChanged, innerPadding)
            }
        }
    }
}

@Composable
fun HomeScreen(serverUrl: String, currentServedPath: String, padding: androidx.compose.foundation.layout.PaddingValues) {
    val uriHandler = LocalUriHandler.current
    val qrCodeBitmap = remember(serverUrl) { generateQrCode(serverUrl) }
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Web Server Address:", style = MaterialTheme.typography.titleMedium)
        Text(
            text = serverUrl,
            style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable { uriHandler.openUri(serverUrl) }
        )
        Spacer(modifier = Modifier.height(16.dp))
        qrCodeBitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = "QR Code for $serverUrl", modifier = Modifier.size(250.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when(currentServedPath) {
                SCREEN_MIRROR_TAG -> "Screen Mirroring Active"
                NOTIFICATIONS_TAG -> "Notifications Display Active"
                GPS_TAG -> "GPS Tracking Active"
                BATTERY_TAG -> "Battery Monitor Active"
                else -> "Serving: $currentServedPath"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun FavoritesScreen(
    favoritePaths: List<String>,
    currentServedPath: String,
    onPathChanged: (String) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Text(text = "Favorite Folders", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(favoritePaths) { path ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPathChanged(path) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when(path) {
                            SCREEN_MIRROR_TAG -> Icons.Default.Favorite
                            NOTIFICATIONS_TAG -> Icons.Default.Notifications
                            GPS_TAG -> Icons.Default.LocationOn
                            BATTERY_TAG -> Icons.Default.BatteryFull
                            else -> Icons.Default.Folder
                        },
                        contentDescription = null,
                        tint = if (path == currentServedPath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = when(path) {
                                SCREEN_MIRROR_TAG -> "Screen Mirror"
                                NOTIFICATIONS_TAG -> "Notifications"
                                GPS_TAG -> "GPS Tracker"
                                BATTERY_TAG -> "Battery Status"
                                else -> File(path).name.ifEmpty { "Root" }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = when(path) {
                                SCREEN_MIRROR_TAG -> "Live Web Projection"
                                NOTIFICATIONS_TAG -> "Last 10 Notifications"
                                GPS_TAG -> "Live Position Data"
                                BATTERY_TAG -> "Level and Health Info"
                                else -> path
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (path == currentServedPath) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(text = "Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
fun FileBrowserScreen(
    currentServedPath: String,
    onPathChanged: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    favoritePaths: List<String>,
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    var currentDir by remember { mutableStateOf(File(if (currentServedPath.startsWith("INTERNAL")) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath else currentServedPath)) }
    val files = remember(currentDir) { currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList() }
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { currentDir.parentFile?.let { currentDir = it } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(text = currentDir.absolutePath, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPathChanged(currentDir.absolutePath) }, modifier = Modifier.weight(1f), enabled = currentDir.absolutePath != currentServedPath) {
                Text(if (currentDir.absolutePath == currentServedPath) "Currently Serving" else "Serve This Folder")
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { onToggleFavorite(currentDir.absolutePath) }) {
                Icon(
                    imageVector = if (favoritePaths.contains(currentDir.absolutePath)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Toggle Favorite",
                    tint = if (favoritePaths.contains(currentDir.absolutePath)) androidx.compose.ui.graphics.Color.Red else MaterialTheme.colorScheme.outline
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file -> FileItem(file = file, onClick = { if (file.isDirectory) currentDir = file }) }
        }
    }
}

@Composable
fun FileItem(file: File, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = file.name, style = MaterialTheme.typography.bodyLarge)
    }
}

fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) { for (y in 0 until height) { bitmap[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE } }
        bitmap
    } catch (e: Exception) { null }
}

enum class AppDestinations(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    FILES("Files", R.drawable.ic_account_box),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Android_viawebTheme { Greeting("Android") }
}