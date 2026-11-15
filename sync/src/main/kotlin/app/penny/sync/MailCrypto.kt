package app.penny.sync

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CP-06 "penny-mail-v1" (matches server/mail.go): P-256 ECDH with an ephemeral sender key, HKDF-SHA256
 * (salt = ephPub ‖ recipientPub, info = "penny-mail-v1"), AES-256-GCM with AAD "penny-mail-v1".
 * Blob = ephPub(65, uncompressed) ‖ nonce(12) ‖ ciphertext ‖ tag(16). P-256 works on every supported Android version.
 */
object MailCrypto {
    private val INFO = "penny-mail-v1".toByteArray()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** 65-byte uncompressed point 0x04 ‖ X ‖ Y. */
    fun uncompressed(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(pub.w.affineX) + fixed(pub.w.affineY)

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun encodePrivate(k: KeyPair): ByteArray = k.private.encoded
    fun encodePublic(k: KeyPair): ByteArray = k.public.encoded

    fun restore(priv: ByteArray, pub: ByteArray): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub)), kf.generatePrivate(PKCS8EncodedKeySpec(priv)))
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int = 32): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        return mac.doFinal(info + byteArrayOf(1)).copyOf(len)
    }

    fun decrypt(blob: ByteArray, keys: KeyPair): ByteArray {
        val priv = keys.private as ECPrivateKey
        val ephRaw = blob.copyOfRange(0, 65)
        require(ephRaw[0] == 4.toByte()) { "bad point" }
        val x = BigInteger(1, ephRaw.copyOfRange(1, 33))
        val y = BigInteger(1, ephRaw.copyOfRange(33, 65))
        val eph = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), priv.params))
        val z = KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(eph, true) }.generateSecret()
        val key = hkdf(z, ephRaw + uncompressed(keys.public as ECPublicKey), INFO)
        val nonce = blob.copyOfRange(65, 77)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(INFO) }
        return c.doFinal(blob, 77, blob.size - 77)
    }
}
