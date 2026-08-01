package com.translator.overlay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnToggleService: Button
    private lateinit var etApiKeys: EditText
    private lateinit var etCustomPrompt: EditText
    private lateinit var spModel: Spinner
    private lateinit var spThinking: Spinner
    private lateinit var spButtonSize: Spinner
    private lateinit var spButtonOpacity: Spinner
    private lateinit var swDoubleClick: Switch

    private val REQUEST_OVERLAY = 1001
    private val REQUEST_MEDIA_PROJECTION = 1002

    // true เมื่อ Activity นี้ถูกเปิดขึ้นมาจาก OverlayService เพื่อขอ Screen Capture ใหม่แบบอัตโนมัติ
    private var pendingProjectionRequest = false

    private val modelsList = arrayOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    )

    private val thinkingLevelsList = arrayOf("off", "minimal", "low", "medium", "high")
    private val buttonSizeList = arrayOf("เล็ก (Small)", "กลาง (Medium)", "ใหญ่ (Large)")
    private val buttonOpacityList = arrayOf("100% (ชัดเจน)", "80% (ปกติ)", "50% (จางปานกลาง)", "30% (จางมาก)")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Root ScrollView
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        // Header Title
        val tvHeader = TextView(this).apply {
            text = "OVERLAY TRANSLATOR"
            textSize = 22f
            setTextColor(0xFF00E676.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        mainLayout.addView(tvHeader)

        // --- Card 1: Master Switch ---
        val cardStatus = createCardLayout()
        tvStatus = TextView(this).apply {
            text = if (isServiceRunning()) "STATUS: 🟢 กำลังทำงาน" else "STATUS: 🔴 ปิดใช้งานอยู่"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        btnToggleService = Button(this).apply {
            text = if (isServiceRunning()) "🛑 ปิดการทำงานปุ่มลอย" else "🚀 เปิดการทำงานปุ่มลอย"
            textSize = 16f
            setTextColor(if (isServiceRunning()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            background = createButtonDrawable(if (isServiceRunning()) 0xFFD32F2F.toInt() else 0xFF00E676.toInt())
            setOnClickListener {
                saveSettings()
                if (isServiceRunning()) {
                    stopOverlayService()
                } else {
                    checkPermissionsAndStart()
                }
            }
        }
        cardStatus.addView(tvStatus)
        cardStatus.addView(btnToggleService)
        mainLayout.addView(cardStatus)

        // --- Card 2: Floating Button Settings ---
        val cardButtonSettings = createCardLayout()
        cardButtonSettings.addView(createSectionTitle("⚙️ ปรับแต่งปุ่มลอย (Floating Button)"))

        cardButtonSettings.addView(createLabel("ขนาดปุ่มลอย"))
        spButtonSize = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, buttonSizeList)
            setSelection(prefs.getInt("btn_size_index", 1))
        }
        cardButtonSettings.addView(spButtonSize)

        cardButtonSettings.addView(createLabel("ความโปร่งแสงปุ่มลอย"))
        spButtonOpacity = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, buttonOpacityList)
            setSelection(prefs.getInt("btn_opacity_index", 1))
        }
        cardButtonSettings.addView(spButtonOpacity)

        swDoubleClick = Switch(this).apply {
            text = "ดับเบิลคลิกปุ่มลอยเพื่อแคปแปลภาษา"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = prefs.getBoolean("enable_double_click", true)
            setPadding(0, 24, 0, 12)
        }
        cardButtonSettings.addView(swDoubleClick)
        mainLayout.addView(cardButtonSettings)

        // --- Card 3: Gemini AI Settings ---
        val cardAiSettings = createCardLayout()
        cardAiSettings.addView(createSectionTitle("🤖 ตั้งค่า Gemini AI & Keys"))

        cardAiSettings.addView(createLabel("Gemini API Keys (สลับคีย์อัตโนมัติ 1 บรรทัดต่อ 1 Key)"))
        etApiKeys = EditText(this).apply {
            hint = "วาง API Keys ที่นี่..."
            minLines = 3
            maxLines = 6
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            background = createEditTextDrawable()
            setPadding(24, 24, 24, 24)
            setText(prefs.getString("api_keys", ""))
        }
        cardAiSettings.addView(etApiKeys)

        cardAiSettings.addView(createLabel("โมเดล AI"))
        spModel = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modelsList)
            val savedModel = prefs.getString("selected_model", "gemini-3.1-flash-lite")
            setSelection(modelsList.indexOf(savedModel).coerceAtLeast(0))
        }
        cardAiSettings.addView(spModel)

        cardAiSettings.addView(createLabel("Thinking Level (ระดับการวิเคราะห์)"))
        spThinking = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, thinkingLevelsList)
            val savedThinking = prefs.getString("thinking_level", "off")
            setSelection(thinkingLevelsList.indexOf(savedThinking).coerceAtLeast(0))
        }
        cardAiSettings.addView(spThinking)
        mainLayout.addView(cardAiSettings)

        // --- Card 4: Custom Prompt & Save ---
        val cardPromptSettings = createCardLayout()
        cardPromptSettings.addView(createSectionTitle("📜 คำสั่งการแปล (Custom Prompt)"))

        val defaultPrompt = "อ่านข้อความในภาพนี้ แล้วแปลทั้งหมดเป็นภาษาไทย สละสลวย คำแทนตัวใช้ 'ข้า/เจ้า' ให้สอดคล้องบริบท สระและวรรณยุกต์ไทยต้องครบถ้วน ห้ามขาดหาย"
        etCustomPrompt = EditText(this).apply {
            minLines = 3
            maxLines = 5
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            background = createEditTextDrawable()
            setPadding(24, 24, 24, 24)
            setText(prefs.getString("custom_prompt", defaultPrompt))
        }
        cardPromptSettings.addView(etCustomPrompt)

        val btnSave = Button(this).apply {
            text = "💾 บันทึกการตั้งค่าทั้งหมด"
            textSize = 14f
            setTextColor(0xFF00E676.toInt())
            background = createButtonDrawable(0xFF2A2A2A.toInt())
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "บันทึกการตั้งค่าเรียบร้อย!", Toast.LENGTH_SHORT).show()
            }
        }
        val saveParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 24, 0, 0) }
        cardPromptSettings.addView(btnSave, saveParams)
        mainLayout.addView(cardPromptSettings)

        scrollView.addView(mainLayout)
        setContentView(scrollView)

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * เมื่อ MediaProjection หมดอายุ (เกิดขึ้นได้ทุกครั้งบน Android 14+ เพราะแต่ละ session
     * ใช้ createVirtualDisplay ได้ครั้งเดียว) OverlayService จะเปิดหน้านี้พร้อม extra
     * "request_media_projection" เพื่อขอสิทธิ์ Screen Capture ใหม่โดยอัตโนมัติ
     * โดยไม่ต้องให้ผู้ใช้กดปุ่ม "เปิดการทำงานปุ่มลอย" เอง
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("request_media_projection", false) == true) {
            pendingProjectionRequest = true
            requestMediaProjectionOnly()
        }
    }

    private fun requestMediaProjectionOnly() {
        if (!Settings.canDrawOverlays(this)) {
            // ไม่มีสิทธิ์ overlay แล้ว ให้กลับไปหน้าปกติให้ผู้ใช้กดเปิดเองใหม่
            pendingProjectionRequest = false
            return
        }
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
        if (isServiceRunning()) {
            tvStatus.text = "STATUS: 🟢 กำลังทำงาน"
            btnToggleService.text = "🛑 ปิดการทำงานปุ่มลอย"
            btnToggleService.background = createButtonDrawable(0xFFD32F2F.toInt())
            btnToggleService.setTextColor(0xFFFFFFFF.toInt())
        } else {
            tvStatus.text = "STATUS: 🔴 ปิดใช้งานอยู่"
            btnToggleService.text = "🚀 เปิดการทำงานปุ่มลอย"
            btnToggleService.background = createButtonDrawable(0xFF00E676.toInt())
            btnToggleService.setTextColor(0xFF000000.toInt())
        }
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = 24f
                setStroke(2, 0xFF2C2C2C.toInt())
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 32) }
            layoutParams = params
        }
    }

    private fun createSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF00E676.toInt())
            setPadding(0, 0, 0, 16)
        }
    }

    private fun createLabel(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 16, 0, 8)
        }
    }

    private fun createEditTextDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(0xFF121212.toInt())
            cornerRadius = 16f
            setStroke(2, 0xFF333333.toInt())
        }
    }

    private fun createButtonDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 16f
        }
    }

    private fun saveSettings() {
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().apply {
            putString("api_keys", etApiKeys.text.toString().trim())
            putString("selected_model", spModel.selectedItem.toString())
            putString("thinking_level", spThinking.selectedItem.toString())
            putString("custom_prompt", etCustomPrompt.text.toString().trim())
            putInt("btn_size_index", spButtonSize.selectedItemPosition)
            putInt("btn_opacity_index", spButtonOpacity.selectedItemPosition)
            putBoolean("enable_double_click", swDoubleClick.isChecked)
            apply()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        stopService(serviceIntent)
        updateUiState()
        Toast.makeText(this, "ปิดบริการปุ่มลอยเรียบร้อย", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (OverlayService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                val serviceIntent = Intent(this, OverlayService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    // สั่งให้ OverlayService แปลหน้าจอซ้ำทันทีที่ได้สิทธิ์ใหม่ กรณีนี้เป็นการขอสิทธิ์
                    // ใหม่แบบอัตโนมัติ (session เก่าหมดอายุระหว่างใช้งาน)
                    putExtra("auto_retry", pendingProjectionRequest)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateUiState()

                if (pendingProjectionRequest) {
                    // ขอสิทธิ์ใหม่ให้อัตโนมัติสำเร็จ พาผู้ใช้กลับไปแอปเดิมทันที ไม่ต้องค้างอยู่หน้านี้
                    pendingProjectionRequest = false
                    finish()
                } else {
                    Toast.makeText(this, "เปิดใช้งานปุ่มลอยเรียบร้อย!", Toast.LENGTH_SHORT).show()
                }
            } else {
                // ผู้ใช้กดปฏิเสธ/ปิด dialog ระหว่างขอสิทธิ์ใหม่อัตโนมัติ
                if (pendingProjectionRequest) {
                    pendingProjectionRequest = false
                    Toast.makeText(this, "ไม่ได้รับอนุญาต Screen Capture กรุณากดแปลใหม่อีกครั้ง", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }
}
