package app.penny.ui.lock

import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import app.penny.R
import app.penny.ui.common.BookViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PINs are never stored: PBKDF2-HMAC-SHA256, 120k iterations, random 16-byte salt, constant-time compare. */
object PinHasher {
    private const val ITER = 120_000

    fun hash(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return enc(salt) + ":" + enc(derive(pin, salt))
    }

    fun verify(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 2 || pin.isEmpty()) return false
        val expected = dec(parts[1])
        val actual = derive(pin, dec(parts[0]))
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITER, 256)).encoded

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

fun canUseBiometrics(context: android.content.Context): Boolean =
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(activity: FragmentActivity, title: String, onOk: () -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build(),
    )
}

/** SE-04 lock; SE-05 duress PIN opens the decoy profile and keeps real data locked. Back-off after failed tries. */
@Composable
fun LockScreen(onUnlocked: () -> Unit, vm: BookViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val sec = vm.prefs.security.value
    var pin by rememberSaveable { mutableStateOf("") }
    var fails by rememberSaveable { mutableStateOf(0) }
    var wait by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.unlock_title)
    val bioOk = sec.biometric && canUseBiometrics(context)
    fun bio() { (context as? FragmentActivity)?.let { promptBiometric(it, title) { onUnlocked() } } }
    LaunchedEffect(Unit) { if (bioOk) bio() }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait-- } }

    fun submit() {
        val p = pin
        pin = ""
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val ok = sec.pinHash.isNotEmpty() && PinHasher.verify(p, sec.pinHash)
            val duress = !ok && sec.duressPinHash.isNotEmpty() && PinHasher.verify(p, sec.duressPinHash) && sec.duressProfileId != null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { when {
                ok -> { fails = 0; onUnlocked() }
                duress -> {
                    vm.prefs.setCurrentProfile(sec.duressProfileId)
                    onUnlocked()
                }
                else -> { fails++; if (fails >= 5) wait = 30 * (fails - 4) }
            } }
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 360.dp)) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            val dots = stringResource(R.string.pin_entered, pin.length)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { contentDescription = dots }) {
                repeat(maxOf(4, pin.length)) { i ->
                    Surface(shape = CircleShape, color = if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(14.dp)) {}
                }
            }
            Spacer(Modifier.height(12.dp))
            if (wait > 0) Text(stringResource(R.string.pin_wait, wait), color = MaterialTheme.colorScheme.error)
            else if (fails > 0) Text(stringResource(R.string.pin_wrong), color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    row.forEach { k ->
                        when (k) {
                            "bio" -> IconButton(onClick = { bio() }, enabled = bioOk, modifier = Modifier.size(72.dp)) { if (bioOk) Icon(Icons.Outlined.Fingerprint, stringResource(R.string.use_biometric)) }
                            "del" -> IconButton(onClick = { pin = pin.dropLast(1) }, modifier = Modifier.size(72.dp)) { Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.backspace)) }
                            else -> Surface(
                                onClick = {
                                    // No verify per key press: PBKDF2 is deliberately slow; the user taps Unlock.
                                    if (wait == 0 && pin.length < 12) pin += k
                                },
                                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp),
                            ) { Box(contentAlignment = Alignment.Center) { Text(k, style = MaterialTheme.typography.headlineSmall) } }
                        }
                    }
                }
            }
            TextButton(onClick = { submit() }, enabled = pin.length >= 4 && wait == 0, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock)) }
        }
    }
}
