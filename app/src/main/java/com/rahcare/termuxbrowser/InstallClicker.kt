package com.rahcare.termuxbrowser

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ম্যানুয়াল চাপ ছাড়া আপডেট: Android-এর "Install/Update" নিশ্চিত-পর্দা নিজে চেপে দেয়।
 * সীমা (ইচ্ছা করে সংকীর্ণ রাখা):
 *  - শুধু প্যাকেজ-ইনস্টলারের পর্দায় চলে (res/xml/installer_clicker.xml-এ নির্দিষ্ট করা)
 *  - শুধু অ্যাপ নিজে আপডেট শুরু করার পর ৩ মিনিট ([ClickGate.open] খুললে)
 *  - পর্দার লেখায় এই অ্যাপের নাম থাকতে হবে (অন্য অ্যাপের ইনস্টলে চাপে না)
 *  - শুধু "Install" / "Update" লেখা বোতাম চাপে; "Install anyway", "Cancel" ইত্যাদি কখনো নয়
 */
object ClickGate {
    private const val WINDOW_MS = 3 * 60 * 1000L
    private fun sp(c: Context) = c.getSharedPreferences("a11y_gate", Context.MODE_PRIVATE)

    /** আপডেট কমিট করার ঠিক আগে ডাকা হয় */
    fun open(c: Context) { try { sp(c).edit().putLong("until", System.currentTimeMillis() + WINDOW_MS).commit() } catch (_: Exception) {} }
    fun isOpen(c: Context) = System.currentTimeMillis() < sp(c).getLong("until", 0L)
    fun close(c: Context) { try { sp(c).edit().putLong("until", 0L).apply() } catch (_: Exception) {} }

    /** সেটিংসে এই সার্ভিস চালু আছে কি না */
    fun enabled(c: Context): Boolean =
        (android.provider.Settings.Secure.getString(c.contentResolver, "enabled_accessibility_services") ?: "")
            .contains("${c.packageName}/")
}

class InstallClicker : AccessibilityService() {
    companion object {
        /** সার্ভিস চালু থাকলে নন-null (ব্যাকগ্রাউন্ড থেকে নিশ্চিত-পর্দা খুলতে কাজে লাগে) */
        @Volatile var instance: InstallClicker? = null
        private val INSTALLERS = setOf(
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.packageinstaller"
        )
        // শুধু এই লেখাগুলোই (পুরো মিল)। "install anyway" ইচ্ছা করে বাদ — ওটা নিরাপত্তা-সতর্কতার পর্দা, নিজে চাপা হয় না
        private val OK = setOf("install", "update", "ইনস্টল", "আপডেট", "ইনস্টল করুন", "আপডেট করুন")
    }

    override fun onServiceConnected() {
        instance = this
        // আপডেটের পর সার্ভিস আবার চালু হলে: অ্যাপ এখনও না খুলে থাকলে সার্ভিস থেকে খোলার চেষ্টা (অ্যাক্সেসিবিলিটি সার্ভিস ব্যাকগ্রাউন্ড থেকে অ্যাপ খুলতে পারে)
        if (Reopen.pending(this)) {
            try { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Exception) {}
        }
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!ClickGate.isOpen(this)) return
        val root = (try { rootInActiveWindow } catch (_: Exception) { null }) ?: return
        if (root.packageName?.toString() !in INSTALLERS) return
        val label = applicationInfo.loadLabel(packageManager).toString()
        val texts = ArrayList<String>()
        val buttons = ArrayList<AccessibilityNodeInfo>()
        walk(root, texts, buttons)
        // এই অ্যাপেরই আপডেট কি না: পর্দায় অ্যাপের নাম থাকতে হবে
        if (texts.none { it.contains(label, ignoreCase = true) }) return
        val btn = buttons.firstOrNull { b ->
            val t = (b.text ?: b.contentDescription)?.toString()?.trim()?.lowercase() ?: return@firstOrNull false
            t in OK
        } ?: return
        if (btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ClickGate.close(this)
    }

    private fun walk(n: AccessibilityNodeInfo?, texts: MutableList<String>, buttons: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        n.text?.toString()?.let { texts.add(it) }
        if (n.isClickable && n.isEnabled) buttons.add(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), texts, buttons)
    }
}
