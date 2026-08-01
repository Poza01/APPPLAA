package com.translator.overlay

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnStart: Button
    private val REQUEST_OVERLAY = 1001
    private val REQUEST_MEDIA_PROJECTION = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        etApiKey = EditText(this).apply {
            hint = "วาง Gemini API Key ที่นี่"
            setText(getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("api_key", ""))
        }
        
        btnStart = Button(this).apply {
            text = "เปิดใช้งานปุ่มลอยแปลภาษา"
            setOnClickListener {
                saveApiKey()
                checkPermissionsAndStart()
            }
        }

        layout.addView(etApiKey)
        layout.addView(btnStart)
        setContentView(layout)
    }

    private fun saveApiKey() {
        val key = etApiKey.text.toString().trim()
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("api_key", key).apply()
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