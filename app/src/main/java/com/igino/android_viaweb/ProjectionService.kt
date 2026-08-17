package com.igino.android_viaweb

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ProjectionService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    companion object {
        const val CHANNEL_ID = "ProjectionServiceChannel"
        const val NOTIFICATION_ID = 101
        
        @Volatile
        var lastFrame: ByteArray? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring Active")
            .setContentText("Streaming phone screen to web...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }

        if (resultData != null) {
            startProjection(resultCode, resultData)
        } else {
            val newWidth = intent?.getIntExtra("width", 640) ?: 640
            val newHeight = intent?.getIntExtra("height", 360) ?: 360
            updateResolution(newWidth, newHeight)
        }

        return START_NOT_STICKY
    }

    private var currentResultCode: Int = -1
    private var currentResultData: Intent? = null

    private fun startProjection(resultCode: Int, resultData: Intent) {
        currentResultCode = resultCode
        currentResultData = resultData
        
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        backgroundThread = HandlerThread("ProjectionThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        setupVirtualDisplay(640, 360)
    }

    private fun updateResolution(width: Int, height: Int) {
        if (mediaProjection == null) return
        
        virtualDisplay?.release()
        imageReader?.close()
        
        setupVirtualDisplay(width, height)
    }

    private fun setupVirtualDisplay(width: Int, height: Int) {
        val metrics = resources.displayMetrics
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MirrorDisplay",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out)
                lastFrame = out.toByteArray()
                bitmap.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Screen Mirror Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        backgroundThread?.quitSafely()
        lastFrame = null
        super.onDestroy()
    }
}