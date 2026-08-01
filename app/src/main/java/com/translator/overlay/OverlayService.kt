package com.translator.overlay

import android.animation.ObjectAnimator
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var subMenuView: LinearLayout? = null
    private var resultOverlay: View? = null

    private var mediaProjection: MediaProjection? = null
    private var serviceResultCode: Int = -1
    private var serviceData: Intent? = null

    // Android 14+: createVirtualDisplay() ใช้ได้แค่ครั้งเดียวต่อ MediaProjection instance
    // ต้องสร้าง VirtualDisplay/ImageReader ครั้งเดียวแล้วใช้ซ้ำตลอดอายุของ session นี้
    private var persistentImageReader: ImageReader? = null
    private var persistentVirtualDisplay: VirtualDisplay? = null
    private var capturedWidth = 0
    private var capturedHeight = 0

    private var lastClickTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var isTranslating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "⚠️ ไม่ได้รับสิทธิ์การแสดงผลทับหน้าจอ (Overlay)", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        val newResultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val newData = intent?.getParcelableExtra<Intent>("data")
        val autoRetry = intent?.getBooleanExtra("auto_retry", false) ?: false

        if (newResultCode != -1 && newData != null) {
            serviceResultCode = newResultCode
            serviceData = newData
            setupMediaProjection(autoRetry)
        }

        // ป้องกันการเพิ่มปุ่มลอยซ้ำซ้อน กรณี service ทำงานอยู่แล้วแล้วมี intent
        // ใหม่เข้ามา (เช่น ขอสิทธิ์ Screen Capture ใหม่อัตโนมัติ)
        if (!::floatingButton.isInitialized) {
            setupFloatingButton()
        }
        return START_STICKY
    }

    private fun setupMediaProjection(autoRetry: Boolean) {
        // เคลียร์ของเก่าทิ้งก่อนเสมอ ทั้ง VirtualDisplay/ImageReader และ MediaProjection ตัวก่อนหน้า
        releasePersistentCapture()
        try { mediaProjection?.stop() } catch (ignored: Exception) {}
        mediaProjection = null

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(serviceResultCode, serviceData!!)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                releasePersistentCapture()
                mediaProjection = null
            }
        }, handler)

        setupPersistentVirtualDisplay()

        if (autoRetry) {
            // ผู้ใช้เพิ่งกดแปล แล้วต้องขอสิทธิ์ใหม่ระหว่างทาง พอได้สิทธิ์แล้วให้แปลต่อให้ทันที
            handler.postDelayed({
                if (::floatingButton.isInitialized) {
                    startLoadingAnimation()
                    captureAndTranslate()
                }
            }, 300)
        }
    }

    /**
     * สร้าง VirtualDisplay + ImageReader "ครั้งเดียว" ต่อ MediaProjection session ตามคำแนะนำของ Android
     * (ห้ามเรียก createVirtualDisplay ซ้ำกับ MediaProjection instance เดิม มิฉะนั้น session จะถูกเพิกถอนสิทธิ์)
     */
    private fun setupPersistentVirtualDisplay() {
        val dm = resources.displayMetrics
        capturedWidth = dm.widthPixels
        capturedHeight = dm.heightPixels
        val density = dm.densityDpi

        try {
            persistentImageReader = ImageReader.newInstance(capturedWidth, capturedHeight, PixelFormat.RGBA_8888, 2)
            persistentVirtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                capturedWidth, capturedHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                persistentImageReader?.surface, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating persistent VirtualDisplay: ${e.message}", e)
        }
    }

    private fun releasePersistentCapture() {
        try { persistentVirtualDisplay?.release() } catch (ignored: Exception) {}
        try { persistentImageReader?.close() } catch (ignored: Exception) {}
        persistentVirtualDisplay = null
        persistentImageReader = null
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
            .setContentText("ระบบแปลหน้าจอกำลังทำงาน...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
                if (isTranslating) return true

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
                        // save position
                        prefs.edit()
                            .putInt("btn_x", btnParams.x)
                            .putInt("btn_y", btnParams.y)
                            .apply()

                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            // Single click: open submenu with actions
                            toggleSubMenu(btnParams.x, btnParams.y)
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

    private fun toggleSubMenu(btnX: Int, btnY: Int) {
        if (subMenuView != null) {
            closeSubMenu()
            return
        }

        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = btnX + 180
            y = btnY
        }

        subMenuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(0xEE222222.toInt())
                cornerRadius = 16f
                setStroke(2, 0xFF333333.toInt())
            }

            val btnTranslate = Button(this@OverlayService).apply {
                text = "🔍 แปลหน้าจอ"
                setOnClickListener {
                    // start translating and close menu
                    startLoadingAnimation()
                    closeSubMenu()
                    captureAndTranslate()
                }
            }

            val btnRequestCapture = Button(this@OverlayService).apply {
                text = "🔁 รี-ขอ Screen Capture"
                setOnClickListener {
                    // open MainActivity to request screen capture intent
                    val intent = Intent(this@OverlayService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("request_media_projection", true)
                    }
                    startActivity(intent)
                    closeSubMenu()
                }
            }

            val btnBackToApp = Button(this@OverlayService).apply {
                text = "📱 กลับเข้าแอป"
                setOnClickListener {
                    val intent = Intent(this@OverlayService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    closeSubMenu()
                }
            }

            val btnClose = Button(this@OverlayService).apply {
                text = "✖ ปิดเมนู"
                setOnClickListener { closeSubMenu() }
            }

            addView(btnTranslate)
            addView(btnRequestCapture)
            addView(btnBackToApp)
            addView(btnClose)
        }

        try {
            windowManager.addView(subMenuView, menuParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding submenu: ${e.message}")
            subMenuView = null
        }
    }

    private fun closeSubMenu() {
        subMenuView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            subMenuView = null
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
        // startLoadingAnimation() is already called by caller (submenu) before invoking this

        if (mediaProjection == null || persistentImageReader == null || persistentVirtualDisplay == null) {
            // Session หมดอายุ (ปกติของ Android 14+ ที่ virtual display ใช้ได้ครั้งเดียว/หยุดไปเอง)
            // เปิด MainActivity เพื่อขอสิทธิ์ Screen Capture ใหม่แบบอัตโนมัติ แล้วแปลต่อให้ทันที
            resetLoadingAnimation()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("request_media_projection", true)
            }
            startActivity(intent)
            Toast.makeText(this, "🔒 สิทธิ์ Screen Capture หมดอายุ กำลังขอใหม่ให้อัตโนมัติ...", Toast.LENGTH_SHORT).show()
            return
        }

        ensureCaptureSizeMatchesScreen()

        val imageReader = persistentImageReader
        var image: android.media.Image? = null

        try {
            for (i in 1..3) {
                try {
                    image = imageReader?.acquireLatestImage() ?: imageReader?.acquireNextImage()
                    if (image != null) break
                } catch (e: Exception) {
                    Log.e(TAG, "Attempt $i failed: ${e.message}")
                }
                SystemClock.sleep(150)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}", e)
            handler.post {
                Toast.makeText(this, "❌ mediaProjection SecurityException: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image: ${e.message}", e)
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
                image.close()
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

    /**
     * ถ้าหน้าจอหมุน/เปลี่ยนขนาดระหว่างใช้งาน ให้ resize VirtualDisplay เดิม + สลับ Surface ใหม่
     * ตามที่ Android แนะนำ (ห้ามเรียก createVirtualDisplay ซ้ำกับ MediaProjection เดิม)
     */
    private fun ensureCaptureSizeMatchesScreen() {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val density = dm.densityDpi

        if (width == capturedWidth && height == capturedHeight) return

        try {
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            persistentVirtualDisplay?.resize(width, height, density)
            persistentVirtualDisplay?.surface = newReader.surface
            persistentImageReader?.close()
            persistentImageReader = newReader
            capturedWidth = width
            capturedHeight = height
        } catch (e: Exception) {
            Log.e(TAG, "Error resizing VirtualDisplay: ${e.message}", e)
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
        closeSubMenu()
        resetLoadingAnimation()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (e: Exception) {}
        }
        releasePersistentCapture()
        mediaProjection?.stop()
    }
}
