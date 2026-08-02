package com.translator.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ResultOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    companion object {
        private const val TAG = "ResultOverlayManager"
    }

    private var resultOverlay: View? = null

    fun showFullScreenResultOverlay(text: String) {
        removeResultOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val container = FrameLayout(context).apply {
            setBackgroundColor(0x77000000.toInt())
            setOnClickListener { removeResultOverlay() }
        }

        val tv = TextView(context).apply {
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

    fun removeResultOverlay() {
        resultOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            resultOverlay = null
        }
    }
}
