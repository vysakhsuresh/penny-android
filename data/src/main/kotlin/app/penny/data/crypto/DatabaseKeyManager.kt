package app.penny.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The SQLCipher passphrase is 32 random bytes, wrapped by an AES-GCM key that never leaves the Android Keystore
 * (CLAUDE.md crypto rule, SE-01). The wrapped blob lives in no-backup storage so it is never copied off the device.
 */
class DatabaseKeyManager(private val context: Context) {

    fun passphrase(): ByteArray {
        val file = File(context.noBackupFilesDir, BLOB)
        if (file.exists()) return unwrap(file.readBytes())
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeBytes(wrap(raw))
        return raw
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(raw)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return c.doFinal(blob.copyOfRange(1 + ivLen, blob.size))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "penny_db_wrap_v1"
        const val BLOB = "db.key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
