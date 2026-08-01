package com.translator.overlay

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKeys: EditText
    private lateinit var etCustomPrompt: EditText
    private lateinit var spModel: Spinner
    private lateinit var spThinking: Spinner
    private lateinit var btnStart: Button
    private lateinit var btnSavePrompt: Button

    private val REQUEST_OVERLAY = 1001
    private val REQUEST_MEDIA_PROJECTION = 1002

    private val modelsList = arrayOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    )

    private val thinkingLevelsList = arrayOf("off", "minimal", "low", "medium", "high")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Main Layout (Scrollable Card View Style)
        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFF121212.toInt()) // Dark 2026 Theme
        }

        // --- Card 1: API Keys ---
        val tvKeysLabel = TextView(this).apply {
            text = "🔑 Gemini API Keys (แยกบรรทัดละ Key)"
            setTextColor(0xFF00E676.toInt())
            textSize = 14f
        }
        etApiKeys = EditText(this).apply {
            hint = "วาง API Key ที่นี่ (1 บรรทัดต่อ 1 Key)"
            minLines = 3
            maxLines = 6
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(20, 20, 20, 20)
            setText(prefs.getString("api_keys", ""))
        }

        // --- Card 2: Model & Thinking Select ---
        val tvModelLabel = TextView(this).apply {
            text = "🤖 เลือกโมเดล AI"
            setTextColor(0xFF00E676.toInt())
            setPadding(0, 30, 0, 10)
        }
        spModel = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modelsList)
            val savedModel = prefs.getString("selected_model", "gemini-3.1-flash-lite")
            setSelection(modelsList.indexOf(savedModel).coerceAtLeast(0))
        }

        val tvThinkingLabel = TextView(this).apply {
            text = "🧠 Thinking Level (ระดับความคิดวิเคราะห์)"
            setTextColor(0xFF00E676.toInt())
            setPadding(0, 20, 0, 10)
        }
        spThinking = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, thinkingLevelsList)
            val savedThinking = prefs.getString("thinking_level", "off")
            setSelection(thinkingLevelsList.indexOf(savedThinking).coerceAtLeast(0))
        }

        // --- Card 3: Custom Prompt ---
        val tvPromptLabel = TextView(this).apply {
            text = "📜 คำสั่งการแปล (Custom Prompt)"
            setTextColor(0xFF00E676.toInt())
            setPadding(0, 30, 0, 10)
        }
        val defaultPrompt = "อ่านข้อความภาษาจีน/อังกฤษในภาพนี้ แล้วแปลทั้งหมดเป็นภาษาไทย สละสลวย คำแทนตัวใช้ 'ข้า/เจ้า' ให้สอดคล้องบริบท สระและวรรณยุกต์ไทยต้องครบ ห้ามขาดหาย"
        etCustomPrompt = EditText(this).apply {
            minLines = 3
            maxLines = 5
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF777777.toInt())
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(20, 20, 20, 20)
            setText(prefs.getString("custom_prompt", defaultPrompt))
        }

        btnSavePrompt = Button(this).apply {
            text = "💾 บันทึกการตั้งค่า"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(0xFF00E676.toInt())
            setOnClickListener {
                saveSettings()
                Toast.makeText(this@MainActivity, "บันทึกข้อมูลเรียบร้อย!", Toast.LENGTH_SHORT).show()
            }
        }

        // --- Start Button ---
        btnStart = Button(this).apply {
            text = "🚀 เปิดใช้งานปุ่มลอย"
            textSize = 16f
            setBackgroundColor(0xFF00E676.toInt())
            setTextColor(0xFF000000.toInt())
            setOnClickListener {
                saveSettings()
                checkPermissionsAndStart()
            }
        }

        mainLayout.addView(tvKeysLabel)
        mainLayout.addView(etApiKeys)
        mainLayout.addView(tvModelLabel)
        mainLayout.addView(spModel)
        mainLayout.addView(tvThinkingLabel)
        mainLayout.addView(spThinking)
        mainLayout.addView(tvPromptLabel)
        mainLayout.addView(etCustomPrompt)
        mainLayout.addView(btnSavePrompt)
        mainLayout.addView(TextView(this).apply { height = 40 })
        mainLayout.addView(btnStart)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun saveSettings() {
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().apply {
            putString("api_keys", etApiKeys.text.toString().trim())
            putString("selected_model", spModel.selectedItem.toString())
            putString("thinking_level", spThinking.selectedItem.toString())
            putString("custom_prompt", etCustomPrompt.text.toString().trim())
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "เริ่มทำงานปุ่มลอยแล้ว!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
