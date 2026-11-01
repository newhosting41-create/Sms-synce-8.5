package com.gatenoc.bkashsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppStorage {
    private const val PREF_NAME = "FlutterSharedPreferences"
    private const val KEY_LOGGED_IN = "flutter.logged_in"
    private const val KEY_ENDPOINT = "flutter.endpoint_url"
    private const val KEY_DEVICE_KEY = "flutter.device_key"
    private const val KEY_AUTO_CLEANUP_HOURS = "flutter.auto_cleanup_hours"
    private const val KEY_SMS_HISTORY = "flutter.sms_history"
    private const val KEY_LAST_PROCESSED_SMS_ID = "flutter.last_inbox_sms_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGED_IN, false)

    fun setLoggedIn(context: Context, loggedIn: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOGGED_IN, loggedIn).apply()
    }

    fun getEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, "")?.trim() ?: ""

    fun getDeviceKey(context: Context): String =
        prefs(context).getString(KEY_DEVICE_KEY, "")?.trim() ?: ""

    fun saveLoginConfig(context: Context, endpoint: String, deviceKey: String) {
        prefs(context).edit()
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_DEVICE_KEY, deviceKey.trim())
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }

    fun logout(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
    }

    fun getAutoCleanupHours(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_CLEANUP_HOURS, 0) // 0 = Disabled

    fun setAutoCleanupHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_AUTO_CLEANUP_HOURS, hours).apply()
    }

    fun getLastInboxSmsId(context: Context): Long =
        prefs(context).getLong(KEY_LAST_PROCESSED_SMS_ID, 0L)

    fun setLastInboxSmsId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_PROCESSED_SMS_ID, id).apply()
    }

    @Synchronized
    fun getRecords(context: Context): MutableList<SmsRecord> {
        val raw = prefs(context).getString(KEY_SMS_HISTORY, "[]") ?: "[]"
        val list = mutableListOf<SmsRecord>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SmsRecord(
                        id = o.optString("id"),
                        sender = o.optString("sender", "bKash"),
                        senderNumber = o.optString("senderNumber", ""),
                        amountBdt = o.optDouble("amountBdt", 0.0),
                        feeBdt = o.optDouble("feeBdt", 0.0),
                        balanceAfter = o.optDouble("balanceAfter", 0.0),
                        trxId = o.optString("trxId", ""),
                        smsTimeStr = o.optString("smsTimeStr", ""),
                        message = o.optString("message", ""),
                        status = o.optString("status", "Pending"),
                        time = o.optLong("time", System.currentTimeMillis()),
                        dateText = o.optString("date_text", ""),
                        source = o.optString("source", "Auto"),
                        serverNote = o.optString("serverNote", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    @Synchronized
    fun saveRecords(context: Context, records: List<SmsRecord>) {
        val arr = JSONArray()
        val limit = minOf(records.size, 500)
        for (i in 0 until limit) {
            val r = records[i]
            val o = JSONObject().apply {
                put("id", r.id)
                put("sender", r.sender)
                put("senderNumber", r.senderNumber)
                put("amountBdt", r.amountBdt)
                put("feeBdt", r.feeBdt)
                put("balanceAfter", r.balanceAfter)
                put("trxId", r.trxId)
                put("smsTimeStr", r.smsTimeStr)
                put("message", r.message)
                put("status", r.status)
                put("time", r.time)
                put("date_text", r.dateText)
                put("source", r.source)
                put("serverNote", r.serverNote)
            }
            arr.put(o)
        }
        prefs(context).edit().putString(KEY_SMS_HISTORY, arr.toString()).apply()
    }

    @Synchronized
    fun insertIfNew(context: Context, record: SmsRecord): Boolean {
        val list = getRecords(context)
        val exists = list.any { it.trxId.equals(record.trxId, ignoreCase = true) }
        if (exists) return false
        list.add(0, record)
        saveRecords(context, list)
        return true
    }

    @Synchronized
    fun updateStatusByIdOrTrx(context: Context, idOrTrx: String, newStatus: String, note: String = "") {
        val list = getRecords(context)
        var changed = false
        for (item in list) {
            if (item.id == idOrTrx || item.trxId.equals(idOrTrx, ignoreCase = true)) {
                item.status = newStatus
                if (note.isNotEmpty()) item.serverNote = note
                changed = true
            }
        }
        if (changed) saveRecords(context, list)
    }

    @Synchronized
    fun deleteRecord(context: Context, id: String) {
        val list = getRecords(context).filterNot { it.id == id }
        saveRecords(context, list)
    }

    @Synchronized
    fun clearByCategory(context: Context, category: String) {
        val list = getRecords(context)
        val remaining = when (category) {
            "ALL" -> emptyList()
            "Pending" -> list.filterNot { it.status == "Pending" }
            "Success" -> list.filterNot { it.status == "Success" || it.status == "Used" }
            "Failed" -> list.filterNot { it.status == "Failed" || it.status == "Flagged" }
            else -> list
        }
        saveRecords(context, remaining)
    }

    @Synchronized
    fun runAutoCleanupIfNeeded(context: Context): Int {
        val hours = getAutoCleanupHours(context)
        if (hours <= 0) return 0
        val cutoff = System.currentTimeMillis() - (hours * 3600_000L)
        val list = getRecords(context)
        val before = list.size
        val remaining = list.filter { it.time >= cutoff }
        if (remaining.size != before) {
            saveRecords(context, remaining)
        }
        return before - remaining.size
    }

    fun getTodayCounters(context: Context): Triple<Int, Int, Int> {
        val todayPrefix = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val list = getRecords(context)
        var recentToday = 0
        var successToday = 0
        var failedToday = 0
        for (item in list) {
            if (item.dateText.startsWith(todayPrefix)) {
                recentToday++
                if (item.status == "Success" || item.status == "Used") successToday++
                if (item.status == "Failed") failedToday++
            }
        }
        return Triple(recentToday, successToday, failedToday)
    }
}
