package com.translator.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

class ScreenCaptureManager(
    private val context: Context,
    private val handler: Handler
) {
    companion object {
        private const val TAG = "ScreenCaptureManager"
    }

    var mediaProjection: MediaProjection? = private set
    private var persistentImageReader: ImageReader? = null
    private var persistentVirtualDisplay: VirtualDisplay? = null
    private var capturedWidth = 0
    private var capturedHeight = 0

    fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        releasePersistentCapture()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                releasePersistentCapture()
                mediaProjection = null
            }
        }, handler)

        setupPersistentVirtualDisplay()
    }

    private fun setupPersistentVirtualDisplay() {
        val dm = context.resources.displayMetrics
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

    fun captureBase64Image(): String? {
        if (mediaProjection == null || persistentImageReader == null || persistentVirtualDisplay == null) {
            return null
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
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring image: ${e.message}", e)
        }

        if (image == null) return null

        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                image.close()
                return null
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
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}", e)
            return null
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    private fun ensureCaptureSizeMatchesScreen() {
        val dm = context.resources.displayMetrics
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

    fun releasePersistentCapture() {
        try { persistentVirtualDisplay?.release() } catch (_: Exception) {}
        try { persistentImageReader?.close() } catch (_: Exception) {}
        persistentVirtualDisplay = null
        persistentImageReader = null
    }

    fun stop() {
        releasePersistentCapture()
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }
}
