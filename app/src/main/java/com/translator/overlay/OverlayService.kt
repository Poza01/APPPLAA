package com.translator.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var floatingButtonManager: FloatingButtonManager
    private lateinit var resultOverlayManager: ResultOverlayManager

    private var serviceResultCode: Int = -1
    private var serviceData: Intent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "⚠️ ไม่ได้รับสิทธิ์การแสดงผลทับหน้าจอ (Overlay)", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        if (!::screenCaptureManager.isInitialized) {
            screenCaptureManager = ScreenCaptureManager(this, handler)
        }
        if (!::floatingButtonManager.isInitialized) {
            floatingButtonManager = FloatingButtonManager(this, windowManager, handler)
        }
        if (!::resultOverlayManager.isInitialized) {
            resultOverlayManager = ResultOverlayManager(this, windowManager)
        }

        val newResultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val newData = intent?.getParcelableExtra<Intent>("data")
        val autoRetry = intent?.getBooleanExtra("auto_retry", false) ?: false

        if (newResultCode != -1 && newData != null) {
            serviceResultCode = newResultCode
            serviceData = newData
            screenCaptureManager.setupMediaProjection(serviceResultCode, serviceData!!)

            if (autoRetry) {
                handler.postDelayed({
                    if (::floatingButtonManager.isInitialized) {
                        floatingButtonManager.startLoadingAnimation()
                        captureAndTranslate()
                    }
                }, 300)
            }
        }

        if (!::floatingButtonManager.isInitialized || !floatingButtonManager.floatingButton.isAttachedToWindow) {
            floatingButtonManager.setupFloatingButton {
                captureAndTranslate()
            }
        }

        return START_STICKY
    }

    private fun captureAndTranslate() {
        val base64Image = screenCaptureManager.captureBase64Image()

        if (base64Image == null) {
            floatingButtonManager.resetLoadingAnimation()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("request_media_projection", true)
            }
            startActivity(intent)
            Toast.makeText(this, "🔒 สิทธิ์ Screen Capture หมดอายุ กำลังขอใหม่ให้อัตโนมัติ...", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val rawKeys = prefs.getString("api_keys", "") ?: ""
        val keysList = rawKeys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        if (keysList.isEmpty()) {
            floatingButtonManager.resetLoadingAnimation()
            Toast.makeText(this, "❌ ไม่พบ API Key กรุณากรอกคีย์ในแอป", Toast.LENGTH_LONG).show()
            return
        }

        val model = prefs.getString("selected_model", "gemini-3.1-flash-lite") ?: "gemini-3.1-flash-lite"
        val thinking = prefs.getString("thinking_level", "off") ?: "off"
        val customPrompt = prefs.getString("custom_prompt", "อ่านและแปลภาพนี้เป็นภาษาไทย") ?: ""

        GeminiApi.translateWithMultiKeys(keysList, model, thinking, customPrompt, base64Image) { translatedText ->
            handler.post {
                floatingButtonManager.showSuccessAndReset()
                resultOverlayManager.showFullScreenResultOverlay(translatedText)
            }
        }
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

    override fun onDestroy() {
        super.onDestroy()
        if (::resultOverlayManager.isInitialized) {
            resultOverlayManager.removeResultOverlay()
        }
        if (::floatingButtonManager.isInitialized) {
            floatingButtonManager.removeButton()
        }
        if (::screenCaptureManager.isInitialized) {
            screenCaptureManager.stop()
        }
    }
}
