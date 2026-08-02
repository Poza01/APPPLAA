// ---------------------------------------------------------------------
// MediaProjection / capture setup (ปรับปรุง)
// ---------------------------------------------------------------------

private fun setupPersistentVirtualDisplay() {
    val dm = resources.displayMetrics
    capturedWidth = dm.widthPixels
    capturedHeight = dm.heightPixels
    val density = dm.densityDpi

    releasePersistentCapture()

    try {
        persistentImageReader = ImageReader.newInstance(
            capturedWidth, capturedHeight,
            PixelFormat.RGBA_8888, 3   // เพิ่ม maxImages เป็น 3
        )

        // ตั้ง listener ไว้เผื่อเฟรมมาช้า
        persistentImageReader?.setOnImageAvailableListener({ reader ->
            // ไม่ต้องทำอะไร แค่มี listener จะช่วยให้ระบบส่งเฟรมมาได้ดีขึ้น
        }, handler)

        persistentVirtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            capturedWidth, capturedHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            persistentImageReader?.surface,
            null, handler
        )

        if (persistentVirtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null")
            handler.post {
                Toast.makeText(this, "❌ สร้าง VirtualDisplay ไม่สำเร็จ", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.d(TAG, "VirtualDisplay created successfully \( {capturedWidth}x \){capturedHeight}")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error creating persistent VirtualDisplay: ${e.message}", e)
        handler.post {
            Toast.makeText(this, "❌ ตั้งค่าแคปไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

private fun ensureCaptureSizeMatchesScreen() {
    val dm = resources.displayMetrics
    val width = dm.widthPixels
    val height = dm.heightPixels
    val density = dm.densityDpi

    if (width == capturedWidth && height == capturedHeight) return

    Log.d(TAG, "Screen size changed → resize VirtualDisplay")

    try {
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        newReader.setOnImageAvailableListener({ }, handler)

        persistentVirtualDisplay?.resize(width, height, density)
        persistentVirtualDisplay?.surface = newReader.surface

        persistentImageReader?.close()
        persistentImageReader = newReader
        capturedWidth = width
        capturedHeight = height
    } catch (e: Exception) {
        Log.e(TAG, "Error resizing VirtualDisplay: ${e.message}", e)
        // ถ้า resize ไม่ได้ → สร้างใหม่ทั้งก้อน (บางเครื่องจำเป็น)
        setupPersistentVirtualDisplay()
    }
}

// ---------------------------------------------------------------------
// Capture + translate (เวอร์ชันเสถียรกว่า)
// ---------------------------------------------------------------------

private fun captureAndTranslate() {
    if (mediaProjection == null || persistentImageReader == null || persistentVirtualDisplay == null) {
        resetLoadingAnimation()
        requestNewPermission()
        return
    }

    ensureCaptureSizeMatchesScreen()

    // รอเฟรมใหม่สักครู่ (สำคัญมาก)
    handler.postDelayed({
        doCaptureAndProcess()
    }, 180)
}

private fun doCaptureAndProcess() {
    val imageReader = persistentImageReader ?: run {
        resetLoadingAnimation()
        return
    }

    var image: android.media.Image? = null

    // ลองหลายรอบ + ใช้ทั้ง latest และ next
    for (attempt in 1..5) {
        try {
            image = imageReader.acquireLatestImage()
            if (image == null) {
                image = imageReader.acquireNextImage()
            }
            if (image != null) break
        } catch (e: Exception) {
            Log.w(TAG, "Acquire attempt $attempt failed: ${e.message}")
        }
        SystemClock.sleep(80)
    }

    if (image == null) {
        resetLoadingAnimation()
        Toast.makeText(this, "⚠️ แคปภาพไม่สำเร็จ ลองกดอีกครั้ง", Toast.LENGTH_SHORT).show()
        Log.e(TAG, "Failed to acquire image after retries")
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

        val bitmapWidth = image.width + rowPadding / pixelStride
        val fullBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        fullBitmap.copyPixelsFromBuffer(buffer)

        // ตัด padding ออก
        val croppedBitmap = if (rowPadding == 0) {
            fullBitmap
        } else {
            Bitmap.createBitmap(fullBitmap, 0, 0, image.width, image.height)
        }

        // บีบอัดให้เบาหน่อย (Gemini รับได้ดี)
        val baos = ByteArrayOutputStream()
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos)
        base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        // เคลียร์ memory
        if (croppedBitmap != fullBitmap) croppedBitmap.recycle()
        fullBitmap.recycle()

    } catch (e: Exception) {
        Log.e(TAG, "Error processing image: ${e.message}", e)
        resetLoadingAnimation()
        Toast.makeText(this, "❌ ประมวลผลภาพผิดพลาด", Toast.LENGTH_SHORT).show()
        return
    } finally {
        try { image.close() } catch (_: Exception) {}
    }

    if (base64Image.isEmpty()) {
        resetLoadingAnimation()
        return
    }

    // เรียก Gemini เหมือนเดิม
    val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
    val rawKeys = prefs.getString("api_keys", "") ?: ""
    val keysList = rawKeys.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    if (keysList.isEmpty()) {
        resetLoadingAnimation()
        Toast.makeText(this, "❌ ไม่พบ API Key", Toast.LENGTH_LONG).show()
        return
    }

    val model = prefs.getString("selected_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
    val thinking = prefs.getString("thinking_level", "off") ?: "off"
    val customPrompt = prefs.getString("custom_prompt", "อ่านข้อความทั้งหมดในภาพนี้แล้วแปลเป็นภาษาไทยให้ครบถ้วน ชัดเจน") ?: ""

    GeminiApi.translateWithMultiKeys(keysList, model, thinking, customPrompt, base64Image) { translatedText ->
        floatingButton.post {
            resetLoadingAnimation()
            showFullScreenResultOverlay(translatedText)
        }
    }
}

private fun requestNewPermission() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastPermissionRequestTime < 3000) {
        Toast.makeText(this, "⏳ กำลังรอสิทธิ์อยู่ ลองรอสักครู่", Toast.LENGTH_SHORT).show()
        return
    }
    lastPermissionRequestTime = now

    val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra("request_media_projection", true)
    }
    startActivity(intent)
    Toast.makeText(this, "🔒 กำลังขอสิทธิ์ Screen Capture ใหม่...", Toast.LENGTH_SHORT).show()
}