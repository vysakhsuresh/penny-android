package app.penny.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.event.Entities
import app.penny.core.event.Event
import app.penny.core.event.EventOrder
import app.penny.core.event.Op
import app.penny.core.event.Projector
import app.penny.core.event.Reasons
import app.penny.core.event.Version
import app.penny.core.model.TxType
import app.penny.ui.common.Ctx
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.kit.Choice
import app.penny.ui.kit.ChipFlow
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.LoadingState
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Human labels for entity types (Trash, History filters). */
fun entityLabel(context: Context, entity: String): String = context.getString(
    when (entity) {
        Entities.TRANSACTION -> R.string.obj_transaction
        Entities.ACCOUNT -> R.string.obj_account
        Entities.CATEGORY -> R.string.obj_category
        Entities.TAG -> R.string.obj_tag
        Entities.PAYEE -> R.string.obj_payee
        Entities.RULE -> R.string.obj_rule
        Entities.ENVELOPE -> R.string.obj_envelope
        Entities.ALLOCATION, Entities.BUDGET_MOVE -> R.string.obj_budget
        Entities.GOAL, Entities.GOAL_ENTRY -> R.string.obj_goal
        Entities.RECURRING, Entities.OCCURRENCE -> R.string.obj_recurring
        Entities.TEMPLATE -> R.string.obj_template
        Entities.SAVED_SEARCH -> R.string.obj_saved_search
        Entities.PERSON -> R.string.obj_person
        Entities.LENDING -> R.string.obj_lending
        Entities.GROUP -> R.string.obj_group
        Entities.GROUP_EXPENSE, Entities.SETTLEMENT -> R.string.obj_group_expense
        Entities.LOAN -> R.string.obj_loan
        Entities.REMITTANCE -> R.string.obj_remittance
        Entities.CIRCLE, Entities.CIRCLE_CONTRIBUTION -> R.string.obj_circle
        Entities.ATTACHMENT -> R.string.obj_attachment
        Entities.PROFILE -> R.string.obj_profile
        Entities.MEMBER -> R.string.obj_member
        Entities.WATCHLIST -> R.string.obj_watchlist
        else -> R.string.obj_other
    },
)

/** Renders values in history: money formatted, ids resolved to names, dates localised, booleans as words. */
class ValueFormatter(private val context: Context, private val ctx: Ctx, private val currency: String?) {
    fun show(field: String, e: JsonElement?): String {
        if (e == null || e.toString() == "null") return "—"
        val raw = (e as? JsonPrimitive)?.content ?: return summarize(e)
        val b = ctx.book
        return when {
            field.endsWith("Minor") -> raw.toLongOrNull()?.let { m -> ctx.money(m, currency ?: ctx.base) } ?: raw
            field in setOf("accountId", "toAccountId", "viaAccountId", "defaultAccountId", "paidFromAccountId") -> b.accountMap[raw]?.value?.name ?: context.getString(R.string.deleted_item)
            field in setOf("categoryId", "parentId", "defaultCategoryId") -> b.categoryName(raw).ifEmpty { context.getString(R.string.deleted_item) }
            field in setOf("personId", "payerId", "fromId", "toId") -> b.personName(raw).ifEmpty { raw.take(8) }
            field == "type" -> runCatching { context.getString(app.penny.ui.common.typeLabel(TxType.valueOf(raw))) }.getOrDefault(raw.lowercase())
            field == "status" -> when (raw) {
                "PENDING" -> context.getString(R.string.status_pending)
                "CLEARED" -> context.getString(R.string.status_cleared)
                "RECONCILED" -> context.getString(R.string.status_reconciled)
                else -> raw.lowercase().replaceFirstChar { it.uppercase() }
            }
            field == "direction" -> context.getString(if (raw == "IN") R.string.direction_in else R.string.direction_out)
            raw == "true" -> context.getString(R.string.yes)
            raw == "false" -> context.getString(R.string.no)
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(raw) -> runCatching { shortDate(java.time.LocalDate.parse(raw)) }.getOrDefault(raw)
            else -> raw
        }
    }

    private fun summarize(e: JsonElement): String {
        val s = e.toString()
        return when {
            s.startsWith("[") -> if (s == "[]") "—" else (e as? kotlinx.serialization.json.JsonArray)?.let { arr ->
                arr.joinToString(", ") { (it as? JsonPrimitive)?.content ?: context.getString(R.string.item_n) }.take(80)
            } ?: s.take(60)
            else -> context.getString(R.string.changed)
        }
    }

