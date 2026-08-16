package com.igino.android_viaweb

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.set
import com.igino.android_viaweb.ui.theme.Android_viawebTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.ui.tooling.preview.Preview
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var server: EmbeddedServer<*, *>? = null
    private var currentServedPath by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!Environment.isExternalStorageManager()) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(intent)
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val defaultDir = File(downloadDir, "serverweb")
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        currentServedPath = defaultDir.absolutePath
        startServer(currentServedPath)

        setContent {
            Android_viawebTheme {
                Android_viawebApp(
                    ipAddress = getIpAddress(),
                    currentServedPath = currentServedPath,
                    onPathChanged = { newPath ->
                        currentServedPath = newPath
                        restartServer(newPath)
                    }
                )
            }
        }
    }

    private fun startServer(path: String) {
        val dir = File(path)
        Log.d("WebServer", "Starting server for path: ${dir.absolutePath}")

        server = embeddedServer(Netty, port = 8080) {
            routing {
                get("/{path...}") {
                    val relativePath = call.parameters.getAll("path")?.joinToString("/") ?: ""
                    val requestedFile = File(dir, relativePath)

                    if (!requestedFile.exists()) {
                        call.respond(io.ktor.http.HttpStatusCode.NotFound, "File not found")
                        return@get
                    }

                    if (requestedFile.isDirectory) {
                        val indexHtml = File(requestedFile, "index.html")
                        val indexHtm = File(requestedFile, "index.htm")

                        when {
                            indexHtml.exists() -> call.respondFile(indexHtml)
                            indexHtm.exists() -> call.respondFile(indexHtm)
                            else -> {
                                val files = requestedFile.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                                val html = buildString {
                                    append("<html><head><title>Index of /$relativePath</title>")
                                    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                                    append("<style>body{font-family:sans-serif;padding:20px;} li{margin:10px 0;} a{text-decoration:none;color:#007bff;} a:hover{text-decoration:underline;}</style>")
                                    append("</head><body>")
                                    append("<h1>Index of /$relativePath</h1><hr><ul>")
                                    if (relativePath.isNotEmpty()) {
                                        append("<li><a href=\"..\">.. (Parent Directory)</a></li>")
                                    }
                                    files.forEach { file ->
                                        val name = file.name + (if (file.isDirectory) "/" else "")
                                        append("<li><a href=\"$name\">$name</a></li>")
                                    }
                                    append("</ul><hr></body></html>")
                                }
                                call.respondText(html, io.ktor.http.ContentType.Text.Html)
                            }
                        }
                    } else {
                        call.respondFile(requestedFile)
                    }
                }
            }
        }.start(wait = false)
        Log.d("WebServer", "Server started on port 8080")
    }

    private fun restartServer(newPath: String) {
        server?.stop(1000, 2000)
        startServer(newPath)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop(1000, 5000)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}

@Composable
fun Android_viawebApp(
    ipAddress: String,
    currentServedPath: String,
    onPathChanged: (String) -> Unit
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val serverUrl = "http://$ipAddress:8080"

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            painterResource(it.icon),
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it },
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.HOME -> HomeScreen(serverUrl, currentServedPath, innerPadding)
                AppDestinations.FILES -> FileBrowserScreen(currentServedPath, onPathChanged, innerPadding)
                AppDestinations.FAVORITES -> {
                    Column(Modifier.padding(innerPadding)) { Text("Favorites placeholder") }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(serverUrl: String, currentServedPath: String, padding: androidx.compose.foundation.layout.PaddingValues) {
    val uriHandler = LocalUriHandler.current
    val qrCodeBitmap = remember(serverUrl) {
        generateQrCode(serverUrl)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Greeting(name = "Web Server")
        Spacer(modifier = Modifier.height(16.dp))

        qrCodeBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "QR Code for $serverUrl",
                modifier = Modifier.size(250.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = serverUrl,
            style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
            modifier = Modifier.clickable { uriHandler.openUri(serverUrl) }
        )
        Text(
            text = "Serving: $currentServedPath",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun FileBrowserScreen(
    currentServedPath: String,
    onPathChanged: (String) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    var currentDir by remember { mutableStateOf(File(currentServedPath)) }
    val files = remember(currentDir) {
        currentDir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                currentDir.parentFile?.let { currentDir = it }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = currentDir.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = { onPathChanged(currentDir.absolutePath) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            enabled = currentDir.absolutePath != currentServedPath
        ) {
            Text(if (currentDir.absolutePath == currentServedPath) "Currently Serving This Folder" else "Serve This Folder")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                FileItem(file = file, onClick = {
                    if (file.isDirectory) {
                        currentDir = file
                    }
                })
            }
        }
    }
}

@Composable
fun FileItem(file: File, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

enum class AppDestinations(
    val label: String,
    val icon: Int,
) {
    HOME("Home", R.drawable.ic_home),
    FAVORITES("Favorites", R.drawable.ic_favorite),
    FILES("Files", R.drawable.ic_account_box),
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Android_viawebTheme {
        Greeting("Android")
    }
}