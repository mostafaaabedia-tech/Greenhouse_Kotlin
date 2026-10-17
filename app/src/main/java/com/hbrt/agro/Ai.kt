package com.hbrt.agro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Calls a local Ollama (OpenAI-compatible) server. Native app = no CORS setup needed. */
object Ai {
    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", Prefs.aiModel.ifBlank { "qwen2.5:1.5b" })
            .put("temperature", 0.7)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .toString()
        val req = Request.Builder()
            .url(Prefs.aiUrl.trim())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = GreenhouseClient.httpFor(req.url.host).newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("server answered HTTP ${r.code}")
            JSONObject(r.body!!.string())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
