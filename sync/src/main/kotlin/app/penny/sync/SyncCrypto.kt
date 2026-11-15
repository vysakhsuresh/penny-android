package app.penny.sync

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SE-02 symmetric layer: every stream (a profile or a space) has a random 256-bit key. Event batches are sealed with
 * AES-256-GCM, the stream id bound as associated data so a blob cannot be replayed into another stream. The relay
 * stores only these blobs.
 */
object StreamCrypto {
    private const val IV = 12
    private val rnd = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also(rnd::nextBytes)

    fun seal(key: ByteArray, plain: ByteArray, aad: String): ByteArray {
        val iv = ByteArray(IV).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad.toByteArray()) }
        return iv + c.doFinal(plain)
    }

    fun open(key: ByteArray, blob: ByteArray, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, IV)); updateAAD(aad.toByteArray())
        }
        return c.doFinal(blob, IV, blob.size - IV)
    }

    /** Account master key from the recovery phrase (SE-03): PBKDF2-HMAC-SHA256, 200k iterations, per-account salt. */
    fun masterKey(phrase: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(phrase.toCharArray(), salt, 200_000, 256)).encoded

    fun wrap(master: ByteArray, streamKey: ByteArray, stream: String) = seal(master, streamKey, "wrap:$stream")
    fun unwrap(master: ByteArray, wrapped: ByteArray, stream: String) = open(master, wrapped, "wrap:$stream")

    /** Base64url without padding, matching the relay (it accepts padded input too). */
    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)
}

/**
 * SE-02 asymmetric layer: each device has an X25519 HPKE key pair (Tink), private part kept in a keyset encrypted by an
 * Android Keystore key. Space keys are wrapped to each member's public key so the relay never sees them.
 */
class DeviceKeys(context: Context) {
    private val handle: KeysetHandle

    init {
        HybridConfig.register()
        handle = AndroidKeysetManager.Builder()
            .withSharedPref(context, "device_keyset", "penny_device_keys")
            .withKeyTemplate(com.google.crypto.tink.KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))
            .withMasterKeyUri("android-keystore://penny_device_master_v1")
            .build().keysetHandle
    }

    /** Public key set, safe to publish to the relay. */
    fun publicKey(): String = StreamCrypto.b64(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle))

    fun decrypt(ciphertext: ByteArray, contextInfo: String): ByteArray =
        handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(ciphertext, contextInfo.toByteArray())

    companion object {
        fun encryptFor(publicKey: String, plain: ByteArray, contextInfo: String): ByteArray {
            HybridConfig.register()
            val pub = TinkProtoKeysetFormat.parseKeysetWithoutSecret(StreamCrypto.unb64(publicKey))
            return pub.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(plain, contextInfo.toByteArray())
        }
    }
}
