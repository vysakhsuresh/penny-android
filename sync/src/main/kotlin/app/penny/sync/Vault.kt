package app.penny.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secret store: values are AES-GCM encrypted with a Keystore key that never leaves the device (SE-01), and kept
 * in no-backup preferences so they are not copied to cloud backups. Holds the session token and unwrapped stream keys.
 */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("penny.vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    fun put(name: String, value: ByteArray?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString(name, StreamCrypto.b64(c.iv + c.doFinal(value))).apply()
    }

    fun get(name: String): ByteArray? {
        val blob = prefs.getString(name, null)?.let(StreamCrypto::unb64) ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
            c.doFinal(blob, 12, blob.size - 12)
        }.getOrNull()
    }

    fun putString(name: String, v: String?) = put(name, v?.toByteArray())
    fun string(name: String): String? = get(name)?.let { String(it) }
    fun names(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }
    fun clear() = prefs.edit().clear().apply()

    private companion object { const val ALIAS = "penny_vault_v1" }
}
