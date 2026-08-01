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
    private var subMenuView: LinearLayout? = null
    private var resultOverlay: View? = null

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var isDoubleClickEnabled = true
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
        val btnParams = WindowManager.LayoutParams(
            150, 150, // วงกลมขนาด 150x150
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // ทำดีไซน์ปุ่มลอยวงกลมทรงสวยงาม
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xDD00E676.toInt()) // สีเขียวนีออนโปร่งแสงนิดๆ
            setStroke(4, 0xFFFFFFFF.toInt())
        }

        floatingButton = TextView(this).apply {
            text = "🌐"
            textSize = 22f
            gravity = Gravity.CENTER
            background = circleBg
        }

        // ระบบจับ Touch event (ลากได้ + Single/Double Click)
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
                            val clickTime = System.currentTimeMillis()
                            if (clickTime - lastClickTime < 300) {
                                // Double Click
                                if (isDoubleClickEnabled) {
                                    closeSubMenu()
                                    captureAndTranslate()
                                }
                            } else {
                                // Single Click
                                toggleSubMenu(btnParams.x, btnParams.y)
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
            x = btnX + 160
            y = btnY
        }

        subMenuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xEE222222.toInt())

            val btnToggleDbClick = Button(this@OverlayService).apply {
                text = if (isDoubleClickEnabled) "🟢 ปิดดับเบิลคลิกแปล" else "🔴 เปิดดับเบิลคลิกแปล"
                textSize = 12f
                setOnClickListener {
                    isDoubleClickEnabled = !isDoubleClickEnabled
                    text = if (isDoubleClickEnabled) "🟢 ปิดดับเบิลคลิกแปล" else "🔴 เปิดดับเบิลคลิกแปล"
                }
            }

            val btnBackToApp = Button(this@OverlayService).apply {
                text = "📱 กลับเข้าแอปหลัก"
                textSize = 12f
                setOnClickListener {
                    val intent = Intent(this@OverlayService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    closeSubMenu()
                }
            }

            addView(btnToggleDbClick)
            addView(btnBackToApp)
        }

        windowManager.addView(subMenuView, menuParams)
    }

    private fun closeSubMenu() {
        subMenuView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            subMenuView = null
        }
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
            setBackgroundColor(0x55000000.toInt())
            setOnClickListener { removeResultOverlay() }
        }

        val tv = TextView(this).apply {
            this.text = text
            setPadding(50, 50, 50, 50)
            setBackgroundColor(0xEE111111.toInt())
            setTextColor(0xFF00E676.toInt())
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
        closeSubMenu()
        removeResultOverlay()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (e: Exception) {}
        }
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}
