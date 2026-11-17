package app.penny.ui.people

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.automirrored.outlined.Chat
import app.penny.domain.ops.deleteLending
import app.penny.domain.ops.ensurePerson
import app.penny.ui.common.safeStart
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.Routes
import app.penny.core.model.Group
import app.penny.core.model.LendDirection
import app.penny.core.model.LendingEntry
import app.penny.core.model.Person
import app.penny.core.money.CurrencyUnit
import app.penny.core.planning.RemitCircle
import app.penny.core.planning.Splitting
import app.penny.domain.ops.groupBalances
import app.penny.domain.ops.lendingBalances
import app.penny.domain.ops.saveLending
import app.penny.domain.ops.savePerson
import app.penny.ui.common.Ctx
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.AmountInput
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.StatBlock
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount
import app.penny.ui.theme.Penny
import java.math.BigDecimal

/** Text for a balance with neutral wording (PE-02): "to receive" / "to give". */
@Composable
fun balanceText(ctx: Ctx, minor: Long, currency: String): Pair<String, Color> = when {
    minor > 0 -> stringResource(R.string.to_receive_x, ctx.money(minor, currency)) to Penny.status.positive
    minor < 0 -> stringResource(R.string.to_give_x, ctx.money(-minor, currency)) to MaterialTheme.colorScheme.onSurface
    else -> stringResource(R.string.settled_up) to MaterialTheme.colorScheme.onSurfaceVariant
}

/** People tab: lending, groups, remittances and circles in one place (PE, RM, CI). */
@Composable
fun PeopleScreen(padding: PaddingValues, go: (String) -> Unit) {
    WithBook { ctx, _ ->
        val b = ctx.book
        val balances = b.lendingBalances()
        // Lending plus your share in each group (in the main currency), so the totals match what the rows below say.
        val mineInGroups = b.groups.filter { !it.value.archived && it.value.baseCurrency == ctx.base }.map { b.groupBalances(it.state.id)[Group.ME] ?: 0L }
        val toReceive = balances.values.flatMap { it.entries }.filter { it.key == ctx.base && it.value > 0 }.sumOf { it.value } + mineInGroups.filter { it > 0 }.sum()
        val toGive = balances.values.flatMap { it.entries }.filter { it.key == ctx.base && it.value < 0 }.sumOf { -it.value } + mineInGroups.filter { it < 0 }.sumOf { -it }
        var showArchived by rememberSaveable { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tab_people), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    app.penny.ui.kit.RevealAmountsButton()
                }
            }
            item {
                PennyCard {
                    Row(Modifier.padding(16.dp)) {
                        StatBlock(stringResource(R.string.to_receive), ctx.money(toReceive), Modifier.weight(1f), Penny.status.positive)
                        StatBlock(stringResource(R.string.to_give), ctx.money(toGive), Modifier.weight(1f))
                    }
                }
            }
            val people = b.people.filter { !it.value.archived }
            // Quick actions only once there is something here; while empty, each section's empty state offers them
            // (showing both read as the same buttons twice).
            if (people.isNotEmpty() || b.groups.any { !it.value.archived }) item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ go("lending/new/edit") }, label = { Text(stringResource(R.string.record_lending)) }, leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) })
                    AssistChip({ go("group/new/edit") }, label = { Text(stringResource(R.string.new_group)) }, leadingIcon = { Icon(Icons.Outlined.Groups, null) })
                }
            }
            item { SectionHeader(stringResource(R.string.lending), action = stringResource(R.string.add_person), onAction = { go("person/new/edit") }) }
            if (people.isEmpty()) item { EmptyState(stringResource(R.string.no_people), stringResource(R.string.no_people_body), icon = Icons.Outlined.Person, action = stringResource(R.string.record_lending), onAction = { go("lending/new/edit") }) }
            else item {
                PennyCard {
                    people.forEach { p ->
                        val bal = balances[p.state.id].orEmpty()
                        val main = bal[ctx.base] ?: bal.values.firstOrNull() ?: 0
                        val cur = if (bal.containsKey(ctx.base)) ctx.base else bal.keys.firstOrNull() ?: ctx.base
                        val (text, color) = balanceText(ctx, main, cur)
                        ListRow(p.value.name, leading = { Avatar(p.value.name, p.state.id) }, subtitle = if (bal.size > 1) stringResource(R.string.plus_n_currencies, bal.size - 1) else null, trailingText = text, trailingColor = color, onClick = { go("person/${p.state.id}") }, divider = true)
                    }
                }
            }
            val archivedPeople = b.people.filter { it.value.archived }
            if (archivedPeople.isNotEmpty()) {
                item { TextButton(onClick = { showArchived = !showArchived }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived_n, archivedPeople.size)) } }
                if (showArchived) item { PennyCard { archivedPeople.forEach { p -> ListRow(p.value.name, leading = { Avatar(p.value.name, p.state.id) }, subtitle = stringResource(R.string.archived), onClick = { go("person/${p.state.id}") }, divider = true) } } }
            }
            item { SectionHeader(stringResource(R.string.groups), action = stringResource(R.string.new_group), onAction = { go("group/new/edit") }) }
            val groups = b.groups.filter { !it.value.archived }
            if (groups.isEmpty()) item { EmptyState(stringResource(R.string.no_groups), stringResource(R.string.no_groups_body), icon = Icons.Outlined.Groups, action = stringResource(R.string.new_group), onAction = { go("group/new/edit") }) }
            else item {
                PennyCard {
                    groups.forEach { g ->
                        val mine = b.groupBalances(g.state.id)[Group.ME] ?: 0
                        val (text, color) = balanceText(ctx, mine, g.value.baseCurrency)
                        ListRow(g.value.name, leading = { Avatar(g.value.name, g.state.id, icon = Icons.Outlined.Groups) }, subtitle = stringResource(R.string.members_n, g.value.memberIds.size), trailingText = text, trailingColor = color, onClick = { go("group/${g.state.id}") }, divider = true)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.more_with_people)) }
            item {
                PennyCard {
                    ListRow(stringResource(R.string.remittances), leading = { Avatar("R", "remit", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Public) }, subtitle = stringResource(R.string.remittances_sub), onClick = { go(Routes.REMITTANCES) }, divider = true)
                    ListRow(
                        ctx.pack.circleNames.firstOrNull() ?: stringResource(R.string.circles),
                        leading = { Avatar("C", "circle", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Savings) },
                        subtitle = stringResource(R.string.circles_sub),
                        onClick = { go(b.circles.firstOrNull()?.let { "circle/${it.state.id}" } ?: "circle/new/edit") },
                    )
                    b.circles.drop(1).forEach { c -> ListRow(c.value.name, subtitle = c.value.localName, onClick = { go("circle/${c.state.id}") }) }
                    if (b.circles.isNotEmpty()) TextButton(onClick = { go("circle/new/edit") }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.new_circle)) }
                }
            }
        }
    }
}

