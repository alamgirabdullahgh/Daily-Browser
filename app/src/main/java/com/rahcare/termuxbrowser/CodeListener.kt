package com.rahcare.termuxbrowser

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** সর্বশেষ GitHub ভেরিফিকেশন কোড (শুধু মেমরিতে, কোথাও সেভ বা লগ হয় না) */
object CodeBox {
    @Volatile private var code = ""
    @Volatile private var at = 0L
    fun put(c: String) { code = c; at = System.currentTimeMillis() }
    fun peek(maxAgeMs: Long): String? = if (code.isNotEmpty() && System.currentTimeMillis() - at <= maxAgeMs) code else null
    fun clear() { code = ""; at = 0L }
}

/**
 * নোটিফিকেশন-অ্যাক্সেস দিলে চলে: যে নোটিফিকেশনে "github" আছে এবং ৬–৮ অঙ্কের কোড আছে (যেমন ইমেইলের
 * "Verification code"), সেই কোডটা CodeBox-এ রাখে, যাতে GitHub ডিভাইস-ভেরিফাই পাতায় নিজে বসানো যায়।
 */
class CodeListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            val e = sbn?.notification?.extras ?: return
            val parts = ArrayList<String>()
            for (k in listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_SUMMARY_TEXT)) e.getCharSequence(k)?.let { parts.add(it.toString()) }
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts.add(it.toString()) }
            val txt = parts.joinToString(" \n ")
            if (!txt.contains("github", ignoreCase = true)) return
            val m = Regex("(?i)code[^0-9]{0,40}(?<!\\d)(\\d{6,8})(?!\\d)").find(txt)
                ?: Regex("(?<!\\d)(\\d{6,8})(?!\\d)").find(txt) ?: return
            CodeBox.put(m.groupValues[1])
        } catch (_: Exception) {}
    }
}
