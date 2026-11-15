package app.penny.domain

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SE-03 recovery kit and SY-05 encrypted backups. A 24-word phrase (192 bits) is shown once; the backup key is derived
 * from it with PBKDF2-HMAC-SHA256 and a random salt, and kept on this device only wrapped by a Keystore key, so
 * scheduled backups need no prompt. A new device restores by typing the phrase. Format:
 * "PENNYENC1" | salt(16) | iv(12) | AES-256-GCM ciphertext.
 */
object BackupCrypto {
    private val MAGIC = "PENNYENC1".toByteArray()
    private const val ITER = 200_000
    private const val WRAP_ALIAS = "penny_backup_wrap_v1"

    val WORDS: List<String> = (
        "able acid aged also area army away baby back ball band bank base bath bear beat bell belt best bird blow blue boat body bone book boot born boss both bowl " +
            "bulk burn bush busy cake call calm came camp card care cart case cash cast cell chat chip city clay club coal coat code cold come cook cool cope copy " +
            "core corn cost crew crop dark data date dawn days deal dear deep deer desk dial diet dish dock door dose down draw drop drum duck dust duty each earn " +
            "east easy edge else even ever face fact fair fall farm fast fear feed feel fern file fill film find fine fire firm fish five flag flat flow folk food " +
            "foot form fort four free from fuel full fund gain game gate gave gear gift girl give glad glow goal goat gold golf good gray grew grid grow gulf hair " +
            "half hall hand hang hard harm hawk head heal heap heat held help herb here hero high hill hint hold hole home hook hope horn host hour huge hunt idea " +
            "inch iron item jazz join joke jump jury keen keep kept kind king kite knee knot know lady lake lamp land lane last late lawn lead leaf lean left lend " +
            "lens life lift like lily lime line link lion list live load loan lock loft long look loop lord loud love luck lung made mail main make mall many map " +
            "mark mass mast meal meat meet melt menu mild milk mill mind mine mint miss mist mode moon more moss most move much must name navy near neat neck need " +
            "nest news next nice nine node none noon norm nose note oak oats once only open oven over pace pack page paid pair palm park part pass past path peak"
        ).split(' ').distinct().take(256)

    init { require(WORDS.size == 256) { "word list must have 256 words, has ${WORDS.size}" } }

    fun newPhrase(): List<String> {
        val r = SecureRandom()
        return List(24) { WORDS[r.nextInt(256)] }
    }

    private fun derive(phrase: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(normalise(phrase).toCharArray(), salt, ITER, 256)).encoded

    fun normalise(phrase: String) = phrase.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

    fun validPhrase(phrase: String) = normalise(phrase).split(' ').let { w -> w.size == 24 && w.all { it in WORDS } }

    /** Sets up the backup key from a new phrase; stores salt and the Keystore-wrapped key in prefs. */
    fun enable(prefs: AppPrefs, phrase: List<String>) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(phrase.joinToString(" "), salt)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        prefs.setString("backup.salt", b64(salt))
        prefs.setString("backup.key", b64(c.iv) + ":" + b64(c.doFinal(key)))
    }

    fun disable(prefs: AppPrefs) { prefs.setString("backup.salt", null); prefs.setString("backup.key", null) }

    fun enabled(prefs: AppPrefs) = prefs.string("backup.key") != null

    private fun storedKey(prefs: AppPrefs): Pair<ByteArray, ByteArray>? {
        val salt = prefs.string("backup.salt")?.let(::unb64) ?: return null
        val (iv, ct) = prefs.string("backup.key")?.split(':')?.takeIf { it.size == 2 } ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, unb64(iv))) }
        return salt to c.doFinal(unb64(ct))
    }

    fun maybeEncrypt(prefs: AppPrefs, json: String): ByteArray {
        val (salt, key) = runCatching { storedKey(prefs) }.getOrNull() ?: return json.toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        return MAGIC + salt + c.iv + c.doFinal(json.toByteArray())
    }

    fun isEncrypted(bytes: ByteArray) = bytes.size > MAGIC.size + 28 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** Decrypts with the phrase; throws on a wrong phrase (GCM tag mismatch). */
    fun decrypt(bytes: ByteArray, phrase: String): String {
        var i = MAGIC.size
        val salt = bytes.copyOfRange(i, i + 16); i += 16
        val iv = bytes.copyOfRange(i, i + 12); i += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(phrase, salt), "AES"), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(bytes.copyOfRange(i, bytes.size)))
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
