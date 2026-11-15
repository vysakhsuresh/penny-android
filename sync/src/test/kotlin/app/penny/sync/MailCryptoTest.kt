package app.penny.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** Encrypts exactly as server/mail.go documents and checks the Android decrypt opens it. */
class MailCryptoTest : FunSpec({
    test("penny-mail-v1 round trip with P-256") {
        val recipient = MailCrypto.newKeyPair()
        val recipientPub = MailCrypto.uncompressed(recipient.public as ECPublicKey)
        recipientPub.size shouldBe 65

        // Server side: ephemeral key, ECDH, HKDF, AES-GCM.
        val eph = MailCrypto.newKeyPair()
        val ephPub = MailCrypto.uncompressed(eph.public as ECPublicKey)
        val z = KeyAgreement.getInstance("ECDH").apply { init(eph.private); doPhase(recipient.public, true) }.generateSecret()
        val info = "penny-mail-v1".toByteArray()
        val key = MailCrypto.hkdf(z, ephPub + recipientPub, info)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plain = """{"from":"orders@shop.example","subject":"Your order","text":"Total ₹1,250.00","receivedAt":1}"""
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(info) }.doFinal(plain.toByteArray())
        val blob = ephPub + nonce + ct

        String(MailCrypto.decrypt(blob, recipient)) shouldBe plain
        // Keys survive encode/restore (stored in the vault between runs).
        val restored = MailCrypto.restore(MailCrypto.encodePrivate(recipient), MailCrypto.encodePublic(recipient))
        String(MailCrypto.decrypt(blob, restored)) shouldBe plain
    }

    test("HKDF matches RFC 5869 test case 1") {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = MailCrypto.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 32)
        okm.joinToString("") { "%02x".format(it) } shouldBe "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
    }
})
