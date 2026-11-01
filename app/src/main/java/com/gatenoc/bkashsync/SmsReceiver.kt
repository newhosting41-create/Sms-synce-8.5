package com.gatenoc.bkashsync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UI_REFRESH = "com.gatenoc.bkashsync.ACTION_UI_REFRESH"

        // Replicates com/subpay/paykureghor/SmsReceiver.c() live notification counter update
        fun updateLiveNotification(context: Context, lastEventText: String) {
            try {
                val (recent, success, failed) = AppStorage.getTodayCounters(context)
                val summary = "$lastEventText\nRecent: $recent | Success: $success | Failed: $failed"

                val pendingIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, SyncForegroundService.CHANNEL_ID)
                    .setContentTitle("bKash SMS Sync is Running")
                    .setContentText(lastEventText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pendingIntent)
                    .build()

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(SyncForegroundService.NOTIF_ID, notification)
            } catch (_: Exception) {}
        }

        // Direct background thread uploader (Exact replica of LD0/k.run in base.apk!)
        fun uploadRecordImmediately(
            context: Context,
            record: SmsRecord,
            pendingResult: PendingResult? = null
        ) {
            Thread {
                try {
                    val endpoint = AppStorage.getEndpoint(context)
                    val deviceKey = AppStorage.getDeviceKey(context)

                    if (endpoint.isNotBlank() && deviceKey.length >= 16) {
                        val res = ApiClient.uploadSms(endpoint, deviceKey, record)
                        Log.d("SMS_UPLOAD", "Code: ${res.httpCode} | ${res.message}")

                        if (res.success) {
                            val newSt = if (res.status.equals("flagged", ignoreCase = true)) "Flagged" else "Success"
                            AppStorage.updateStatusByIdOrTrx(context, record.id, newSt, "Synced (${res.status.ifEmpty { "ok" }})")
                            updateLiveNotification(context, "Last SMS: ${record.sender} Tk ${record.amountBdt} (${record.trxId}) uploaded")
                        } else {
                            val failSt = if (res.httpCode in 400..499) "Failed" else "Pending"
                            AppStorage.updateStatusByIdOrTrx(context, record.id, failSt, "Code: ${res.httpCode} - ${res.message}")
                            updateLiveNotification(context, "Last SMS: ${record.sender} upload failed (Code: ${res.httpCode})")
                        }
                    }
                    context.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(context.packageName))
                } catch (e: Exception) {
                    Log.e("SMS_UPLOAD_ERROR", e.toString())
                    AppStorage.updateStatusByIdOrTrx(context, record.id, "Pending", e.localizedMessage ?: "Network error")
                    updateLiveNotification(context, "Last SMS: ${record.sender} upload error")
                } finally {
                    try { pendingResult?.finish() } catch (_: Exception) {}
                }
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppStorage.isLoggedIn(context)) return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        var senderAddress = ""
        val messageBuilder = StringBuilder()

        for (pdu in pdus) {
            val bytes = pdu as? ByteArray ?: continue
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format != null) {
                SmsMessage.createFromPdu(bytes, format)
            } else {
                SmsMessage.createFromPdu(bytes)
            }
            if (senderAddress.isEmpty()) {
                senderAddress = sms.displayOriginatingAddress ?: ""
            }
            messageBuilder.append(sms.displayMessageBody ?: "")
        }

        val fullBody = messageBuilder.toString()
        if (!SmsParser.isAllowedBkashSender(senderAddress)) return

        val record = SmsParser.parseIncomingOrManual(senderAddress, fullBody, "Auto") ?: return

        val isNew = AppStorage.insertIfNew(context, record)
        if (isNew) {
            context.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(context.packageName))
            // Keep BroadcastReceiver alive in background while Thread uploads immediately
            val pendingResult = goAsync()
            uploadRecordImmediately(context.applicationContext, record, pendingResult)
        }
    }
}
