package com.translator.overlay

import android.animation.ObjectAnimator
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.*
import android.animation.TimeInterpolator
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class OverlayService : Service() {

    private companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: TextView
    private var resultOverlay: View? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var lastClickTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var isTranslating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // ตรวจสอบสิทธิ์ Overlay ก่อนเริ่ม
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "⚠️ ไม่ได้รับสิทธิ์การแสดงผลทับหน้าจอ (Overlay)", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay Translator Active")
            .setContentText("ระบบแปลภาษาบนหน้าจอกำลังทำงาน...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupVirtualDisplay() {
        // ใช้ resources.displayMetrics ปลอดภัยและไม่ Deprecated
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up VirtualDisplay: ${e.message}", e)
        }
    }

    private fun setupFloatingButton() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val btnSizeIndex = prefs.getInt("btn_size_index", 1)
        val btnPxSize = when (btnSizeIndex) {
            0 -> 120
            2 -> 180
            else -> 150
        }

        val btnOpacityIndex = prefs.getInt("btn_opacity_index", 1)
        val alphaValue = when (btnOpacityIndex) {
            0 -> 255
            2 -> 128
            3 -> 76
            else -> 204
        }

        // ดึงตำแหน่งที่เคยบันทึกไว้ล่าสุด
        val savedX = prefs.getInt("btn_x", 100)
        val savedY = prefs.getInt("btn_y", 300)

        val btnParams = WindowManager.LayoutParams(
            btnPxSize, btnPxSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
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
            contentDescription = "ปุ่มลอยสำหรับสแกนและแปลภาษาบนหน้าจอ"
        }

        floatingButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                if (isTranslating) return true // ล็อกปุ่มขณะกำลังแปล

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
                        try {
                            windowManager.updateViewLayout(floatingButton, btnParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "Update layout error: ${e.message}")
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // บันทึกตำแหน่งล่าสุดลง SharedPreferences
                        prefs.edit()
                            .putInt("btn_x", btnParams.x)
                            .putInt("btn_y", btnParams.y)
                            .apply()

                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            val isDoubleClickEnabled = prefs.getBoolean("enable_double_click", true)
                            val clickTime = System.currentTimeMillis()

                            if (isDoubleClickEnabled) {
                                if (clickTime - lastClickTime < 500) {
                                    startLoadingAnimation()
                                    captureAndTranslate()
                                }
                            } else {
                                startLoadingAnimation()
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

        try {
            windowManager.addView(floatingButton, btnParams)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while adding view: ${e.message}")
            Toast.makeText(this, "⚠️ ไม่สามารถแสดงปุ่มลอยได้ ตรวจสอบสิทธิ์ Overlay", Toast.LENGTH_LONG).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error adding view: ${e.message}")
        }
    }

    private fun startLoadingAnimation() {
        isTranslating = true
        floatingButton.text = "⏳"
        val rotateAnimator = ObjectAnimator.ofFloat(floatingButton, "rotation", 0f, 360f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        rotateAnimator.start()
        floatingButton.tag = rotateAnimator
    }

    private fun resetLoadingAnimation(defaultText: String = "🌐") {
        isTranslating = false
        val animator = floatingButton.tag as? ObjectAnimator
        animator?.cancel()
        floatingButton.rotation = 0f
        floatingButton.text = defaultText
    }

    private fun showSuccessAndReset() {
        isTranslating = false
        val animator = floatingButton.tag as? ObjectAnimator
        animator?.cancel()
        floatingButton.rotation = 0f
        floatingButton.text = "✅"
        
        handler.postDelayed({
            floatingButton.text = "🌐"
        }, 1500)
    }

    private fun captureAndTranslate() {
        if (mediaProjection == null || imageReader == null) {
            resetLoadingAnimation()
            Toast.makeText(this, "⚠️ ไม่พบสิทธิ์การแคปหน้าจอ กรุณาปิดและเปิดปุ่มลอยใหม่", Toast.LENGTH_SHORT).show()
            return
        }

        // ระบบ Retry 3 ครั้ง เพื่อป้องกัน Buffer ว่างเปล่าบน Android 14/15
        var image: android.media.Image? = null
        for (i in 1..3) {
            try {
                image = imageReader?.acquireLatestImage() ?: imageReader?.acquireNextImage()
                if (image != null) break
            } catch (e: Exception) {
                Log.e(TAG, "Attempt $i failed: ${e.message}")
            }
            SystemClock.sleep(150)
        }

        if (image == null) {
            resetLoadingAnimation()
            Toast.makeText(this, "⚠️ ไม่สามารถแคปภาพได้ กรุณาปิดแล้วเปิดปุ่มลอยใหม่", Toast.LENGTH_SHORT).show()
            return
        }

        var base64Image = ""
        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                resetLoadingAnimation()
                return
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val fullBitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)
            val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)

            val byteArrayOutputStream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteArrayOutputStream)
            base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
            resetLoadingAnimation()
            Toast.makeText(this, "❌ เกิดข้อผิดพลาดในการประมวลผลภาพ", Toast.LENGTH_SHORT).show()
            return
        } finally {
            try { image.close() } catch (ignored: Exception) {}
        }

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val rawKeys = prefs.getString("api_keys", "") ?: ""
        val keysList = rawKeys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        if (keysList.isEmpty()) {
            resetLoadingAnimation()
            Toast.makeText(this, "❌ ไม่พบ API Key กรุณากรอกคีย์ในแอป", Toast.LENGTH_LONG).show()
            return
        }

        val model = prefs.getString("selected_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        val thinking = prefs.getString("thinking_level", "off") ?: "off"
        val customPrompt = prefs.getString("custom_prompt", "อ่านและแปลภาพนี้เป็นภาษาไทย") ?: ""

        GeminiApi.translateWithMultiKeys(keysList, model, thinking, customPrompt, base64Image) { translatedText ->
            floatingButton.post {
                showSuccessAndReset()
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
        
        try {
            windowManager.addView(resultOverlay, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding result overlay: ${e.message}")
        }
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
        resetLoadingAnimation()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (e: Exception) {}
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
