package app.penny.ui.tx

import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.penny.core.event.PennyJson
import app.penny.core.model.SavedSearch
import app.penny.core.model.SortBy
import app.penny.core.model.TxFilter
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.search.NaturalQueryParser
import app.penny.core.search.SearchIndex
import app.penny.core.search.TxSearch
import app.penny.core.money.CurrencyUnit
import app.penny.domain.ops.allTagNames
import app.penny.domain.ops.bulkDelete
import app.penny.domain.ops.bulkEdit
import app.penny.domain.ops.mergeTransactions
import app.penny.ui.common.Ctx
import app.penny.ui.common.SwipeableTx
import app.penny.ui.common.TxRow
import app.penny.ui.common.WithBook
import app.penny.ui.common.shortDate
import app.penny.ui.common.typeLabel
import app.penny.ui.kit.Choice
import app.penny.ui.kit.ChipFlow
import app.penny.ui.kit.DateInput
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.MoneyLabel
import app.penny.ui.kit.OptionSheet
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.kit.parseAmount

/**
 * SR-01…SR-06 + TX-04: transactions with instant offline search (plain words or natural language), filter chips,
 * sort, saved searches, and multi-select bulk actions (long-press).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(initialFilter: String?, onBack: () -> Unit, onOpen: (String) -> Unit, focusSearch: Boolean = false) {
    val searchFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    WithBook { ctx, vm ->
        val b = ctx.book
        val context = LocalContext.current
        var query by rememberSaveable { mutableStateOf("") }
        var filterJson by rememberSaveable { mutableStateOf(initialFilter ?: PennyJson.encodeToString(TxFilter.serializer(), TxFilter())) }
        val filter = runCatching { PennyJson.decodeFromString(TxFilter.serializer(), filterJson) }.getOrDefault(TxFilter())
        fun setFilter(f: TxFilter) { filterJson = PennyJson.encodeToString(TxFilter.serializer(), f) }
        var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
        var showFilters by rememberSaveable { mutableStateOf(false) }
        var sortMenu by remember { mutableStateOf(false) }
        var bulkSheet by rememberSaveable { mutableStateOf<String?>(null) }
        var saveDialog by rememberSaveable { mutableStateOf(false) }

        val decimals = runCatching { CurrencyUnit.of(ctx.base).minorUnits }.getOrDefault(2)
        val parser = remember(b) {
            NaturalQueryParser(
                b.today(), b.profile.cycle, decimals,
                categoryByName = b.categories.associate { it.value.name.lowercase() to it.state.id },
                accountByName = b.accounts.associate { it.value.name.lowercase() to it.state.id },
            )
        }
        val parsed = if (query.isBlank()) null else parser.parse(query)
        val effective = if (parsed == null) filter else filter.copy(
            text = parsed.filter.text, from = parsed.filter.from ?: filter.from, to = parsed.filter.to ?: filter.to,
            minMinor = parsed.filter.minMinor ?: filter.minMinor, maxMinor = parsed.filter.maxMinor ?: filter.maxMinor,
            types = parsed.filter.types.ifEmpty { filter.types }, tags = (filter.tags + parsed.filter.tags).distinct(),
            categoryIds = (filter.categoryIds + parsed.filter.categoryIds).distinct(), accountIds = (filter.accountIds + parsed.filter.accountIds).distinct(),
            uncategorised = filter.uncategorised || parsed.filter.uncategorised, statuses = parsed.filter.statuses.ifEmpty { filter.statuses },
        )
        val attachIds = b.attachments.map { it.value.transactionId }.toSet()
        val ocr = b.attachments.groupBy { it.value.transactionId }.mapValues { (_, l) -> l.joinToString(" ") { it.value.ocrText } }
        val idx = SearchIndex(categoryName = b::categoryName, accountName = b::accountName, attachmentText = { ocr[it].orEmpty() }, hasAttachment = { it in attachIds })
        val hits = remember(b, effective) { TxSearch.filter(b.rows, effective, idx) }
        val selecting = selected.isNotEmpty()

        Scaffold(
            topBar = {
                if (selecting) {
                    TopAppBar(
                        title = { Text(pluralStringResource(R.plurals.selected_n, selected.size, selected.size)) },
                        navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_selection)) } },
                        actions = { TextButton(onClick = { selected = hits.map { it.id }.toSet() }) { Text(stringResource(R.string.select_all)) } },
                    )
                } else {
                    TopAppBar(
                        title = {
                            OutlinedTextField(
                                query, { query = it }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear)) } },
                                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(searchFocus), shape = MaterialTheme.shapes.extraLarge,
                            )
                            // Opened from Home's search button: put the cursor in the field and show the keyboard.
                            androidx.compose.runtime.LaunchedEffect(Unit) { if (focusSearch) runCatching { searchFocus.requestFocus() } }
                        },
                        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    )
                }
            },
            bottomBar = {
                if (selecting) BottomAppBar {
                    IconButton(onClick = { bulkSheet = "category" }) { Icon(Icons.Outlined.Category, stringResource(R.string.change_category)) }
                    IconButton(onClick = { bulkSheet = "account" }) { Icon(Icons.Outlined.Wallet, stringResource(R.string.change_account)) }
                    IconButton(onClick = { bulkSheet = "tag" }) { Icon(Icons.Outlined.Sell, stringResource(R.string.add_tag)) }
                    IconButton(onClick = { bulkSheet = "date" }) { Icon(Icons.Outlined.Tune, stringResource(R.string.change_date)) }
                    if (selected.size >= 2) IconButton(onClick = {
                        val ordered = hits.filter { it.id in selected }.map { it.id }
                        vm.act(R.string.merged) { mergeTransactions(it, ordered.first(), ordered.drop(1)) }
                        selected = emptySet()
                    }) { Icon(Icons.Outlined.MergeType, stringResource(R.string.merge_duplicates)) }
                    IconButton(onClick = {
                        val ids = selected
                        vm.act(messageText = context.resources.getQuantityString(R.plurals.deleted_n, ids.size, ids.size)) { bulkDelete(ids) }
                        selected = emptySet()
                    }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                }
            },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 48.dp)) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(showFilters, { showFilters = !showFilters }, label = { Text(stringResource(R.string.filters)) }, leadingIcon = { Icon(Icons.Outlined.Tune, null) }) }
                        item {
                            FilterChip(false, { sortMenu = true }, label = { Text(stringResource(R.string.sort)) }, leadingIcon = { Icon(Icons.Outlined.SwapVert, null) })
                            DropdownMenu(sortMenu, { sortMenu = false }) {
                                SortBy.entries.forEach { s ->
                                    DropdownMenuItem({ Text(stringResource(sortLabel(s)) + if (filter.sort == s) (if (filter.ascending) " ↑" else " ↓") else "") }, onClick = {
                                        setFilter(filter.copy(sort = s, ascending = if (filter.sort == s) !filter.ascending else false)); sortMenu = false
                                    })
                                }
                            }
                        }
                        item { FilterChip(filter.uncategorised, { setFilter(filter.copy(uncategorised = !filter.uncategorised)) }, label = { Text(stringResource(R.string.uncategorised)) }) }
                        item { FilterChip(TxStatus.PENDING in filter.statuses, { setFilter(filter.copy(statuses = if (TxStatus.PENDING in filter.statuses) emptyList() else listOf(TxStatus.PENDING))) }, label = { Text(stringResource(R.string.status_pending)) }) }
                        item { FilterChip(false, { saveDialog = true }, label = { Text(stringResource(R.string.save_search)) }, leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) }) }
                        b.savedSearches.filter { it.value.pinned }.forEach { s ->
                            item(key = s.state.id) { FilterChip(filter == s.value.filter, { setFilter(s.value.filter); query = "" }, label = { Text(s.value.name) }) }
                        }
                    }
                }
                if (parsed != null && parsed.understood.isNotEmpty()) item {
                    Text(stringResource(R.string.understood, parsed.understood.joinToString(" · ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
                if (showFilters) item { FilterPanel(ctx, filter, ::setFilter) }
                item {
                    // In the book's currency: rows in other currencies are converted (rows without a rate are left out).
                    val spend = hits.sumOf { r ->
                        val sign = when (r.tx.type) { TxType.EXPENSE -> 1L; TxType.REFUND -> -1L; else -> 0L }
                        if (sign == 0L) 0L else sign * (ctx.book.fx.convert(r.tx.amountMinor, r.tx.currency, ctx.base, r.tx.date) ?: 0L)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(pluralStringResource(R.plurals.results_n, hits.size, hits.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        MoneyLabel(stringResource(R.string.spent_x, ctx.money(spend)), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (hits.isEmpty()) item {
                    if (b.transactions.isEmpty()) EmptyState(stringResource(R.string.no_tx_title), stringResource(R.string.no_tx_body))
                    else EmptyState(stringResource(R.string.no_results), stringResource(R.string.no_results_body), icon = Icons.Outlined.SearchOff, action = stringResource(R.string.clear_filters), onAction = { setFilter(TxFilter()); query = "" })
                }
                items(hits.take(1_000), key = { it.id }) { r ->
                    val sel = r.id in selected
                    SwipeableTx(r.id, onDelete = { vm.act(R.string.deleted_tx) { transactions.delete(r.id) } }, onCategory = null) {
                        TxRow(
                            r.id, r.tx, ctx, showDate = true, selected = sel,
                            onOpen = { if (selecting) selected = if (sel) selected - r.id else selected + r.id else onOpen(r.id) },
                            onLongClick = { selected = if (sel) selected - r.id else selected + r.id },
                        )
                    }
                }
            }
        }

        when (bulkSheet) {
            "category" -> OptionSheet(stringResource(R.string.change_category), null as String?, b.categories.filter { !it.value.archived }.map { Choice(it.state.id, b.categoryName(it.state.id)) }, true, stringResource(R.string.uncategorised), true, { bulkSheet = null }) { c ->
                val ids = selected; vm.act(R.string.updated) { bulkEdit(it, ids) { t -> t.copy(categoryId = c, splits = emptyList()) } }; bulkSheet = null; selected = emptySet()
            }
            "account" -> OptionSheet(stringResource(R.string.change_account), null as String?, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, false, "", false, { bulkSheet = null }) { a ->
                if (a != null) { val ids = selected; vm.act(R.string.updated) { bulkEdit(it, ids) { t -> t.copy(accountId = a) } } }
                bulkSheet = null; selected = emptySet()
            }
            "tag" -> OptionSheet(stringResource(R.string.add_tag), null as String?, b.allTagNames().map { Choice(it, it) }, false, "", true, { bulkSheet = null }) { tag ->
                if (tag != null) { val ids = selected; vm.act(R.string.updated) { bulkEdit(it, ids) { t -> t.copy(tags = (t.tags + tag).distinct()) } } }
                bulkSheet = null; selected = emptySet()
            }
            "date" -> ModalBottomSheet(onDismissRequest = { bulkSheet = null }) {
                DateInput(stringResource(R.string.change_date), b.today(), { d ->
                    val ids = selected; vm.act(R.string.updated) { bulkEdit(it, ids) { t -> t.copy(date = d) } }; bulkSheet = null; selected = emptySet()
                }, today = b.today())
                androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
            }
        }
        if (saveDialog) {
            var name by rememberSaveable { mutableStateOf(query) }
            AlertDialog(
                onDismissRequest = { saveDialog = false },
                title = { Text(stringResource(R.string.save_search)) },
                text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        vm.act(R.string.saved) { savedSearches.create(newId(), it.profileId, SavedSearch(name.trim(), effective, sort = it.savedSearches.size)) }
                        saveDialog = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { saveDialog = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

fun sortLabel(s: SortBy) = when (s) {
    SortBy.DATE -> R.string.sort_date
    SortBy.AMOUNT -> R.string.sort_amount
    SortBy.PAYEE -> R.string.sort_payee
    SortBy.CATEGORY -> R.string.sort_category
    SortBy.CREATED -> R.string.sort_created
}

/** SR-02 filter chips: dates, types, accounts, categories, tags, amount range, flags. */
@Composable
private fun FilterPanel(ctx: Ctx, f: TxFilter, set: (TxFilter) -> Unit) {
    val b = ctx.book
    Column {
        SectionHeader(stringResource(R.string.dates))
        val today = b.today()
        ChipFlow(
            listOf(
                Choice("month", stringResource(R.string.this_month)), Choice("last", stringResource(R.string.last_month)),
                Choice("90", stringResource(R.string.last_90_days)), Choice("year", stringResource(R.string.this_year)),
            ),
            emptySet(),
            { k ->
                when (k) {
                    "month" -> set(f.copy(from = today.withDayOfMonth(1), to = today))
                    "last" -> { val s = today.withDayOfMonth(1).minusMonths(1); set(f.copy(from = s, to = s.plusMonths(1).minusDays(1))) }
                    "90" -> set(f.copy(from = today.minusDays(90), to = today))
                    "year" -> set(f.copy(from = today.withDayOfYear(1), to = today))
                }
            },
        )
        Row {
            DateInput(stringResource(R.string.from), f.from, { set(f.copy(from = it)) }, Modifier.weight(1f), quick = false, today = today)
            DateInput(stringResource(R.string.to), f.to, { set(f.copy(to = it)) }, Modifier.weight(1f), quick = false, today = today)
        }
        if (f.from != null || f.to != null) TextButton(onClick = { set(f.copy(from = null, to = null)) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.clear_dates) + " (" + listOfNotNull(f.from?.let(::shortDate), f.to?.let(::shortDate)).joinToString(" – ") + ")")
        }
        SectionHeader(stringResource(R.string.field_type))
        ChipFlow(TxType.entries.map { Choice(it, stringResource(typeLabel(it))) }, f.types.toSet(), { t -> set(f.copy(types = if (t in f.types) f.types - t else f.types + t)) })
        SectionHeader(stringResource(R.string.accounts))
        ChipFlow(b.accounts.map { Choice(it.state.id, it.value.name) }, f.accountIds.toSet(), { a -> set(f.copy(accountIds = if (a in f.accountIds) f.accountIds - a else f.accountIds + a)) })
        SectionHeader(stringResource(R.string.categories))
        ChipFlow(b.categories.filter { it.value.parentId == null && !it.value.archived }.map { Choice(it.state.id, it.value.name) }, f.categoryIds.toSet(), { c ->
            val withChildren = listOf(c) + b.categories.filter { it.value.parentId == c }.map { it.state.id }
            set(f.copy(categoryIds = if (c in f.categoryIds) f.categoryIds - withChildren.toSet() else (f.categoryIds + withChildren).distinct()))
        })
        val tags = b.allTagNames()
        if (tags.isNotEmpty()) {
            SectionHeader(stringResource(R.string.field_tags))
            ChipFlow(tags.map { Choice(it, it) }, f.tags.toSet(), { t -> set(f.copy(tags = if (t in f.tags) f.tags - t else f.tags + t)) })
        }
        SectionHeader(stringResource(R.string.amount_range))
        Row {
            var min by rememberSaveable { mutableStateOf(f.minMinor?.let { ctx.fmt.editable(it, ctx.base) }.orEmpty()) }
            var max by rememberSaveable { mutableStateOf(f.maxMinor?.let { ctx.fmt.editable(it, ctx.base) }.orEmpty()) }
            TextInput(min, { min = it; set(f.copy(minMinor = parseAmount(it, ctx.base, ctx.fmt))) }, stringResource(R.string.min), Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            TextInput(max, { max = it; set(f.copy(maxMinor = parseAmount(it, ctx.base, ctx.fmt))) }, stringResource(R.string.max), Modifier.weight(1f), keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
        }
        SwitchRow(stringResource(R.string.has_attachment), f.hasAttachment == true, { set(f.copy(hasAttachment = if (it) true else null)) })
        SwitchRow(stringResource(R.string.field_private), f.isPrivate == true, { set(f.copy(isPrivate = if (it) true else null)) })
        TextButton(onClick = { set(TxFilter(sort = f.sort, ascending = f.ascending)) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.clear_filters)) }
    }
}
