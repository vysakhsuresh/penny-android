package app.penny.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.automation.AutomationReceiver
import app.penny.automation.Webhooks
import app.penny.ui.common.WithBook
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import kotlinx.coroutines.launch

/** M6 automation: Tasker/automation intents (add only) and webhooks to the user's own https endpoint. */
@Composable
fun AutomationScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val scope = rememberCoroutineScope()
        val clip = LocalClipboardManager.current
        var intents by rememberSaveable { mutableStateOf(vm.prefs.flag(AutomationReceiver.FLAG_INTENTS)) }
        var url by rememberSaveable { mutableStateOf(vm.prefs.string(Webhooks.KEY_URL).orEmpty()) }
        var secret by rememberSaveable { mutableStateOf(vm.prefs.string(Webhooks.KEY_SECRET).orEmpty()) }
        var status by rememberSaveable { mutableStateOf<String?>(null) }
        PennyScreen(stringResource(R.string.automation), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                Text(stringResource(R.string.automation_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SectionHeader(stringResource(R.string.automation_apps))
                SwitchRow(stringResource(R.string.allow_automation_apps), intents, { intents = it; vm.prefs.setFlag(AutomationReceiver.FLAG_INTENTS, it) }, subtitle = stringResource(R.string.allow_automation_hint))
                Text(
                    "action: ${AutomationReceiver.ACTION_ADD}\npackage: ${vm.context.packageName}\nextras: amount, type, currency, category, payee, note, account",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(stringResource(R.string.deep_link_example), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SectionHeader(stringResource(R.string.webhooks))
                Text(stringResource(R.string.webhooks_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                TextInput(url, { url = it.trim(); vm.prefs.setString(Webhooks.KEY_URL, url.ifBlank { null }) }, stringResource(R.string.webhook_url), keyboard = KeyboardType.Uri,
                    error = if (url.isNotBlank() && !url.startsWith("https://")) stringResource(R.string.https_only) else null)
                if (secret.isNotEmpty()) Text(stringResource(R.string.signing_secret) + "\n" + secret, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { secret = Webhooks.newSecret(); vm.prefs.setString(Webhooks.KEY_SECRET, secret) }) { Text(stringResource(if (secret.isEmpty()) R.string.create_secret else R.string.new_secret)) }
                    if (secret.isNotEmpty()) TextButton(onClick = { clip.setText(AnnotatedString(secret)) }) { Text(stringResource(R.string.copy)) }
                    TextButton(enabled = url.startsWith("https://") && secret.isNotEmpty(), onClick = {
                        scope.launch {
                            val n = Webhooks.deliver(vm.repo, vm.prefs, ctx.book.profileId)
                            status = vm.context.resources.getQuantityString(R.plurals.webhook_sent, n, n)
                        }
                    }) { Text(stringResource(R.string.send_now)) }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
        }
    }
}