/** PE-06 person page: lending history, balance, settle, reminder drafted for the user to send (PE-02). */
@Composable
fun PersonScreen(id: String, onBack: () -> Unit, onEdit: (String) -> Unit, onEntry: (String, String) -> Unit, onGroup: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val p = b.peopleMap[id]
        if (p == null) { PennyScreen(stringResource(R.string.tab_people), onBack) { pad -> EmptyState(stringResource(R.string.not_found), stringResource(R.string.not_found_body), Modifier.padding(pad)) }; return@WithBook }
        val context = LocalContext.current
        val entries = b.lending.filter { it.value.personId == id }
        val bal = Splitting.lendingBalance(entries.filter { !it.value.closed }.map { it.value }, b.today())
        val groups = b.groups.filter { id in it.value.memberIds }
        PennyScreen(p.value.name, onBack, actions = { IconButton(onClick = { onEdit(id) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) } }) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    app.penny.ui.kit.HeroCard {
                        Column(Modifier.padding(20.dp)) {
                            if (bal.isEmpty()) Text(stringResource(R.string.settled_up), style = MaterialTheme.typography.headlineSmall)
                            bal.forEach { (cur, v) -> val (t, _) = balanceText(ctx, v, cur); MoneyLabel(t, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
                item {
                    // Wraps onto a second line at large text or with the WhatsApp button, instead of squeezing.
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { onEntry("new", id) }) { Text(stringResource(R.string.add_entry)) }
                        bal.entries.firstOrNull { it.value != 0L }?.let { (cur, v) ->
                            OutlinedButton(onClick = {
                                // PE-04 settle the full balance: an opposite entry that closes the ledger.
                                vm.act(R.string.settled) { book ->
                                    val g = saveLending(book, null, LendingEntry(id, if (v > 0) LendDirection.RECEIVED else LendDirection.GAVE, kotlin.math.abs(v), cur, book.today(), settles = "all", note = context.getString(R.string.settlement), closed = true), postToAccount = false)
                                    // The settlement is closed along with what it settles; left open it read as a new debt the other way.
                                    entries.filter { !it.value.closed }.forEach { e -> lending.update(e.state.id, e.value.copy(closed = true), groupId = g) }
                                    g
                                }
                            }) { Text(stringResource(R.string.settle_all)) }
                            // PE-02: a reminder the user sends themselves; no automatic messages. The real amount goes in
                            // the message even while amounts are hidden on screen: the person chose to send it.
                            val reminder = context.getString(if (v > 0) R.string.reminder_receive else R.string.reminder_give, p.value.name, ctx.fmt.format(kotlin.math.abs(v), cur))
                            val wa = whatsAppLink(p.value.phone, ctx.book.profile.regionPack, reminder)
                            if (wa != null) FilledTonalButton(onClick = { context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(wa))) }) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.remind_whatsapp))
                            }
                            OutlinedButton(onClick = {
                                app.penny.ui.common.ShareCard.share(
                                    context, p.value.name, context.getString(if (v > 0) R.string.share_card_owes_you else R.string.share_card_you_owe),
                                    null, ctx.fmt.format(kotlin.math.abs(v), cur), emptyList(), reminder,
                                )
                            }) { Text(stringResource(R.string.draft_reminder)) }
                        }
                    }
                }
                if (groups.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.shared_groups)) }
                    items(groups, key = { "g" + it.state.id }) { g -> ListRow(g.value.name, leading = { Avatar(g.value.name, g.state.id, icon = Icons.Outlined.Groups) }, onClick = { onGroup(g.state.id) }, divider = true) }
                }
                item { SectionHeader(stringResource(R.string.history)) }
                if (entries.isEmpty()) item { EmptyState(stringResource(R.string.no_entries), stringResource(R.string.no_entries_body)) }
                items(entries, key = { it.state.id }) { e ->
                    val gave = e.value.direction == LendDirection.GAVE
                    ListRow(
                        stringResource(if (gave) R.string.you_gave else R.string.you_received),
                        subtitle = listOfNotNull(shortDate(e.value.date), e.value.note.ifBlank { null }, e.value.due?.let { stringResource(R.string.due_x, shortDate(it)) }, stringResource(R.string.settled).takeIf { e.value.closed }, (e.value.interestBp.takeIf { it > 0 }?.let { stringResource(R.string.interest_pct, it / 100.0) })).joinToString(" · "),
                        trailingText = ctx.money(e.value.amountMinor, e.value.currency), trailingColor = if (gave) Color.Unspecified else Penny.status.positive,
                        onClick = { onEntry(e.state.id, id) }, divider = true,
                    )
                }
            }
        }
    }
}

