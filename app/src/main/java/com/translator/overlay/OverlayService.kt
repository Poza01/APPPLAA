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

/**
 * ปุ่มลอย (floating button) เวอร์ชันย่อ ใช้งานง่าย:
 *  - แตะ 1 ที (ไม่ลาก) -> แคปหน้าจอ + แปล -> ขึ้นข้อความทับเต็มจอแบบโปร่งแสง
 *  - ลากปุ่ม -> ย้ายตำแหน่ง
 *  - ตอนขึ้นผลแปล: ลากนิ้วเพื่อเลื่อนอ่าน, แตะ 1 ที เพื่อปิด
 */
class OverlayService : Service() {

    private companion object {
        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
        private const val TAP_MOVE_THRESHOLD = 12f // px ที่ถือว่ายังเป็นการ "แตะ" ไม่ใช่ "ลาก"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: TextView
    private var resultOverlay: View? = null

    private var mediaProjection: MediaProjection? = null
    private var serviceResultCode: Int = -1
    private var serviceData: Intent? = null

    // Android 14+: createVirtualDisplay() ใช้ได้แค่ครั้งเดียวต่อ MediaProjection instance
    // สร้างครั้งเดียวแล้วใช้ซ้ำตลอดอายุ session ห้ามสร้างใหม่ทุกครั้งที่แปล
    private var persistentImageReader: ImageReader? = null
    private var persistentVirtualDisplay: VirtualDisplay? = null
    private var capturedWidth = 0
    private var capturedHeight = 0

    private val handler = Handler(Looper.getMainLooper())
    private var isTranslating = false
    private var lastPermissionRequestTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

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

        // กันปุ่มลอยซ้อนกัน กรณี service ทำงานอยู่แล้วแต่มี intent ใหม่เข้ามา (เช่น รีขอสิทธิ์อัตโนมัติ)
        if (!::floatingButton.isInitialized) {
            setupFloatingButton()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeResultOverlay()
        resetLoadingAnimation()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (e: Exception) {}
        }
        releasePersistentCapture()
        try { mediaProjection?.stop() } catch (ignored: Exception) {}
    }

    // ---------------------------------------------------------------------
    // MediaProjection / capture setup
    // ---------------------------------------------------------------------

    private fun setupMediaProjection(autoRetry: Boolean) {
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
            // เพิ่งขอสิทธิ์ใหม่เพราะ session เดิมหมดอายุระหว่างกดแปล -> แปลต่อให้ทันที
            handler.postDelayed({
                if (::floatingButton.isInitialized) {
                    startLoadingAnimation()
                    captureAndTranslate()
                }
            }, 300)
        }
    }

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
            if (persistentVirtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay returned null")
                handler.post {
                    Toast.makeText(this, "❌ สร้างหน้าจอแคปไม่สำเร็จ (VirtualDisplay = null)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating persistent VirtualDisplay: ${e.message}", e)
            handler.post {
                Toast.makeText(this, "❌ ตั้งค่าแคปหน้าจอไม่สำเร็จ: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** รองรับกรณีหน้าจอหมุน/เปลี่ยนขนาดโดยไม่ต้องขอสิทธิ์ใหม่ (resize แทน create ใหม่) */
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

    private fun releasePersistentCapture() {
        try { persistentVirtualDisplay?.release() } catch (ignored: Exception) {}
        try { persistentImageReader?.close() } catch (ignored: Exception) {}
        persistentVirtualDisplay = null
        persistentImageReader = null
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Overlay Service", NotificationManager.IMPORTANCE_LOW
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // Floating button: tap = translate, drag = move
    // ---------------------------------------------------------------------

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
                        prefs.edit()
                            .putInt("btn_x", btnParams.x)
                            .putInt("btn_y", btnParams.y)
                            .apply()

                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < TAP_MOVE_THRESHOLD && diffY < TAP_MOVE_THRESHOLD) {
                            // แตะ (ไม่ลาก) -> แปลทันที
                            startLoadingAnimation()
                            captureAndTranslate()
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
        if (!::floatingButton.isInitialized) return
        val animator = floatingButton.tag as? ObjectAnimator
        animator?.cancel()
        floatingButton.rotation = 0f
        floatingButton.text = defaultText
    }

    // ---------------------------------------------------------------------
    // Capture + translate
    // ---------------------------------------------------------------------

    private fun captureAndTranslate() {
        if (mediaProjection == null || persistentImageReader == null || persistentVirtualDisplay == null) {
            resetLoadingAnimation()

            val now = SystemClock.elapsedRealtime()
            if (now - lastPermissionRequestTime < 3000) {
                // เพิ่งขอสิทธิ์ไปเมื่อกี้ อย่าเพิ่งเด้งซ้ำ กันวนลูป
                Toast.makeText(this, "⏳ กำลังรอสิทธิ์ Screen Capture จากรอบก่อนอยู่ ลองรออีกสักครู่", Toast.LENGTH_SHORT).show()
                return
            }
            lastPermissionRequestTime = now

            // session หมดอายุ หรือสร้างจอแคปไม่สำเร็จ -> เปิด MainActivity ขอสิทธิ์ใหม่อัตโนมัติ
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("request_media_projection", true)
            }
            startActivity(intent)
            Toast.makeText(this, "🔒 ต้องขอสิทธิ์ Screen Capture ใหม่ กำลังขอให้อัตโนมัติ...", Toast.LENGTH_SHORT).show()
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
                resetLoadingAnimation()
                showFullScreenResultOverlay(translatedText)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Full-screen translucent result overlay: drag to scroll, tap to dismiss
    // ---------------------------------------------------------------------

    private fun showFullScreenResultOverlay(text: String) {
        removeResultOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val tv = TextView(this).apply {
            this.text = text
            setPadding(48, 96, 48, 96)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }

        // ScrollView ทำให้ลากนิ้วเลื่อนอ่านข้อความยาวๆ ได้
        val scrollView = ScrollView(this).apply {
            addView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                removeResultOverlay()
                return true
            }
        })

        // ใส่ gesture detector บน scrollView เอง: คืนค่า false เสมอเพื่อให้ ScrollView
        // ยังคง scroll ตามปกติได้ พร้อมกับตรวจจับ "แตะ 1 ที" เพื่อปิดจอไปด้วย
        scrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(0xAA000000.toInt()) // จอโปร่งแสงคลุมเต็มหน้าจอ
            addView(scrollView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

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
}
