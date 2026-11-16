package app.penny.ui.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.TxType
import app.penny.domain.Book
import app.penny.domain.ops.deleteCategory
import app.penny.domain.ops.depth
import app.penny.domain.ops.mergeCategory
import app.penny.domain.ops.reorderCategories
import app.penny.domain.ops.saveCategory
import app.penny.domain.ops.saveTransaction
import app.penny.ui.common.WithBook
import app.penny.ui.common.categoryIcon
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.Choice
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.PrimaryButton
import app.penny.ui.kit.Segmented
import app.penny.ui.kit.SelectField
import app.penny.ui.kit.SwitchRow
import app.penny.ui.kit.TextInput
import app.penny.ui.tx.TagsEditor

/** Categories in tree order: parent, then its children (and grandchildren), each with its depth. */
fun Book.categoryTree(kind: CategoryKind, includeArchived: Boolean): List<Pair<app.penny.core.event.Stored<Category>, Int>> {
    val all = categories.filter { it.value.kind == kind && (includeArchived || !it.value.archived) }
    val byParent = all.groupBy { it.value.parentId }
    val out = mutableListOf<Pair<app.penny.core.event.Stored<Category>, Int>>()
    fun walk(parent: String?, d: Int) {
        byParent[parent].orEmpty().sortedBy { it.value.sort }.forEach { c -> out += c to d; if (d < 3) walk(c.state.id, d + 1) }
    }
    walk(null, 1)
    // Orphans (parent deleted or archived) still show at the top level.
    all.filter { s -> out.none { it.first.state.id == s.state.id } }.forEach { out += it to 1 }
    return out
}

@Composable
fun CategoriesScreen(onBack: () -> Unit, onEdit: (String, String?) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        var kind by rememberSaveable { mutableStateOf(CategoryKind.EXPENSE) }
        var showArchived by rememberSaveable { mutableStateOf(false) }
        var menuFor by remember { mutableStateOf<String?>(null) }
        var mergeFrom by rememberSaveable { mutableStateOf<String?>(null) }
        var deleteFor by rememberSaveable { mutableStateOf<String?>(null) }
        var overflow by remember { mutableStateOf(false) }
        val tree = b.categoryTree(kind, showArchived)
        val counts = b.txList.groupingBy { it.categoryId }.eachCount()
        PennyScreen(
            stringResource(R.string.categories), onBack,
            actions = {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(overflow, { overflow = false }) {
                    DropdownMenuItem({ Text(stringResource(if (showArchived) R.string.hide_archived else R.string.show_archived)) }, onClick = { showArchived = !showArchived; overflow = false })
                    DropdownMenuItem({ Text(stringResource(R.string.reset_defaults)) }, onClick = { vm.act(R.string.defaults_restored) { seedCategories(it.profileId) }; overflow = false })
                }
            },
            floating = { app.penny.ui.kit.AddFab(stringResource(R.string.add_category), { onEdit("new", null) }) },
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 120.dp)) {
                item { Segmented(listOf(Choice(CategoryKind.EXPENSE, stringResource(R.string.type_expense)), Choice(CategoryKind.INCOME, stringResource(R.string.type_income))), kind, { kind = it }) }
                if (tree.isEmpty()) item { EmptyState(stringResource(R.string.no_categories), stringResource(R.string.no_categories_body), action = stringResource(R.string.reset_defaults), onAction = { vm.act(R.string.defaults_restored) { seedCategories(it.profileId) } }) }
                items(tree, key = { it.first.state.id }) { (s, d) ->
                    val c = s.value
                    val n = counts[s.state.id] ?: 0
                    Column {
                        ListRow(
                            title = c.name, modifier = Modifier.padding(start = ((d - 1) * 24).dp),
                            subtitle = listOfNotNull(
                                pluralStringResource(R.plurals.tx_count, n, n),
                                stringResource(R.string.excluded_from_spend).takeIf { c.excludedFromSpend },
                                stringResource(R.string.archived).takeIf { c.archived },
                            ).joinToString(" · "),
                            leading = { Avatar(c.name, s.state.id, icon = categoryIcon(c.key), size = 36.dp) },
                            trailing = {
                                IconButton(onClick = { menuFor = s.state.id }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.actions_for, c.name)) }
                                DropdownMenu(menuFor == s.state.id, { menuFor = null }) {
                                    if (d < 3) DropdownMenuItem({ Text(stringResource(R.string.add_subcategory)) }, onClick = { menuFor = null; onEdit("new", s.state.id) })
                                    DropdownMenuItem({ Text(stringResource(R.string.move_up)) }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) }, onClick = { menuFor = null; reorder(vm, b, tree.map { it.first }, s.state.id, -1) })
                                    DropdownMenuItem({ Text(stringResource(R.string.move_down)) }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) }, onClick = { menuFor = null; reorder(vm, b, tree.map { it.first }, s.state.id, 1) })
                                    DropdownMenuItem({ Text(stringResource(R.string.merge_into)) }, onClick = { menuFor = null; mergeFrom = s.state.id })
                                    DropdownMenuItem({ Text(stringResource(if (c.archived) R.string.unarchive else R.string.archive)) }, onClick = {
                                        menuFor = null; vm.act(if (c.archived) R.string.unarchived else R.string.archived_done) { saveCategory(it, s.state.id, c.copy(archived = !c.archived)) }
                                    })
                                    DropdownMenuItem({ Text(stringResource(R.string.delete)) }, onClick = {
                                        menuFor = null
                                        if (n == 0 && b.categories.none { it.value.parentId == s.state.id }) vm.act(R.string.category_deleted) { deleteCategory(it, s.state.id, null) } else deleteFor = s.state.id
                                    })
                                }
                            },
                            onClick = { onEdit(s.state.id, null) },
                        )
                    }
                }
            }
        }
        mergeFrom?.let { from ->
            ReassignDialog(b, from, stringResource(R.string.merge_into), stringResource(R.string.merge_body, b.categoryName(from)), onDismiss = { mergeFrom = null }) { to ->
                vm.act(R.string.merged) { mergeCategory(it, from, to!!) }; mergeFrom = null
            }
        }
        deleteFor?.let { del ->
            // CAT-06: deleting a category with transactions requires reassignment.
            ReassignDialog(b, del, stringResource(R.string.delete_category), stringResource(R.string.reassign_body, b.categoryName(del)), allowNone = true, onDismiss = { deleteFor = null }) { to ->
                vm.act(R.string.category_deleted) { deleteCategory(it, del, to) }; deleteFor = null
            }
        }
    }
}

