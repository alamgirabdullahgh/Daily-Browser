package com.rahcare.termuxbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * অটো-লগইনের ইমেইল → পাসওয়ার্ড ভাণ্ডার। পুরো তালিকা একটি JSON হিসেবে Android Keystore-এর AES চাবিতে এনক্রিপ্ট হয়ে থাকে
 * (Secret.kt-এর মতোই)। পাসওয়ার্ড কোডে, লগে, GitHub-এ বা এজেন্ট-বাসে কখনো যায় না; শুধু এই অ্যাপই খুলতে পারে।
 */
object Creds {
    private const val ALIAS = "tb_creds"
    private const val PREF = "creds_enc"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun read(c: Context): JSONObject = try {
        val raw = Store.prefs(c).getString(PREF, null) ?: ""
        if (raw.isEmpty()) JSONObject() else {
            val p = raw.split(":")
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
            JSONObject(String(ci.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), Charsets.UTF_8))
        }
    } catch (_: Exception) { JSONObject() }

    private fun write(c: Context, o: JSONObject): Boolean = try {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
        val ct = ci.doFinal(o.toString().toByteArray(Charsets.UTF_8))
        Store.prefs(c).edit().putString(PREF, Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        true
    } catch (_: Exception) { false }

    private fun k(email: String) = email.trim().lowercase()

    fun emails(c: Context): List<String> = read(c).keys().asSequence().toList().sorted()
    fun has(c: Context, email: String) = read(c).has(k(email))
    fun get(c: Context, email: String): String = read(c).optString(k(email), "")
    fun put(c: Context, email: String, pw: String): Boolean {
        if (email.isBlank() || pw.isEmpty()) return false
        val o = read(c); o.put(k(email), pw); return write(c, o)
    }
    fun remove(c: Context, email: String): Boolean { val o = read(c); o.remove(k(email)); return write(c, o) }
}
