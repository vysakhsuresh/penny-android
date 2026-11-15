package app.penny.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.billing.Billing
import app.penny.core.model.Role
import app.penny.domain.BackupCrypto
import app.penny.domain.SyncManager
import app.penny.domain.SyncPhase
import app.penny.domain.ops.period
import app.penny.sync.DeviceInfo
import app.penny.sync.MemberInfo
import app.penny.ui.common.WithBook
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.Pill
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.TypedConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(val sync: SyncManager) : ViewModel()

/**
 * US-01, SE-02/03/06, SY-02/04/09, CP-10, US-14: sign in by emailed code, recovery phrase, sync status and pending
 * count, devices with remote sign-out, bank connections, advisor links, delete account on the relay.
 */
@Composable
fun SyncScreen(onBack: () -> Unit, svm: SyncViewModel = hiltViewModel()) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val st by svm.sync.state.collectAsStateWithLifecycle()
        var email by rememberSaveable { mutableStateOf(st.email.orEmpty()) }
        var relay by rememberSaveable { mutableStateOf("") }
        var code by rememberSaveable { mutableStateOf("") }
        var codeSent by rememberSaveable { mutableStateOf(false) }
        var newPhrase by rememberSaveable { mutableStateOf<List<String>?>(null) }
        var phraseInput by rememberSaveable { mutableStateOf("") }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
        var deleteAccount by rememberSaveable { mutableStateOf(false) }
        var busy by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(st.phase) { if (st.phase == SyncPhase.READY) devices = runCatching { svm.sync.devices() }.getOrNull() }
        PennyScreen(stringResource(R.string.sync_and_banks), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                when (st.phase) {
                    SyncPhase.LOCAL_ONLY -> {
                        PennyCard {
                            Column(Modifier.padding(16.dp)) {
                                Row { Icon(Icons.Outlined.CloudOff, null); Spacer(Modifier.padding(6.dp)); Text(stringResource(R.string.local_only), style = MaterialTheme.typography.titleMedium) }
                                Text(stringResource(R.string.local_only_body), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        SectionHeader(stringResource(R.string.turn_on_sync))
                        TextInput(email, { email = it }, stringResource(R.string.email), keyboard = androidx.compose.ui.text.input.KeyboardType.Email)
                        if (!codeSent) {
                            TextInput(relay, { relay = it }, stringResource(R.string.relay_server_optional), supporting = stringResource(R.string.relay_server_hint))
                            PrimaryButton(stringResource(R.string.send_code), {
                                busy = true; error = null
                                scope.launch { runCatching { svm.sync.startSignIn(email, relay) }.onSuccess { codeSent = true }.onFailure { error = context.getString(R.string.sync_error, it.message ?: "") }; busy = false }
                            }, Modifier.padding(horizontal = 16.dp), enabled = email.contains('@') && !busy)
                        } else {
                            TextInput(code, { code = it.filter(Char::isDigit).take(6) }, stringResource(R.string.code_from_email), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                            PrimaryButton(stringResource(R.string.verify), {
                                busy = true; error = null
                                scope.launch {
                                    runCatching { svm.sync.verify(code) }.onSuccess {
                                        // New accounts get a phrase now; an existing account on a new device types its phrase.
                                        if (svm.sync.isNewAccount()) newPhrase = BackupCrypto.newPhrase()
                                    }.onFailure { error = context.getString(R.string.sync_error, it.message ?: "") }
                                    busy = false
                                }
                            }, Modifier.padding(horizontal = 16.dp), enabled = code.length == 6 && !busy)
                        }
                        Text(stringResource(R.string.sync_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                    }
                    SyncPhase.NEEDS_PHRASE -> {
                        SectionHeader(stringResource(R.string.recovery_phrase))
                        Text(stringResource(R.string.enter_phrase_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                        OutlinedTextField(phraseInput, { phraseInput = it }, label = { Text(stringResource(R.string.recovery_phrase)) }, modifier = Modifier.fillMaxWidth().padding(16.dp), minLines = 3)
                        Row(Modifier.padding(horizontal = 16.dp)) {
                            FilledTonalButton(onClick = {
                                busy = true
                                scope.launch { if (!svm.sync.usePhrase(phraseInput)) error = context.getString(R.string.wrong_phrase) else { error = null; svm.sync.syncNow() }; busy = false }
                            }, enabled = BackupCrypto.validPhrase(phraseInput) && !busy) { Text(stringResource(R.string.unlock)) }
                            Spacer(Modifier.padding(4.dp))
                            TextButton(onClick = { newPhrase = BackupCrypto.newPhrase() }) { Text(stringResource(R.string.new_account_phrase)) }
                        }
                    }
                    else -> {
                        PennyCard {
                            Column(Modifier.padding(16.dp)) {
                                Row { Icon(Icons.Outlined.CloudDone, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.padding(6.dp)); Text(st.email.orEmpty(), style = MaterialTheme.typography.titleMedium) }
                                Text(
                                    if (st.phase == SyncPhase.SYNCING) stringResource(R.string.syncing) else st.lastSync?.let { stringResource(R.string.last_synced, DateFormat.getDateTimeInstance().format(Date(it))) } ?: stringResource(R.string.not_synced_yet),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (st.region.isNotEmpty()) Text(stringResource(R.string.data_region, st.region.uppercase()), style = MaterialTheme.typography.bodySmall)
                                st.error?.let { Text(stringResource(R.string.sync_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                Row {
                                    TextButton(onClick = { scope.launch { svm.sync.syncNow() } }) { Text(stringResource(R.string.sync_now)) }
                                    TextButton(onClick = { svm.sync.signOut() }) { Text(stringResource(R.string.sign_out)) }
                                }
                            }
                        }
                        SectionHeader(stringResource(R.string.devices))
                        (devices ?: emptyList()).forEach { d ->
                            ListRow(d.name, subtitle = d.platform + " · " + DateFormat.getDateInstance().format(Date(d.lastSeen)) + if (d.current) " · " + stringResource(R.string.this_device) else "",
                                leading = { Avatar(d.name, d.deviceId, icon = Icons.Outlined.Devices) },
                                trailing = { if (!d.current) TextButton(onClick = { scope.launch { runCatching { svm.sync.signOutDevice(d.deviceId) }; devices = runCatching { svm.sync.devices() }.getOrNull() } }) { Text(stringResource(R.string.sign_out)) } }, divider = true)
                        }
                        SectionHeader(stringResource(R.string.danger_zone))
                        TextButton(onClick = { deleteAccount = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete_sync_account), color = MaterialTheme.colorScheme.error) }
                    }
                }
                SectionHeader(stringResource(R.string.bank_sync))
                BankSection(ctx)
                SectionHeader(stringResource(R.string.advisor_links))
                AdvisorSection(svm, ctx)
            }
        }
        newPhrase?.let { words ->
            // SE-03: shown once; the user writes it down. It also encrypts backups.
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.recovery_phrase)) },
                text = {
                    Column {
                        Text(stringResource(R.string.phrase_body))
                        Spacer(Modifier.height(12.dp))
                        Text(words.chunked(4).joinToString("\n") { it.joinToString("  ") }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                    }
                },
                confirmButton = { TextButton(onClick = { scope.launch { svm.sync.usePhrase(words.joinToString(" ")); svm.sync.syncNow() }; newPhrase = null }) { Text(stringResource(R.string.wrote_it_down)) } },
            )
        }
        if (deleteAccount) TypedConfirmDialog(
            stringResource(R.string.delete_sync_account), stringResource(R.string.delete_sync_account_body), st.email.orEmpty(), stringResource(R.string.delete),
            onConfirm = { deleteAccount = false; scope.launch { runCatching { svm.sync.deleteRemoteAccount() }.onFailure { error = it.message } } },
            onDismiss = { deleteAccount = false },
        )
    }
}

@HiltViewModel
class BankViewModel @Inject constructor(val bank: app.penny.domain.BankSync) : ViewModel()

/**
 * CP-10 / AC-09: connections with provider, status, last fetch and consent expiry; sync now, re-consent and
 * disconnect (which deletes credentials at the connector service). Plus pays the provider fees (NN-1).
 */
@Composable
private fun BankSection(ctx: app.penny.ui.common.Ctx, bvm: BankViewModel = hiltViewModel(), svm: SyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plus by Billing.plus.collectAsStateWithLifecycle()
    val st by svm.sync.state.collectAsStateWithLifecycle()
    var providers by remember { mutableStateOf<List<app.penny.sync.ConnectorInfo>?>(null) }
    var tokenFor by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val regional = ctx.pack.connectors.ifEmpty { listOfNotNull(ctx.pack.aggregatorProvider) }
    LaunchedEffect(st.phase) { if (st.phase == SyncPhase.READY) providers = runCatching { bvm.bank.providers(regional) }.getOrNull() }
    Text(stringResource(R.string.bank_sync_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
    if (regional.isNotEmpty()) Text(stringResource(R.string.providers_here, regional.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
    ctx.book.syncLinks.filter { it.value.status != app.penny.core.model.SyncStatus.DISCONNECTED }.forEach { l ->
        PennyCard {
            ListRow(
                l.value.institution.ifBlank { l.value.provider },
                subtitle = listOfNotNull(
                    l.value.provider, l.value.status.name.lowercase().replace('_', ' '),
                    l.value.lastFetchMillis?.let { stringResource(R.string.last_synced, DateFormat.getDateTimeInstance().format(Date(it))) },
                    l.value.consentExpiry?.let { stringResource(R.string.consent_until, app.penny.ui.common.shortDate(it)) },
                ).joinToString(" · "),
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { scope.launch { message = runCatching { bvm.bank.syncOne(ctx.book, l.state.id) }.fold({ context.resources.getQuantityString(R.plurals.bank_synced, it, it) }, { it.message }) } }) { Text(stringResource(R.string.sync_now)) }
                TextButton(onClick = {
                    scope.launch {
                        runCatching { bvm.bank.reconsent(ctx.book, l.state.id, null) }.onSuccess { c -> c?.redirectUrl?.let { u -> context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }.onFailure { message = it.message }
                    }
                }) { Text(stringResource(R.string.reconsent)) }
                TextButton(onClick = { scope.launch { runCatching { bvm.bank.disconnect(ctx.book, l.state.id) }; message = context.getString(R.string.disconnected) } }) { Text(stringResource(R.string.disconnect)) }
            }
        }
    }
    if (st.phase != SyncPhase.READY) Text(stringResource(R.string.bank_needs_sync), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
    providers?.forEach { p ->
        ListRow(p.name, subtitle = stringResource(if (p.kind == "token") R.string.connect_with_token else R.string.connect_in_browser), onClick = {
            if (p.kind == "token") tokenFor = p.id
            else scope.launch {
                runCatching { bvm.bank.connect(ctx.book, p.id, null) }.onSuccess { c -> c.redirectUrl?.let { u -> context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }
                    .onFailure { message = if (it.message?.contains("plus_required") == true) context.getString(R.string.bank_sync_needs_plus) else it.message }
            }
        }, divider = true)
    }
    if (!plus) Pill(stringResource(R.string.bank_sync_needs_plus), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    tokenFor?.let { provider ->
        var token by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { tokenFor = null }, title = { Text(stringResource(R.string.connect_with_token)) },
            text = { Column { Text(stringResource(R.string.simplefin_help)); OutlinedTextField(token, { token = it.trim() }, label = { Text(stringResource(R.string.setup_token)) }, minLines = 2) } },
            confirmButton = {
                TextButton(enabled = token.length > 20, onClick = {
                    scope.launch {
                        runCatching { bvm.bank.connect(ctx.book, provider, token) }
                            .onSuccess { message = context.getString(R.string.bank_connected) }
                            .onFailure { message = if (it.message?.contains("plus_required") == true) context.getString(R.string.bank_sync_needs_plus) else it.message }
                    }
                    tokenFor = null
                }) { Text(stringResource(R.string.connect)) }
            },
            dismissButton = { TextButton(onClick = { tokenFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Lands here from the provider's consent page (penny://bank/callback?state=…&code=…). */
@Composable
fun BankCallbackScreen(state: String?, params: Map<String, String>, onDone: () -> Unit, bvm: BankViewModel = hiltViewModel()) {
    WithBook { ctx, _ ->
        var msg by rememberSaveable { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        LaunchedEffect(state) {
            msg = if (state == null) context.getString(R.string.bank_cancelled)
            else runCatching { bvm.bank.complete(ctx.book, state, params) }.fold({ context.getString(R.string.bank_connected) }, { it.message })
        }
        PennyScreen(stringResource(R.string.bank_sync), onDone) { pad ->
            Column(Modifier.padding(pad).padding(20.dp)) {
                Text(msg ?: stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
private fun AdvisorSection(svm: SyncViewModel, ctx: app.penny.ui.common.Ctx) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by svm.sync.state.collectAsStateWithLifecycle()
    var days by rememberSaveable { mutableStateOf(14) }
    Text(stringResource(R.string.advisor_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
    if (st.phase != SyncPhase.READY) { Text(stringResource(R.string.advisor_needs_sync), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)); return }
    SelectField(stringResource(R.string.link_lasts), days, listOf(1, 7, 14, 30).map { Choice(it, context.resources.getQuantityString(R.plurals.days_n, it, it)) }, { it?.let { d -> days = d } })
    OutlinedButton(onClick = {
        scope.launch {
            val b = ctx.book
            val p = b.period()
            val ins = app.penny.core.insights.Insights(b.txList, ctx.base, b.excludedCategoryIds, b.fx, b::topLevel).summary(p, b.profile.cycle)
            val json = kotlinx.serialization.json.buildJsonObject {
                put("title", kotlinx.serialization.json.JsonPrimitive(b.profile.name)); put("generatedAt", kotlinx.serialization.json.JsonPrimitive(java.time.Instant.now().toString()))
                put("currency", kotlinx.serialization.json.JsonPrimitive(ctx.base)); put("note", kotlinx.serialization.json.JsonPrimitive("${p.start} – ${p.endInclusive}"))
                put("rows", kotlinx.serialization.json.JsonArray(listOf("Income" to ins.incomeMinor, "Spent" to ins.spentMinor).map { (l, v) -> kotlinx.serialization.json.buildJsonObject { put("label", kotlinx.serialization.json.JsonPrimitive(l)); put("value", kotlinx.serialization.json.JsonPrimitive(ctx.money(v))) } } +
                    ins.byCategory.take(12).map { s -> kotlinx.serialization.json.buildJsonObject { put("label", kotlinx.serialization.json.JsonPrimitive(b.categoryMap[s.key]?.value?.name ?: s.key)); put("value", kotlinx.serialization.json.JsonPrimitive(ctx.money(s.amountMinor))) } }))
            }.toString()
            runCatching { svm.sync.advisorLink(json, days) }.onSuccess { link ->
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            }
        }
    }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.create_advisor_link)) }
}

/** US-05…US-13 / SP-01…SP-05: spaces (shared books), invites with roles, member management, audit log. */
@Composable
fun SpacesScreen(onBack: () -> Unit, initialToken: String? = null, svm: SyncViewModel = hiltViewModel()) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val st by svm.sync.state.collectAsStateWithLifecycle()
        val profiles by vm.repo.observeProfiles().collectAsStateWithLifecycle(emptyList())
        val spaceRows by vm.repo.dao.observeAllOf(app.penny.core.event.Entities.SPACE).collectAsStateWithLifecycle(emptyList())
        var creating by rememberSaveable { mutableStateOf(false) }
        var members by remember { mutableStateOf<List<MemberInfo>>(emptyList()) }
        var inviteRole by rememberSaveable { mutableStateOf(Role.EDITOR) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var joinToken by rememberSaveable { mutableStateOf(initialToken.orEmpty()) }
        val isSpace = ctx.book.spaces.any { it.state.id == ctx.book.profileId }
        LaunchedEffect(ctx.book.profileId, st.phase) { if (isSpace && st.phase == SyncPhase.READY) members = runCatching { svm.sync.members(ctx.book.profileId) }.getOrDefault(emptyList()) }
        PennyScreen(stringResource(R.string.spaces), onBack) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                Text(stringResource(R.string.spaces_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (st.phase != SyncPhase.READY) Pill(stringResource(R.string.spaces_need_sync), Modifier.padding(horizontal = 16.dp))
                SectionHeader(stringResource(R.string.your_spaces))
                val spaceIds = spaceRows.map { it.id }.toSet()
                if (spaceIds.isEmpty()) Text(stringResource(R.string.no_spaces), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
                profiles.filter { p -> p.state.id in spaceIds }.forEach { p ->
                    ListRow(p.value.name, leading = { Avatar(p.value.name, p.state.id, icon = Icons.Outlined.Groups) }, onClick = { vm.prefs.setCurrentProfile(p.state.id) }, divider = true)
                }
                if (isSpace) {
                    SectionHeader(stringResource(R.string.members))
                    members.forEach { m ->
                        ListRow(m.accountId.take(8), subtitle = m.role.lowercase() + if (m.left) " · " + stringResource(R.string.ex_member) else "", leading = { Avatar(m.accountId, m.accountId) },
                            trailing = {
                                if (!m.left && m.role != "OWNER") TextButton(onClick = { scope.launch { runCatching { svm.sync.removeMember(ctx.book.profileId, m.accountId) }.onFailure { error = it.message }; members = runCatching { svm.sync.members(ctx.book.profileId) }.getOrDefault(members) } }) { Text(stringResource(R.string.remove)) }
                            }, divider = true)
                    }
                    SelectField(stringResource(R.string.invite_as), inviteRole, listOf(Role.ADMIN, Role.EDITOR, Role.CONTRIBUTOR, Role.VIEWER).map { Choice(it, roleLabel(context, it)) }, { it?.let { r -> inviteRole = r } })
                    FilledTonalButton(onClick = {
                        scope.launch {
                            runCatching { svm.sync.invite(ctx.book.profileId, inviteRole, ctx.book.profile.name) }.onSuccess { inv ->
                                val text = context.getString(R.string.invite_text, ctx.book.profile.name, svm.sync.inviteLink(inv.token))
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                            }.onFailure { error = it.message }
                        }
                    }, modifier = Modifier.padding(horizontal = 16.dp), enabled = st.phase == SyncPhase.READY) { Text(stringResource(R.string.invite_someone)) }
                    Text(stringResource(R.string.invite_expiry_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    TextButton(onClick = { scope.launch { runCatching { svm.sync.leave(ctx.book.profileId) }.onFailure { error = it.message } } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.leave_space)) }
                }
                SectionHeader(stringResource(R.string.join_space))
                TextInput(joinToken, { joinToken = it.substringAfterLast('/') }, stringResource(R.string.invite_link_or_code))
                OutlinedButton(onClick = {
                    scope.launch { runCatching { svm.sync.acceptInvite(joinToken.trim()) }.onSuccess { joinToken = ""; error = context.getString(R.string.joined_wait_key) }.onFailure { error = it.message } }
                }, modifier = Modifier.padding(horizontal = 16.dp), enabled = joinToken.isNotBlank() && st.phase == SyncPhase.READY) { Text(stringResource(R.string.join)) }
                PrimaryButton(stringResource(R.string.create_space), { creating = true }, Modifier.padding(16.dp))
            }
        }
        if (creating) app.penny.ui.catalog.NameDialog(stringResource(R.string.create_space), "", { creating = false }) { name ->
            creating = false
            scope.launch { runCatching { val id = svm.sync.createSpace(name, ctx.book.profile); vm.prefs.setCurrentProfile(id) }.onFailure { error = it.message } }
        }
    }
}

fun roleLabel(context: android.content.Context, r: Role): String = context.getString(when (r) {
    Role.OWNER -> R.string.role_owner; Role.ADMIN -> R.string.role_admin; Role.EDITOR -> R.string.role_editor
    Role.CONTRIBUTOR -> R.string.role_contributor; Role.VIEWER -> R.string.role_viewer; Role.NONE -> R.string.role_none
})

/** MO-01…MO-04 Plus: what it funds in plain words, prices from the store, restore, cancel in two taps (store page). */
@Composable
fun PlusScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val plus by Billing.plus.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Billing.restore(context) }
    PennyScreen(stringResource(R.string.plus), onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
            if (plus) Pill(stringResource(R.string.plus_active), Modifier.padding(16.dp))
            Text(stringResource(R.string.plus_intro), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SectionHeader(stringResource(R.string.plus_funds))
            listOf(R.string.plus_f1, R.string.plus_f2, R.string.plus_f3, R.string.plus_f4).forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            SectionHeader(stringResource(R.string.plus_always_free))
            Text(stringResource(R.string.plus_free_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))
            if (!plus) {
                listOf(Billing.PLUS_MONTHLY to R.string.plus_monthly, Billing.PLUS_YEARLY to R.string.plus_yearly, Billing.PLUS_LIFETIME to R.string.plus_lifetime).forEach { (sku, label) ->
                    FilledTonalButton(onClick = { Billing.buy(context, sku) {} }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Text(stringResource(label)) }
                }
            }
            TextButton(onClick = { Billing.restore(context) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.restore_purchases)) }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/account/subscriptions?package=" + context.packageName))) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.manage_cancel)) }
            Text(stringResource(R.string.plus_downgrade_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
    }
}