private fun reorder(vm: app.penny.ui.common.BookViewModel, b: Book, order: List<app.penny.core.event.Stored<Category>>, id: String, delta: Int) {
    val me = order.firstOrNull { it.state.id == id } ?: return
    val siblings = order.filter { it.value.parentId == me.value.parentId }.map { it.state.id }.toMutableList()
    val i = siblings.indexOf(id); val j = i + delta
    if (j !in siblings.indices) return
    siblings[i] = siblings[j].also { siblings[j] = siblings[i] }
    // Keep other categories' relative order: renumber siblings within the global sort sequence.
    val all = b.categories.map { it.state.id }.toMutableList()
    val slots = all.withIndex().filter { it.value in siblings }.map { it.index }
    slots.forEachIndexed { k, slot -> all[slot] = siblings[k] }
    vm.act(R.string.reordered) { reorderCategories(it, all) }
}

@Composable
private fun ReassignDialog(b: Book, from: String, title: String, body: String, allowNone: Boolean = false, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val kind = b.categoryMap[from]?.value?.kind ?: CategoryKind.EXPENSE
    val sub = b.categories.filter { it.value.parentId == from }.map { it.state.id }.toSet()
    // The parent is the natural home for a subcategory's transactions; otherwise the first other category.
    var to by rememberSaveable { mutableStateOf(b.categoryMap[from]?.value?.parentId?.takeIf { it in b.categoryMap } ?: b.categoriesOf(kind).firstOrNull { it.state.id != from && it.state.id !in sub }?.state?.id) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                Text(body)
                SelectField(stringResource(R.string.move_to), to, b.categoriesOf(kind).filter { it.state.id != from && it.state.id !in sub }.map { Choice(it.state.id, b.categoryName(it.state.id)) }, { to = it }, allowNone = allowNone, noneLabel = stringResource(R.string.uncategorised))
            }
        },
        confirmButton = { TextButton(onClick = { onPick(to) }, enabled = allowNone || to != null) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** CAT-01…CAT-05: name, parent (three levels), flags and per-category defaults. */
@Composable
fun CategoryEditScreen(id: String, parent: String?, onBack: () -> Unit, onHistory: (String) -> Unit) {
    WithBook { ctx, vm ->
        val b = ctx.book
        val existing = b.categoryMap[id]?.value
        val parentCat = parent?.let { b.categoryMap[it]?.value }
        var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
        var kind by rememberSaveable { mutableStateOf(existing?.kind ?: parentCat?.kind ?: CategoryKind.EXPENSE) }
        var parentId by rememberSaveable { mutableStateOf(existing?.parentId ?: parent) }
        var excluded by rememberSaveable { mutableStateOf(existing?.excludedFromSpend ?: false) }
        var budgetable by rememberSaveable { mutableStateOf(existing?.budgetable ?: true) }
        var tax by rememberSaveable { mutableStateOf(existing?.tax ?: false) }
        var defAccount by rememberSaveable { mutableStateOf(existing?.defaultAccountId) }
        var defTags by rememberSaveable { mutableStateOf(existing?.defaultTags ?: emptyList()) }
        var err by rememberSaveable { mutableStateOf<String?>(null) }
        val req = stringResource(R.string.err_name_required)
        val takenErr = stringResource(R.string.name_taken)
        val tooDeep = stringResource(R.string.err_too_deep)
        PennyScreen(
            stringResource(if (existing == null) R.string.add_category else R.string.edit_category), onBack, large = false,
            actions = { if (existing != null) IconButton(onClick = { onHistory(id) }) { Icon(Icons.Outlined.History, stringResource(R.string.history)) } },
        ) { pad ->
            Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                TextInput(name, { name = it; err = null }, stringResource(R.string.field_name), error = err)
                if (existing == null && parent == null) Segmented(listOf(Choice(CategoryKind.EXPENSE, stringResource(R.string.type_expense)), Choice(CategoryKind.INCOME, stringResource(R.string.type_income))), kind, { kind = it; parentId = null })
                val parents = b.categoryTree(kind, false).filter { (s, d) -> d < 3 && s.state.id != id && !isDescendant(b, s.state.id, id) }
                SelectField(stringResource(R.string.parent_category), parentId, parents.map { (s, _) -> Choice(s.state.id, b.categoryName(s.state.id)) }, { parentId = it }, allowNone = true, noneLabel = stringResource(R.string.top_level))
                SwitchRow(stringResource(R.string.excluded_from_spend), excluded, { excluded = it }, subtitle = stringResource(R.string.excluded_from_spend_hint))
                SwitchRow(stringResource(R.string.budgetable), budgetable, { budgetable = it })
                SwitchRow(stringResource(R.string.tax_category), tax, { tax = it })
                SelectField(stringResource(R.string.default_account), defAccount, b.activeAccounts.map { Choice(it.state.id, it.value.name) }, { defAccount = it }, allowNone = true)
                TagsEditor(defTags, emptyList()) { defTags = it }
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.save), {
                    if (name.isBlank()) { err = req; return@PrimaryButton }
                    if (b.depth(parentId) >= 3) { err = tooDeep; return@PrimaryButton }
                    // Same name under the same parent would be two identical rows in every picker.
                    if (b.categories.any { it.state.id != id && it.value.parentId == parentId && it.value.kind == kind && it.value.name.trim().equals(name.trim(), ignoreCase = true) }) { err = takenErr; return@PrimaryButton }
                    val c = (existing ?: Category(kind = kind, name = name)).copy(
                        name = name.trim(), kind = kind, parentId = parentId, excludedFromSpend = excluded, budgetable = budgetable, tax = tax,
                        defaultAccountId = defAccount, defaultTags = defTags,
                    )
                    vm.act(R.string.saved, after = onBack) { saveCategory(it, if (existing == null) null else id, c) }
                }, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun isDescendant(b: Book, candidate: String, of: String): Boolean {
    var c = b.categoryMap[candidate]?.value?.parentId
    var guard = 0
    while (c != null && guard++ < 10) { if (c == of) return true; c = b.categoryMap[c]?.value?.parentId }
    return false
}

/** TX-13 swipe right: change category in one tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(txId: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        WithBook { ctx, vm ->
            val b = ctx.book
            val t = b.txMap[txId]?.value ?: run { onDismiss(); return@WithBook }
            val kind = if (t.type == TxType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            Text(stringResource(R.string.change_category), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            // A full category tree is long (60+ rows); a search box gets to any one in a couple of letters.
            var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
            app.penny.ui.kit.TextInput(query, { query = it }, stringResource(R.string.search))
            val q = query.trim()
            val rows = b.categoryTree(kind, false).let { tree -> if (q.isEmpty()) tree else tree.filter { it.first.value.name.contains(q, ignoreCase = true) } }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(rows, key = { it.first.state.id }) { (s, d) ->
                    ListRow(
                        title = s.value.name, modifier = Modifier.padding(start = (if (q.isEmpty()) (d - 1) * 20 else 0).dp), selected = s.state.id == t.categoryId,
                        leading = { Avatar(s.value.name, s.state.id, icon = categoryIcon(s.value.key), size = 32.dp) },
                        onClick = {
                            vm.act(R.string.updated) { book -> saveTransaction(book, txId, t.copy(categoryId = s.state.id, splits = emptyList())).group }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}
