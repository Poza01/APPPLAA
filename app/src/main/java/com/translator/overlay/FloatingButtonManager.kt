package com.translator.overlay

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatingButtonManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val handler: Handler
) {
    companion object {
        private const val TAG = "FloatingButtonManager"
    }

    lateinit var floatingButton: TextView
        private set
    private var subMenuView: LinearLayout? = null
    var isTranslating = false
        private set

    fun setupFloatingButton(onTranslateClick: () -> Unit) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

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

        floatingButton = TextView(context).apply {
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
                        if (diffX < 10 && diffY < 10) {
                            toggleSubMenu(btnParams.x, btnParams.y, onTranslateClick)
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(floatingButton, btnParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding view: ${e.message}")
        }
    }

    private fun toggleSubMenu(btnX: Int, btnY: Int, onTranslateClick: () -> Unit) {
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

        subMenuView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(0xEE222222.toInt())
                cornerRadius = 16f
                setStroke(2, 0xFF333333.toInt())
            }

            val btnTranslate = Button(context).apply {
                text = "🔍 แปลหน้าจอ"
                setOnClickListener {
                    startLoadingAnimation()
                    closeSubMenu()
                    onTranslateClick()
                }
            }

            val btnRequestCapture = Button(context).apply {
                text = "🔁 รี-ขอ Screen Capture"
                setOnClickListener {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("request_media_projection", true)
                    }
                    context.startActivity(intent)
                    closeSubMenu()
                }
            }

            val btnBackToApp = Button(context).apply {
                text = "📱 กลับเข้าแอป"
                setOnClickListener {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    closeSubMenu()
                }
            }

            val btnClose = Button(context).apply {
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

    fun closeSubMenu() {
        subMenuView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            subMenuView = null
        }
    }

    fun startLoadingAnimation() {
        if (!::floatingButton.isInitialized) return
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

    fun resetLoadingAnimation(defaultText: String = "🌐") {
        if (!::floatingButton.isInitialized) return
        isTranslating = false
        val animator = floatingButton.tag as? ObjectAnimator
        animator?.cancel()
        floatingButton.rotation = 0f
        floatingButton.text = defaultText
    }

    fun showSuccessAndReset() {
        if (!::floatingButton.isInitialized) return
        isTranslating = false
        val animator = floatingButton.tag as? ObjectAnimator
        animator?.cancel()
        floatingButton.rotation = 0f
        floatingButton.text = "✅"

        handler.postDelayed({
            floatingButton.text = "🌐"
        }, 1500)
    }

    fun removeButton() {
        closeSubMenu()
        resetLoadingAnimation()
        if (::floatingButton.isInitialized) {
            try { windowManager.removeView(floatingButton) } catch (_: Exception) {}
        }
    }
}
