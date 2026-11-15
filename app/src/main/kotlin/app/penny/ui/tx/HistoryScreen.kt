package app.penny.ui.tx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.event.Op
import app.penny.core.event.Reasons
import app.penny.core.event.Version
import app.penny.domain.PennyRepository
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class VersionUi(val version: Int, val title: String, val meta: String, val lines: List<String>, val canRevert: Boolean)

/** TX-05: every version with actor, time and diff; revert writes a new compensating version. */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val undo: UndoController,
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val id: String = handle[TransactionFormViewModel.ARG_ID]!!

    val versions: StateFlow<List<VersionUi>?> = repo.observeTransactions(repo.currentProfileId.value.orEmpty())
        .map { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Lookups so history shows names and formatted amounts, not ids and minor units. */
    private var accountNames: Map<String, String> = emptyMap()
    private var categoryNames: Map<String, String> = emptyMap()
    private var fmt: app.penny.ui.common.MoneyFmt? = null
    private var currency: String = ""

    private suspend fun load(): List<VersionUi> {
        val pid = repo.currentProfileId.value.orEmpty()
        repo.profiles.get(pid)?.let { fmt = app.penny.ui.common.MoneyFmt.of(it.value, repo.packs.get(it.value.regionPack)) }
        accountNames = repo.accounts.list(pid, includeDeleted = true).associate { it.state.id to it.value.name }
        val cats = repo.categories.list(pid, includeDeleted = true).associateBy { it.state.id }
        categoryNames = cats.mapValues { (_, c) ->
            val parent = c.value.parentId?.let(cats::get)?.value?.name
            if (parent != null) "$parent › ${c.value.name}" else c.value.name
        }
        currency = repo.transactions.get(id)?.value?.currency.orEmpty()
        val h = repo.transactions.history(id)
        val dataVersions = h.filter { it.op == Op.CREATE || it.op == Op.UPDATE }.map { it.version }.toSet()
        val latestData = dataVersions.maxOrNull()
        return h.map { v -> v.toUi(canRevert = v.version in dataVersions && v.version != latestData) }.reversed()
    }

    private fun Version.toUi(canRevert: Boolean): VersionUi {
        val r = reason
        val title = when {
            r == Reasons.UNDO -> context.getString(R.string.history_undo)
            r != null && r.startsWith(Reasons.REVERT_PREFIX) ->
                context.getString(R.string.history_reverted, r.removePrefix(Reasons.REVERT_PREFIX).toIntOrNull() ?: 0)
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
        val who = if (actor == repo.localAccountId) context.getString(R.string.history_you) else actor.take(8)
        val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))
        val lines = changes.filter { it.field != "tz" }.map { c ->
            val name = fieldLabel(c.field)
            if (c.before == null) context.getString(R.string.history_set, name, show(c.field, c.after))
            else context.getString(R.string.history_change, name, show(c.field, c.before), show(c.field, c.after))
        }
        return VersionUi(version, "${context.getString(R.string.history_version, version)} · $title",
            context.getString(R.string.history_by, who, whenText), lines, canRevert)
    }

    private fun show(field: String, e: JsonElement?): String {
        if (e == null || e.toString() == "null") return "—"
        val raw = (e as? JsonPrimitive)?.content ?: return e.toString()
        return when (field) {
            "amountMinor", "toAmountMinor" -> raw.toLongOrNull()?.let { m -> fmt?.format(m, currency) } ?: raw
            "accountId", "toAccountId" -> accountNames[raw] ?: raw
            "categoryId" -> categoryNames[raw] ?: raw
            "type" -> runCatching { context.getString(app.penny.ui.common.typeLabel(app.penny.core.model.TxType.valueOf(raw))) }.getOrDefault(raw)
            "status" -> when (raw) {
                "PENDING" -> context.getString(R.string.status_pending)
                "CLEARED" -> context.getString(R.string.status_cleared)
                "RECONCILED" -> context.getString(R.string.status_reconciled)
                else -> raw
            }
            "direction" -> context.getString(if (raw == "IN") R.string.direction_in else R.string.direction_out)
            "date" -> runCatching { app.penny.ui.common.shortDate(java.time.LocalDate.parse(raw)) }.getOrDefault(raw)
            "isPrivate", "excludeFromBudget", "excludeFromReports" -> if (raw == "true") "✓" else "—"
            else -> raw
        }
    }

    private fun fieldLabel(field: String): String = context.getString(
        when (field) {
            "type" -> R.string.field_type
            "amountMinor" -> R.string.field_amount
            "currency" -> R.string.field_currency
            "accountId" -> R.string.field_account
            "toAccountId" -> R.string.field_to_account
            "toAmountMinor" -> R.string.field_to_amount
            "categoryId" -> R.string.field_category
            "payee" -> R.string.field_payee
            "date" -> R.string.field_date
            "time" -> R.string.field_time
            "note" -> R.string.field_note
            "tags" -> R.string.field_tags
            "status" -> R.string.field_status
            "direction" -> R.string.field_direction
            "isPrivate" -> R.string.field_private
            "excludeFromBudget" -> R.string.field_exclude_budget
            "excludeFromReports" -> R.string.field_exclude_reports
            "splits" -> R.string.field_splits
            "attachmentIds" -> R.string.field_attachments
            else -> R.string.none
        },
    ).let { if (it == context.getString(R.string.none)) field else it }

    fun revert(version: Int) = viewModelScope.launch {
        val g = repo.transactions.revertTo(id, version) ?: return@launch
        undo.offer(UndoMessage(context.getString(R.string.reverted), undo = { repo.transactions.undo(g) }))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit) {
    val versions by vm.versions.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val list = versions
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text(stringResource(R.string.history_empty)) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.version }) { v ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(v.title, style = MaterialTheme.typography.titleSmall)
                            Text(v.meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            v.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (v.canRevert) TextButton(onClick = { vm.revert(v.version) }) { Text(stringResource(R.string.history_revert)) }
                        }
                    }
                }
            }
        }
    }
}
