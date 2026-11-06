package com.rahcare.termuxbrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub টোকেনের মতো গোপন লেখা ফোনের Android Keystore-এর চাবি দিয়ে এনক্রিপ্ট করে রাখে।
 * টোকেন কোডে বা সাধারণ সেটিংসে খোলা লেখায় থাকে না; শুধু এই অ্যাপই খুলতে পারে।
 */
object Secret {
    private const val ALIAS = "tb_gh_token"
    private const val PREF = "ghtok_enc"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    /** খালি দিলে মুছে ফেলে; সফল হলে true */
    fun put(c: Context, token: String): Boolean = try {
        val sp = Store.prefs(c)
        if (token.isBlank()) { sp.edit().remove(PREF).apply(); true }
        else {
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key())
            val ct = ci.doFinal(token.trim().toByteArray(Charsets.UTF_8))
            sp.edit().putString(PREF, Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
            true
        }
    } catch (_: Exception) { false }

    fun get(c: Context): String = try {
        val raw = Store.prefs(c).getString(PREF, null) ?: ""
        if (raw.isEmpty()) "" else {
            val (iv, ct) = raw.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(ci.doFinal(ct), Charsets.UTF_8)
        }
    } catch (_: Exception) { "" }

    fun has(c: Context) = Store.prefs(c).getString(PREF, null).isNullOrEmpty().not()
}