/**
 * A WhatsApp chat link (wa.me) for [phone] with [text] typed in, or null without a usable number. Local numbers are
 * put in international form using the book's region, the same way the phone's dialler would.
 */
fun whatsAppLink(phone: String, region: String, text: String): String? {
    if (phone.isBlank()) return null
    val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(phone, region.uppercase())
        ?: phone.filter { it.isDigit() || it == '+' }.takeIf { it.startsWith("+") }
        ?: return null
    return "https://wa.me/" + e164.removePrefix("+") + "?text=" + Uri.encode(text)
}

@Composable
fun PersonEditScreen(id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val existing = ctx.book.peopleMap[id]?.value
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var phone by rememberSaveable { mutableStateOf(existing?.phone.orEmpty()) }
        var email by rememberSaveable { mutableStateOf(existing?.email.orEmpty()) }
        var handle by rememberSaveable { mutableStateOf(existing?.payHandle.orEmpty()) }
        var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_name_required)
        val takenErr = stringResource(R.string.name_taken)
        val inUse = ctx.book.lending.any { it.value.personId == id } || ctx.book.groups.any { id in it.value.memberIds } || ctx.book.circles.any { c -> c.value.members.any { it.personId == id } }
        PennyScreen(stringResource(if (existing == null) R.string.add_person else R.string.edit_person), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                TextInput(phone, { phone = it }, stringResource(R.string.phone), keyboard = KeyboardType.Phone)
                val context = androidx.compose.ui.platform.LocalContext.current
                // Fill name and number from the phone's contacts; the picker grants this one contact only, so Penny
                // needs no contacts permission and reads nothing else.
                val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                    val uri = r.data?.data ?: return@rememberLauncherForActivityResult
                    runCatching {
                        context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                c.getString(0)?.let { n -> if (name.isBlank()) name = n }
                                c.getString(1)?.let { num -> phone = num }
                            }
                        }
                    }
                }
                TextButton(onClick = { runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Contacts, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pick_from_contacts))
                }
                TextInput(email, { email = it }, stringResource(R.string.email), keyboard = KeyboardType.Email)
                TextInput(handle, { handle = it }, stringResource(R.string.pay_handle), supporting = stringResource(R.string.pay_handle_hint, ctx.pack.settlementSchemes.joinToString().ifBlank { "PayPal" }))
                TextInput(note, { note = it }, stringResource(R.string.field_note), singleLine = false)
                Text(stringResource(R.string.person_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (name.isBlank()) { err = req; return@PrimaryButton }
                    if (ctx.book.people.any { it.state.id != id && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) { err = takenErr; return@PrimaryButton }
                    val person = (existing ?: Person(name)).copy(name = name.trim(), phone = phone.trim(), email = email.trim(), payHandle = handle.trim(), note = note.trim())
                    vm.act(R.string.saved, after = onBack) { savePerson(it, if (existing == null) null else id, person) }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) app.penny.ui.kit.ActionStrip {
                    app.penny.ui.kit.StripAction(onClick = { vm.act(R.string.updated) { savePerson(it, id, existing.copy(archived = !existing.archived)) } }) { Text(stringResource(if (existing.archived) R.string.unarchive else R.string.archive)) }
                    // Someone with money records or group memberships is archived instead, so no balance loses its owner.
                    if (!inUse) app.penny.ui.kit.StripAction(destructive = true, onClick = { vm.act(R.string.deleted, after = onBack) { people.delete(id) } }) { Text(stringResource(R.string.delete)) }
                }
                if (existing != null && inUse) Text(stringResource(R.string.person_in_use), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
    }
}

/** PE-01: gave/received, partial settlements, optional due date and simple interest; can post to the ledger. */
@Composable
fun LendingEditScreen(id: String, personArg: String?, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.lending.firstOrNull { it.state.id == id }?.value
        var personId by rememberSaveable { mutableStateOf(existing?.personId ?: personArg) }
        var newName by rememberSaveable { mutableStateOf("") }
        var direction by rememberSaveable { mutableStateOf(existing?.direction ?: LendDirection.GAVE) }
        var currency by rememberSaveable { mutableStateOf(existing?.currency ?: ctx.base) }
        var amount by rememberSaveable { mutableStateOf(existing?.let { ctx.fmt.editable(it.amountMinor, it.currency) }.orEmpty()) }
        var date by rememberSaveable { mutableStateOf((existing?.date ?: b.today()).toString()) }
        var due by rememberSaveable { mutableStateOf(existing?.due?.toString()) }
        var interest by rememberSaveable { mutableStateOf(existing?.interestBp?.takeIf { it > 0 }?.let { (it / 100.0).toString() }.orEmpty()) }
        var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
        var account by rememberSaveable { mutableStateOf(if (existing != null) b.activeAccountOrNull(existing.accountId) else b.liveDefaultAccountId) }
        var post by rememberSaveable { mutableStateOf(existing == null) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_person_amount)
        val endErr = stringResource(R.string.err_end_before_start)
        val pctErr = stringResource(R.string.err_percent)
        val acctErr = stringResource(R.string.err_account_required)
        var fieldErr by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
        PennyScreen(stringResource(if (existing == null) R.string.record_lending else R.string.edit_entry), onBack, large = false) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Segmented(listOf(Choice(LendDirection.GAVE, stringResource(R.string.i_gave)), Choice(LendDirection.RECEIVED, stringResource(R.string.i_received))), direction, { direction = it })
                SelectField(stringResource(R.string.person), personId, b.people.map { Choice(it.state.id, it.value.name) }, { personId = it }, allowNone = true, noneLabel = stringResource(R.string.new_person))
                if (personId == null) TextInput(newName, { newName = it; err = null }, stringResource(R.string.new_person_name), error = err)
                SelectField(stringResource(R.string.field_currency), currency, app.penny.ui.kit.currencyChoices(listOf(ctx.base)), { it?.let { c -> currency = c } })
                AmountInput(amount, { amount = it }, currency, ctx.fmt, stringResource(R.string.field_amount))
                DateInput(stringResource(R.string.field_date), runCatching { java.time.LocalDate.parse(date) }.getOrNull(), { date = it.toString() }, today = b.today())
                DateInput(stringResource(R.string.due_date_optional), due?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }, { due = it.toString(); fieldErr = fieldErr - "due" }, quick = false, today = b.today())
                fieldErr["due"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
                TextInput(interest, { interest = it; fieldErr = fieldErr - "interest" }, stringResource(R.string.interest_simple_pct), keyboard = KeyboardType.Decimal, error = fieldErr["interest"])
                TextInput(note, { note = it }, stringResource(R.string.field_note), singleLine = false)
                if (existing == null) {
                    SwitchRow(stringResource(R.string.post_to_account), post, { post = it }, subtitle = stringResource(R.string.post_to_account_hint))
                    if (post) SelectField(stringResource(R.string.field_account), account, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { account = it; fieldErr = fieldErr - "account" }, error = fieldErr["account"])
                }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    val a = parseAmount(amount, currency, ctx.fmt)
                    if (a == null || (personId == null && newName.isBlank())) { err = req; return@PrimaryButton }
                    val d = java.time.LocalDate.parse(date)
                    val dueDate = due?.let { java.time.LocalDate.parse(it) }
                    val bp = if (interest.isBlank()) 0 else app.penny.ui.kit.parsePercentBp(interest)
                    val errs = buildMap {
                        if (dueDate != null && dueDate.isBefore(d)) put("due", endErr)
                        if (bp == null) put("interest", pctErr)
                        if (existing == null && post && b.activeAccountOrNull(account) == null) put("account", acctErr)
                    }
                    fieldErr = errs
                    if (errs.isNotEmpty()) return@PrimaryButton
                    vm.act(R.string.saved, after = onBack) { book ->
                        val g = people.newGroup()
                        // A typed name that is already a person is that person, not a second one.
                        val pid = personId ?: ensurePerson(book, newName, g)
                        val e = (existing ?: LendingEntry(pid, direction, a, currency, java.time.LocalDate.parse(date))).copy(
                            personId = pid, direction = direction, amountMinor = a, currency = currency, date = java.time.LocalDate.parse(date),
                            due = dueDate, note = note.trim(), interestBp = bp ?: 0,
                            accountId = if (existing == null) account.takeIf { post } else existing.accountId,
                        )
                        // One undo group: Undo removes the entry, its posted transaction and any new person together.
                        saveLending(book, if (existing == null) null else id, e, post && existing == null, g)
                        g
                    }
                }, Modifier.padding(horizontal = 16.dp))
                if (existing != null) TextButton(onClick = { vm.act(R.string.deleted, after = onBack) { deleteLending(it, id) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

/** US-10 settle-up: opens the regional payment app via its deep link or shares a request text. */
fun settleIntent(context: android.content.Context, ctx: Ctx, person: Person?, amountMinor: Long, currency: String, note: String): Intent {
    val major = BigDecimal.valueOf(amountMinor, CurrencyUnit.of(currency).minorUnits).toPlainString()
    val link = ctx.pack.settlementSchemes.firstNotNullOfOrNull { s -> Splitting.settleLink(s, person?.payHandle.orEmpty(), person?.name.orEmpty(), major, currency, note) }
    return if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link))
    else Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, context.getString(R.string.settle_text, ctx.money(amountMinor, currency), note)), null)
}
