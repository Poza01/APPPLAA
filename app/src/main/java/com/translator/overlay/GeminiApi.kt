package com.translator.overlay

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object GeminiApi {
    private val client = OkHttpClient()

    fun translateWithMultiKeys(
        keysList: List<String>,
        model: String,
        thinkingLevel: String,
        customPrompt: String,
        base64Image: String,
        callback: (String) -> Unit
    ) {
        tryNextKey(keysList, 0, model, thinkingLevel, customPrompt, base64Image, callback)
    }

    private fun tryNextKey(
        keysList: List<String>,
        currentIndex: Int,
        model: String,
        thinkingLevel: String,
        customPrompt: String,
        base64Image: String,
        callback: (String) -> Unit
    ) {
        if (currentIndex >= keysList.size) {
            callback("❌ API Key ทั้งหมดติด Quota หรือใช้งานไม่ได้")
            return
        }

        val currentKey = keysList[currentIndex]
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$currentKey"

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().apply {
                    put(JSONObject().put("text", customPrompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }))
                })
            ))
            
            // เพิ่มการตั้งค่า Thinking Config ถ้าเลือกใช้งาน
            if (thinkingLevel != "off") {
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", thinkingLevel)
                })
            }
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // ถ้าสัญญานหลุด ให้ลอง Key ถัดไป
                tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    // ถ้า Key นี้ติด Error / Quota ให้สลับไปลอง Key ถัดไปทันที
                    tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
                    return
                }

                try {
                    val responseText = response.body?.string() ?: ""
                    val json = JSONObject(responseText)
                    val result = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                    
                    callback(result)
                } catch (e: Exception) {
                    // ถ้าโครงสร้าง Json พังหรือ Key มีปัญหา วนไป Key ถัดไป
                    tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
                }
            }
        })
    }
}
