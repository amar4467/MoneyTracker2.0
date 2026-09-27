package com.amar.moneytracker

import android.app.Notification
import android.os.Bundle
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

class MoneyNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(160)
            val candidates = buildList {
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(4096)?.let(::add)
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(4096)?.let(::add)
                extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")?.take(4096)?.let(::add)
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { parcelable ->
                    (parcelable as? Bundle)?.getCharSequence("text")?.toString()?.take(4096)?.let(::add)
                }
                sbn.notification.tickerText?.toString()?.take(4096)?.let(::add)
            }.filter { it.isNotBlank() }.distinct()
            if (candidates.isEmpty()) return
            Log.d(TAG, "notification received from ${sbn.packageName}; candidates=${candidates.size}")
            candidates.forEachIndexed { index, candidate -> Log.d(TAG, "candidate[$index]=${candidate.take(180)}") }
            val parsedCandidate = candidates.asSequence()
                .mapNotNull { candidate -> BankNotificationParser.parse(title, candidate)?.let { it to candidate } }
                .firstOrNull() ?: run {
                Log.d(TAG, "notification ignored: no transaction candidate")
                return
            }
            val parsed = parsedCandidate.first
            val text = parsedCandidate.second
            Log.i(TAG, "candidate type=${parsed.type} amountCents=${parsed.amountCents}")
            val sourceHash = sha256("${sbn.packageName}|${sbn.key}|${text.trim()}")
            val pending = PendingImport(
                id = 0,
                sourceHash = sourceHash,
                packageName = sbn.packageName,
                rawText = text,
                title = parsed.title,
                amountCents = parsed.amountCents,
                type = parsed.type,
                dateMillis = parsed.dateMillis ?: sbn.postTime
            )
            val database = TransactionDatabase(applicationContext)
            try {
                if (database.addPendingImport(pending)) {
                    Log.i(TAG, "pending import stored")
                    sendBroadcast(android.content.Intent(ACTION_PENDING_CHANGED).setPackage(this@MoneyNotificationListener.packageName))
                } else Log.d(TAG, "duplicate notification ignored")
            } finally {
                database.close()
            }
        }.onFailure { error -> Log.w(TAG, "notification callback failed: ${error.javaClass.simpleName}") }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object { const val TAG = "MoneyListener" }
}
