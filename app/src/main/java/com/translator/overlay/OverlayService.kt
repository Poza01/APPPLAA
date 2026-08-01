package com.translator.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: TextView
    private var resultOverlay: View? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var lastClickTime = 0L

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

        setupFloatingButton()
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

    private fun setupFloatingButton() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // ขนาดปุ่ม
        val btnSizeIndex = prefs.getInt("btn_size_index", 1)
        val btnPxSize = when (btnSizeIndex) {
            0 -> 120 // Small
            2 -> 180 // Large
            else -> 150 // Medium
        }

        // ความโปร่งแสง
        val btnOpacityIndex = prefs.getInt("btn_opacity_index", 1)
        val alphaValue = when (btnOpacityIndex) {
            0 -> 255 // 100%
            2 -> 128 // 50%
            3 -> 76  // 30%
            else -> 204 // 80%
        }

        val btnParams = WindowManager.LayoutParams(
            btnPxSize, btnPxSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alphaValue, 0, 230, 118))
            setStroke(4, Color.argb(alphaValue, 255, 255, 255))
        }

        floatingButton = TextView(this).apply {
            text = "🌐"
            textSize = when (btnSizeIndex) {
                0 -> 18f
                2 -> 26f
                else -> 22f
            }
            gravity = Gravity.CENTER
            background = circleBg
        }

        floatingButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = btnParams.x
                        initialY = btnParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        btnParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        btnParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingButton, btnParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            val isDoubleClickEnabled = prefs.getBoolean("enable_double_click", true)
                            val clickTime = System.currentTimeMillis()

                            if (isDoubleClickEnabled) {
                                if (clickTime - lastClickTime < 300) {
                                    captureAndTranslate()
                                }
                            } else {
                                captureAndTranslate()
                            }
                            lastClickTime = clickTime
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingButton, btnParams)
    }

    private fun captureAndTranslate() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            Toast.makeText(this, "กดใหม่อีกครั้ง...", Toast.LENGTH_SHORT).show()
            return
        }

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val fullBitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        fullBitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
        image.close()

        val byteArrayOutputStream = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteArrayOutputStream)
        val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val rawKeys = prefs.getString("api_keys", "") ?: ""
        val keysList = rawKeys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        if (keysList.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก API Key ในแอปก่อน", Toast.LENGTH_SHORT).show()
            return
        }

        val model = prefs.getString("selected_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        val thinking = prefs.getString("thinking_level", "off") ?: "off"
        val customPrompt = prefs.getString("custom_prompt", "อ่านและแปลภาพนี้เป็นภาษาไทย") ?: ""

        Toast.makeText(this, "กำลังสแกนแปล...", Toast.LENGTH_SHORT).show()

        GeminiApi.translateWithMultiKeys(keysList, model, thinking, customPrompt, base64Image) { translatedText ->
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
            setBackgroundColor(0x77000000.toInt())
            setOnClickListener { removeResultOverlay() }
        }

        val tv = TextView(this).apply {
            this.text = text
            setPadding(48, 48, 48, 48)
            background = GradientDrawable().apply {
                setColor(0xEE1E1E1E.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFF00E676.toInt())
            }
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
        }

        val tvParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(32, 32, 32, 120)
        }

        container.addView(tv, tvParams)
        resultOverlay = container
        windowManager.addView(resultOverlay, params)
    }

    private fun removeResultOverlay() {
        resultOverlay?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
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
