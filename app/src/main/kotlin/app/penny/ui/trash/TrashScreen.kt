package app.penny.ui.trash

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.domain.PennyRepository
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import app.penny.ui.common.shortDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashRow(val id: String, val title: String, val detail: String)

/** TX-03 / HS-02: deleted transactions with restore and delete-forever (delayed 5 s so it can be undone). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val undo: UndoController,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val pendingPurge = MutableStateFlow<Set<String>>(emptySet())

    val rows: StateFlow<List<TrashRow>?> = repo.observeProfile().filterNotNull().flatMapLatest { p ->
        val fmt = MoneyFmt.of(p.value, repo.packs.get(p.value.regionPack))
        combine(repo.observeTrash(p.state.id), pendingPurge) { items, pending ->
            items.filter { it.id !in pending }.map { i ->
                TrashRow(
                    id = i.id,
                    title = i.tx.payee ?: i.tx.note.ifBlank { shortDate(i.tx.date) },
                    detail = "${shortDate(i.tx.date)} · ${fmt.format(i.tx.amountMinor, i.tx.currency)}",
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: String) = viewModelScope.launch {
        val g = repo.transactions.restore(id)
        undo.offer(UndoMessage(context.getString(R.string.restored), undo = { repo.transactions.undo(g) }))
    }

    fun purge(id: String) {
        pendingPurge.value += id
        undo.offer(
            UndoMessage(
                context.getString(R.string.deleted_forever),
                undo = { pendingPurge.value -= id },
                onExpire = { repo.transactions.purge(id); pendingPurge.value -= id },
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: TrashViewModel, onBack: () -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val list = rows
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(stringResource(R.string.trash_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            when {
                list == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text(stringResource(R.string.trash_empty)) }
                else -> LazyColumn {
                    items(list, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.bodyLarge)
                                Text(r.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { vm.restore(r.id) }) { Text(stringResource(R.string.restore)) }
                            TextButton(onClick = { vm.purge(r.id) }) { Text(stringResource(R.string.delete_forever)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
