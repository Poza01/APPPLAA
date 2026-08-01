package com.translator.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: Button
    private var resultOverlay: View? = null
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var btnLayoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, null)
            setupVirtualDisplay()
        }

        setupOverlayViews()
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "overlay_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Overlay Translator Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)
    }

    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupOverlayViews() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        btnLayoutParams = params

        floatingButton = Button(this).apply {
            text = "🌐 แปลหน้าจอ"
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downTime = 0L

        floatingButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    initialX = btnLayoutParams?.x ?: 0
                    initialY = btnLayoutParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    btnLayoutParams?.x = initialX + dx
                    btnLayoutParams?.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(floatingButton, btnLayoutParams)
                    } catch (e: Exception) { }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val upTime = System.currentTimeMillis()
                    val timeDiff = upTime - downTime
                    if (timeDiff < 200 && Math.abs((btnLayoutParams?.x ?: 0) - initialX) < 10 && Math.abs((btnLayoutParams?.y ?: 0) - initialY) < 10) {
                        captureAndTranslate()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatingButton, params)
        } catch (e: Exception) { }
    }

    private fun captureAndTranslate() {
        val image: Image? = imageReader?.acquireLatestImage()
        if (image == null) {
            Toast.makeText(this, "แตะปุ่มอีกครั้ง...", Toast.LENGTH_SHORT).show()
            return
        }

        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        image.close()

        val byteArrayOutputStream = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteArrayOutputStream)
        val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)

        val apiKey = getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก API Key ในแอปก่อน", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "กำลังแปล...", Toast.LENGTH_SHORT).show()

        GeminiApi.translateImage(apiKey, base64Image) { translatedText ->
            floatingButton.post {
                showFullScreenResultOverlay(translatedText)
            }
        }
    }

    private fun showFullScreenResultOverlay(text: String) {
        removeResultOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val container = FrameLayout(this).apply {
            setBackgroundColor(0x55000000.toInt())
            setOnClickListener {
                removeResultOverlay()
            }
        }

        val tv = TextView(this).apply {
            this.text = text
            setPadding(50, 50, 50, 50)
            setBackgroundColor(0xEE111111.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }

        val tvParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(30, 30, 30, 100)
        }

        container.addView(tv, tvParams)
        resultOverlay = container
        try {
            windowManager.addView(resultOverlay, params)
        } catch (e: Exception) { }
    }

    private fun removeResultOverlay() {
        resultOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            resultOverlay = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeResultOverlay()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (e: Exception) {}
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}