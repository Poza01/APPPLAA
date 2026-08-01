package com.translator.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GeminiApi {
    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private const val TAG = "GeminiApi"

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
            postCallback(callback, "❌ API Key ทั้งหมดติด Quota หรือใช้งานไม่ได้")
            return
        }

        val currentKey = keysList[currentIndex]
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$currentKey"

        val contentsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", customPrompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    }))
                })
            })
        }

        val jsonBody = JSONObject().apply {
            put("contents", contentsArray)
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
                Log.e(TAG, "onFailure for key index $currentIndex: ${e.message}")
                // try next key
                tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = try { response.body?.string() ?: "" } catch (ex: Exception) { "" }
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for key index $currentIndex: $responseText")
                    // try next key for HTTP errors like 401/403/429
                    tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
                    return
                }

                try {
                    val json = JSONObject(responseText)
                    var result: String? = null

                    // primary expected path: candidates -> [0] -> content -> parts -> [0] -> text
                    if (json.has("candidates")) {
                        val candidates = json.getJSONArray("candidates")
                        if (candidates.length() > 0) {
                            val first = candidates.getJSONObject(0)
                            if (first.has("content")) {
                                val content = first.getJSONObject("content")
                                if (content.has("parts")) {
                                    val parts = content.getJSONArray("parts")
                                    if (parts.length() > 0) {
                                        result = parts.getJSONObject(0).optString("text", null)
                                    }
                                }
                            }
                        }
                    }

                    // fallback: outputs -> content -> text
                    if (result.isNullOrEmpty() && json.has("outputs")) {
                        val outputs = json.getJSONArray("outputs")
                        for (i in 0 until outputs.length()) {
                            val out = outputs.getJSONObject(i)
                            if (out.has("content")) {
                                val contentArr = out.getJSONArray("content")
                                for (j in 0 until contentArr.length()) {
                                    val item = contentArr.getJSONObject(j)
                                    if (item.has("text")) {
                                        result = item.getString("text")
                                        break
                                    }
                                }
                            }
                            if (!result.isNullOrEmpty()) break
                        }
                    }

                    if (result.isNullOrEmpty()) {
                        Log.w(TAG, "No result found in response, trying next key. Raw: ${responseText.take(500)}")
                        tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
                    } else {
                        postCallback(callback, result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response for key index $currentIndex: ${e.message}")
                    tryNextKey(keysList, currentIndex + 1, model, thinkingLevel, customPrompt, base64Image, callback)
                }
            }
        })
    }

    private fun postCallback(callback: (String) -> Unit, message: String) {
        try {
            mainHandler.post { callback(message) }
        } catch (e: Exception) {
            Log.e(TAG, "postCallback error: ${e.message}")
            // as fallback call directly
            callback(message)
        }
    }
}
