package app.penny.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.ui.common.relativeDate
import app.penny.ui.common.shortDate
import app.penny.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onTrash: () -> Unit,
    onSettings: () -> Unit,
    onNeedsOnboarding: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui) { if (ui is HomeUi.NeedsOnboarding) onNeedsOnboarding() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.brand_name)) },
                actions = {
                    IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, stringResource(R.string.trash)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.add_transaction)) },
            )
        },
    ) { padding ->
        when (val s = ui) {
            HomeUi.Loading, HomeUi.NeedsOnboarding -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "loading" })
            }
            HomeUi.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_generic), style = MaterialTheme.typography.bodyLarge)
            }
            is HomeUi.Ready -> HomeContent(s.data, padding, vm, onAdd, onOpen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(d: HomeData, padding: PaddingValues, vm: HomeViewModel, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 120.dp),
    ) {
        item { SafeCard(d, vm) }
        if (d.accounts.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.accounts),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.accounts, key = { it.id }) { a ->
                        OutlinedCard {
                            Column(Modifier.padding(12.dp).width(140.dp)) {
                                Text(a.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(a.balance, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                stringResource(R.string.transactions),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
            )
        }
        if (d.rows.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.empty_period_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.empty_period_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onAdd) { Text(stringResource(R.string.add_transaction)) }
                }
            }
        } else {
            var lastDate: java.time.LocalDate? = null
            d.rows.forEach { row ->
                if (row.date != lastDate) {
                    val date = row.date
                    lastDate = date
                    item(key = "h$date") {
                        Text(
                            relativeDate(context, date, d.today),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = row.id) { SwipeRow(row, onOpen = { onOpen(row.id) }, onDelete = { vm.delete(row.id) }) }
            }
        }
    }
}

@Composable
private fun SafeCard(d: HomeData, vm: HomeViewModel) {
    val status = LocalStatusColors.current
    val ahead = d.safe.aheadOfPace
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.shiftPeriod(-1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.prev_period))
                }
                Text(
                    stringResource(R.string.period_range, shortDate(d.period.start), shortDate(d.period.endInclusive)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.shiftPeriod(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period))
                }
            }
            if (d.offset != 0) {
                TextButton(onClick = { vm.shiftPeriod(0) }) { Text(stringResource(R.string.this_period)) }
            }
            Text(stringResource(R.string.safe_to_spend), style = MaterialTheme.typography.titleMedium)
            Text(d.safeText, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics(mergeDescendants = true) {})
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.per_day, d.perDayText) + " · " +
                    pluralStringResource(R.plurals.days_left, d.safe.daysLeft, d.safe.daysLeft),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.income_spent, d.incomeText, d.spentText), style = MaterialTheme.typography.bodySmall)
            if (d.safe.unconvertedCount > 0) {
                Text(
                    pluralStringResource(R.plurals.unconverted, d.safe.unconvertedCount, d.safe.unconvertedCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (d.offset == 0) {
                Text(
                    stringResource(if (ahead) R.string.ahead_of_pace else R.string.on_pace),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ahead) status.onNear else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(if (ahead) status.near else MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(row: TxRowUi, onOpen: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        },
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.subtitle.isNotEmpty()) {
                        Text(
                            row.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    row.amount,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.inflow) LocalStatusColors.current.positive else MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}
