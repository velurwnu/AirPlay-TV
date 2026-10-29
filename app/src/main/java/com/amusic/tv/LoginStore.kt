package com.amusic.tv

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
 * The Apple ID e-mail and password, kept on this device only, encrypted with an
 * Android Keystore key (the key never leaves the Keystore, so a copied prefs file is useless).
 */
class LoginStore(context: Context) {

    data class Login(val email: String, val password: String)

    private val prefs = context.getSharedPreferences("login", Context.MODE_PRIVATE)

    fun load(): Login? = runCatching {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        val json = JSONObject(String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8))
        Login(json.getString("email"), json.getString("password"))
    }.getOrNull()

    fun save(login: Login) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = JSONObject().put("email", login.email).put("password", login.password).toString()
        val raw = cipher.iv + cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
    }

    fun clear() = prefs.edit().remove(KEY_BLOB).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ALIAS = "apple_id_login"
        const val KEY_BLOB = "blob"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
