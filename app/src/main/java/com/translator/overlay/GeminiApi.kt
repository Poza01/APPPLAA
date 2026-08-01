package com.translator.overlay

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object GeminiApi {
    private val client = OkHttpClient()

    fun translateImage(apiKey: String, base64Image: String, callback: (String) -> Unit) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().apply {
                    put(JSONObject().put("text", "อ่านข้อความภาษาจีน/อังกฤษในภาพนี้ แล้วแปลทั้งหมดเป็นภาษาไทย สละสลวย คำแทนตัวใช้ 'ข้า/เจ้า' ให้สอดคล้องบริบท สระและวรรณยุกต์ไทยต้องครบ ห้ามขาดหาย"))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }))
                })
            ))
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback("เกิดข้อผิดพลาดในการเชื่อมต่อ: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
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
                    callback("แปลล้มเหลว หรือ Key ผิดพลาด")
                }
            }
        })
    }
}