    fun label(field: String): String {
        val id = mapOf(
            "type" to R.string.field_type, "amountMinor" to R.string.field_amount, "currency" to R.string.field_currency,
            "accountId" to R.string.field_account, "toAccountId" to R.string.field_to_account, "toAmountMinor" to R.string.field_to_amount,
            "categoryId" to R.string.field_category, "payee" to R.string.field_payee, "date" to R.string.field_date, "time" to R.string.field_time,
            "note" to R.string.field_note, "tags" to R.string.field_tags, "status" to R.string.field_status, "direction" to R.string.field_direction,
            "isPrivate" to R.string.field_private, "excludeFromBudget" to R.string.field_exclude_budget, "excludeFromReports" to R.string.field_exclude_reports,
            "splits" to R.string.field_splits, "attachmentIds" to R.string.field_attachments, "name" to R.string.field_name,
            "limitMinor" to R.string.field_limit, "targetMinor" to R.string.field_target, "archived" to R.string.archived,
        )[field]
        return id?.let(context::getString) ?: field.replace(Regex("([a-z])([A-Z])"), "$1 $2").removeSuffix(" Minor").replaceFirstChar { it.uppercase() }
    }
}

private fun whenText(ts: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

private fun opTitle(context: Context, op: Op, reason: String?): String = when {
    reason == Reasons.UNDO -> context.getString(R.string.history_undo)
    reason != null && reason.startsWith(Reasons.REVERT_PREFIX) -> context.getString(R.string.history_reverted, reason.removePrefix(Reasons.REVERT_PREFIX).toIntOrNull() ?: 0)
    reason == "merge" -> context.getString(R.string.history_merged)
    else -> context.getString(
        when (op) {
            Op.CREATE -> R.string.history_created
            Op.UPDATE -> R.string.history_edited
            Op.DELETE -> R.string.history_deleted
            Op.RESTORE -> R.string.history_restored
            Op.PURGE -> R.string.deleted_forever
        },
    )
}

/** TX-05 / §10.2 History for any object: versions, who and when, field diffs, revert (a compensating event). */
@Composable
fun EntityHistoryScreen(entity: String, id: String, onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        var versions by remember { mutableStateOf<List<Version>?>(null) }
        var conflicts by remember { mutableStateOf<Set<String>>(emptySet()) }
        val refresh = ctx.book
        LaunchedEffect(refresh, id) {
            val r = vm.repo.repoFor(entity)
            versions = r.history(id).reversed()
            conflicts = EventOrder.conflicts(vm.repo.store.eventsFor(entity, id)).map { it.eventId }.toSet()
        }
        val currency = (ctx.book.txMap[id]?.value?.currency) ?: ctx.book.accountMap[id]?.value?.currency
        val f = ValueFormatter(context, ctx, currency)
        PennyScreen(stringResource(R.string.history_title), onBack) { pad ->
            val list = versions
            if (list == null) LoadingState(Modifier.padding(pad))
            else if (list.isEmpty()) EmptyState(stringResource(R.string.history_empty), stringResource(R.string.history_empty_body), Modifier.padding(pad), Icons.Outlined.History)
            else {
                val dataVersions = list.filter { it.op == Op.CREATE || it.op == Op.UPDATE }.map { it.version }.toSet()
                val latest = dataVersions.maxOrNull()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                    items(list, key = { it.eventId }) { v ->
                        PennyCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("${stringResource(R.string.history_version, v.version)} · ${opTitle(context, v.op, v.reason)}", style = MaterialTheme.typography.titleSmall)
                                val who = if (v.actor == vm.repo.localAccountId) stringResource(R.string.history_you) else v.actor.take(8)
                                Text(stringResource(R.string.history_by, who, whenText(v.ts)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (v.eventId in conflicts) Text(stringResource(R.string.history_conflict), style = MaterialTheme.typography.bodySmall, color = app.penny.ui.theme.Penny.status.near)
                                v.changes.filter { it.field !in setOf("tz", "payeeId", "sort") }.forEach { c ->
                                    val name = f.label(c.field)
                                    Text(
                                        if (c.before == null) stringResource(R.string.history_set, name, f.show(c.field, c.after))
                                        else stringResource(R.string.history_change, name, f.show(c.field, c.before), f.show(c.field, c.after)),
                                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                if (v.version in dataVersions && v.version != latest) {
                                    OutlinedButton(onClick = { vm.act(R.string.reverted) { repoFor(entity).revertTo(id, v.version) } }, modifier = Modifier.padding(top = 8.dp)) {
                                        Text(stringResource(R.string.history_revert))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** HS-01 global History: every change in the profile, filterable by object; revert where safe. */
@Composable
fun GlobalHistoryScreen(onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        var events by remember { mutableStateOf<List<Event>?>(null) }
        var filter by rememberSaveable { mutableStateOf<String?>(null) }
        var limit by rememberSaveable { mutableStateOf(200) }
        LaunchedEffect(ctx.book, limit) { events = vm.repo.store.eventPage(ctx.book.profileId, limit, 0) }
        PennyScreen(stringResource(R.string.global_history), onBack) { pad ->
            val list = events
            if (list == null) { LoadingState(Modifier.padding(pad)); return@PennyScreen }
            val types = list.map { it.entity }.distinct().filter { it != Entities.ALERT }
            val shown = list.filter { (filter == null || it.entity == filter) && it.entity != Entities.ALERT }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item {
                    ChipFlow(types.map { Choice(it, entityLabel(context, it)) }, setOfNotNull(filter), { t -> filter = if (filter == t) null else t })
                }
                if (shown.isEmpty()) item { EmptyState(stringResource(R.string.history_empty), stringResource(R.string.history_empty_body), icon = Icons.Outlined.History) }
                items(shown, key = { it.id }) { e ->
                    val name = nameOf(ctx, e, events.orEmpty())
                    val who = if (e.actor == vm.repo.localAccountId) stringResource(R.string.history_you) else e.actor.take(8)
                    ListRow(
                        title = "${opTitle(context, e.op, e.reason)} · ${entityLabel(context, e.entity)}",
                        subtitle = listOfNotNull(name, stringResource(R.string.history_by, who, whenText(e.ts))).joinToString(" · "),
                        onClick = { onOpen(e.entity, e.entityId) }, divider = true,
                    )
                }
                if (list.size >= limit) item { TextButton(onClick = { limit += 200 }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.load_more)) } }
            }
        }
    }
}

private fun nameOf(ctx: Ctx, e: Event, all: List<Event> = emptyList()): String? {
    val b = ctx.book
    fun Event.s(k: String) = (patch[k] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
    fun Event.own() = s("name") ?: s("payee") ?: s("description")
    return e.own() ?: b.txMap[e.entityId]?.value?.payee ?: b.accountMap[e.entityId]?.value?.name
        ?: b.categoryMap[e.entityId]?.value?.name
        // Deleted objects are gone from the book: take the name from another event of the same object.
        ?: all.asSequence().filter { it.entityId == e.entityId }.firstNotNullOfOrNull { it.own() }
}

/** HS-02 Trash for every object: restore or delete forever (with undo window), auto-purge at 30 days. */
@Composable
fun TrashScreen(onBack: () -> Unit) {
    WithBook { ctx, vm ->
        val context = LocalContext.current
        val items = ctx.book.deleted.filter { it.entity in Entities.TRASHABLE }
        var pendingPurge by rememberSaveable { mutableStateOf(setOf<String>()) }
        PennyScreen(stringResource(R.string.trash_title), onBack) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { Text(stringResource(R.string.trash_body_all), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                val visible = items.filter { "${it.entity}:${it.id}" !in pendingPurge }
                if (visible.isEmpty()) item { EmptyState(stringResource(R.string.trash_empty), stringResource(R.string.trash_empty_body), icon = Icons.Outlined.RestoreFromTrash) }
                else item { Text(pluralStringResource(R.plurals.trash_count, visible.size, visible.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 20.dp)) }
                items(visible, key = { "${it.entity}:${it.id}" }) { st ->
                    val title = runCatching {
                        val o = st.data
                        listOf("payee", "name", "description", "fileName").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                            // A transaction without a payee is known by its category and date.
                            ?: (o["categoryId"] as? JsonPrimitive)?.content?.let { c -> ctx.book.categoryName(c).takeIf(String::isNotBlank) }
                                ?.let { n -> (o["date"] as? JsonPrimitive)?.content?.let { d -> runCatching { "$n · " + app.penny.ui.common.shortDate(java.time.LocalDate.parse(d)) }.getOrNull() } ?: n }
                    }.getOrNull() ?: entityLabel(context, st.entity)
                    val amount = (st.data["amountMinor"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { m ->
                        ctx.money(m, (st.data["currency"] as? JsonPrimitive)?.content ?: ctx.base)
                    }
                    val days = 30 - ((System.currentTimeMillis() - (st.deletedAt ?: 0)) / 86_400_000L).toInt()
                    PennyCard {
                        ListRow(
                            title = title,
                            subtitle = "${entityLabel(context, st.entity)} · ${pluralStringResource(R.plurals.purge_in_days, days.coerceAtLeast(0), days.coerceAtLeast(0))}",
                            trailingText = amount,
                        )
                        Row(Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                            TextButton(onClick = { vm.act(R.string.restored) { repoFor(st.entity).restore(st.id) } }) { Text(stringResource(R.string.restore)) }
                            TextButton(colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                                val key = "${st.entity}:${st.id}"
                                pendingPurge = pendingPurge + key
                                vm.undo.offer(
                                    app.penny.ui.common.UndoMessage(
                                        context.getString(R.string.deleted_forever), undo = { pendingPurge = pendingPurge - key },
                                        onExpire = { vm.repo.repoFor(st.entity).purge(st.id) },
                                    ),
                                )
                            }) { Text(stringResource(R.string.delete_forever)) }
                        }
                    }
                }
            }
        }
    }
}

/** Projector is used to show the state at a version for revert previews. */
@Suppress("unused")
private val keepProjector = Projector
