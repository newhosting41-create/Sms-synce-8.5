package com.gatenoc.bkashsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ApiResult(
    val httpCode: Int,
    val success: Boolean,
    val status: String,
    val message: String,
    val json: JSONObject? = null
)

object ApiClient {

    private fun postJson(endpoint: String, deviceKey: String, payload: JSONObject): ApiResult {
        if (endpoint.isBlank() || deviceKey.length < 16) {
            return ApiResult(0, false, "error", "Invalid Device Key or Endpoint URL")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                // Exact header expected by your PHP backend (payment/bkash_sms_sync.php)
                setRequestProperty("X-Device-Token", deviceKey)
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            val json = try { JSONObject(responseText) } catch (_: Exception) { null }
            val ok = json?.optBoolean("success", false) ?: false
            val st = json?.optString("status", "") ?: ""
            val msg = json?.optString("message", if (ok) "Connected" else "HTTP $code") ?: "HTTP $code"

            ApiResult(code, ok, st, msg, json)
        } catch (e: Exception) {
            ApiResult(0, false, "network_error", e.localizedMessage ?: "Could not connect to server")
        } finally {
            conn?.disconnect()
        }
    }

    // 1. Connect / Ping Server: {"action": "ping"}
    fun pingServer(endpoint: String, deviceKey: String): ApiResult {
        val body = JSONObject().apply {
            put("action", "ping")
        }
        return postJson(endpoint, deviceKey, body)
    }

    // 2. Upload SMS Event to Server: {"sender": "bKash", "raw_sms": "...", "local_event_id": "..."}
    fun uploadSms(endpoint: String, deviceKey: String, record: SmsRecord): ApiResult {
        val body = JSONObject().apply {
            put("sender", "bKash")
            put("raw_sms", record.message)
            put("local_event_id", record.id)
        }
        return postJson(endpoint, deviceKey, body)
    }

    // 3. Check Claimed/Used TrxIDs on Website: {"action": "status", "trx_ids": [...]}
    fun checkStatuses(endpoint: String, deviceKey: String, trxIds: List<String>): Map<String, String> {
        if (trxIds.isEmpty()) return emptyMap()
        val arr = JSONArray()
        trxIds.take(100).forEach { arr.put(it) }

        val body = JSONObject().apply {
            put("action", "status")
            put("trx_ids", arr)
        }

        val res = postJson(endpoint, deviceKey, body)
        val map = mutableMapOf<String, String>()
        if (res.success && res.json != null) {
            val items = res.json.optJSONObject("items")
            if (items != null) {
                val keys = items.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k.uppercase()] = items.optString(k, "unused")
                }
            }
        }
        return map
    }
}
