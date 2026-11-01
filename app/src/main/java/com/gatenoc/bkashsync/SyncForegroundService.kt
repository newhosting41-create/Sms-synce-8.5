package com.gatenoc.bkashsync

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var smsInboxObserver: ContentObserver? = null

    companion object {
        const val CHANNEL_ID = "sms_gateway_service"
        const val NOTIF_ID = 7601
        const val ACTION_RETRY_NOW = "com.gatenoc.bkashsync.ACTION_RETRY_NOW"

        fun start(context: Context, retryNow: Boolean = false) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                if (retryNow) action = ACTION_RETRY_NOW
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SyncForegroundService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        val (recent, success, failed) = AppStorage.getTodayCounters(this)
        val initText = "Incoming Payment SMS will be monitored.\nRecent: $recent | Success: $success | Failed: $failed"
        startForeground(NOTIF_ID, buildNotification("Incoming Payment SMS will be monitored.", initText))

        registerSmsDatabaseObserver()

        // Background Loop: scans inbox for any missed SMS, retries Pending uploads, and syncs Used status
        serviceScope.launch {
            while (isActive) {
                scanInboxForMissedBkashSms()
                retryPendingAndSyncStatuses()
                AppStorage.runAutoCleanupIfNeeded(this@SyncForegroundService)
                delay(20_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RETRY_NOW) {
            serviceScope.launch {
                scanInboxForMissedBkashSms()
                retryPendingAndSyncStatuses()
            }
        }
        return START_STICKY
    }

    // Fallback ContentObserver for Xiaomi/Oppo/Vivo where SMS_RECEIVED broadcast might be delayed
    private fun registerSmsDatabaseObserver() {
        try {
            smsInboxObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    serviceScope.launch {
                        scanInboxForMissedBkashSms()
                        retryPendingAndSyncStatuses()
                    }
                }
            }
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                smsInboxObserver!!
            )
        } catch (_: Exception) {}
    }

    private fun scanInboxForMissedBkashSms() {
        if (!AppStorage.isLoggedIn(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cutoffTime = System.currentTimeMillis() - (30 * 60 * 1000L) // Last 30 mins
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                "date >= ?",
                arrayOf(cutoffTime.toString()),
                "date DESC LIMIT 20"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                while (c.moveToNext()) {
                    val address = if (addrIdx >= 0) c.getString(addrIdx) else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) else ""
                    if (SmsParser.isAllowedBkashSender(address)) {
                        val parsed = SmsParser.parseIncomingOrManual(address ?: "bKash", body ?: "", "Auto")
                        if (parsed != null) {
                            val added = AppStorage.insertIfNew(this, parsed)
                            if (added) {
                                SmsReceiver.uploadRecordImmediately(applicationContext, parsed)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun retryPendingAndSyncStatuses() {
        if (!AppStorage.isLoggedIn(this)) return
        val endpoint = AppStorage.getEndpoint(this)
        val deviceKey = AppStorage.getDeviceKey(this)
        if (endpoint.isBlank() || deviceKey.length < 16) return

        val records = AppStorage.getRecords(this)

        // 1. Retry Pending items
        val pendingList = records.filter { it.status == "Pending" }
        for (item in pendingList) {
            val res = ApiClient.uploadSms(endpoint, deviceKey, item)
            if (res.success) {
                val st = if (res.status.equals("flagged", ignoreCase = true)) "Flagged" else "Success"
                AppStorage.updateStatusByIdOrTrx(this, item.id, st, "Synced (${res.status.ifEmpty { "ok" }})")
                SmsReceiver.updateLiveNotification(this, "Last SMS: ${item.sender} Tk ${item.amountBdt} (${item.trxId}) uploaded")
            } else if (res.httpCode in 400..499) {
                AppStorage.updateStatusByIdOrTrx(this, item.id, "Failed", "Code: ${res.httpCode} - ${res.message}")
                SmsReceiver.updateLiveNotification(this, "Last SMS: ${item.sender} upload failed")
            }
        }

        // 2. Check website claimed status for Success items
        val syncedIds = AppStorage.getRecords(this)
            .filter { it.status == "Success" }
            .map { it.trxId }

        if (syncedIds.isNotEmpty()) {
            val statusMap = ApiClient.checkStatuses(endpoint, deviceKey, syncedIds)
            for ((trx, webSt) in statusMap) {
                if (webSt == "used") {
                    AppStorage.updateStatusByIdOrTrx(this, trx, "Used", "Claimed by user on website")
                } else if (webSt == "flagged") {
                    AppStorage.updateStatusByIdOrTrx(this, trx, "Flagged", "Flagged on server")
                }
            }
        }

        sendBroadcast(Intent(SmsReceiver.ACTION_UI_REFRESH).setPackage(packageName))
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bKashSync::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Incoming Payment SMS will be monitored."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(shortText: String, bigText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("bKash SMS Sync is Running")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            smsInboxObserver?.let { contentResolver.unregisterContentObserver(it) }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()

        if (AppStorage.isLoggedIn(this)) {
            start(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
