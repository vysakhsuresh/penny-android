package app.penny.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.penny.R
import app.penny.core.model.Profile
import app.penny.domain.PennyRepository
import app.penny.packs.RegionalPack
import app.penny.ui.common.MoneyFmt
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoMessage
import app.penny.ui.common.cycleLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: PennyRepository,
    private val undo: UndoController,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val packs: List<RegionalPack> = repo.packs.all()
    val profile: StateFlow<Profile?> = repo.observeProfile().map { it?.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Region switch is pure data: currency decimals, grouping and pay cycle come from the pack JSON. */
    fun selectRegion(packId: String) = viewModelScope.launch {
        val pid = repo.currentProfileId.value ?: return@launch
        val groups = repo.switchRegion(pid, packId)
        if (groups.isNotEmpty()) {
            undo.offer(UndoMessage(context.getString(R.string.region_changed, repo.packs.get(packId).name), undo = { repo.undoAll(groups) }))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val p = profile
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_region), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.settings_region_body), style = MaterialTheme.typography.bodySmall)
                    if (p != null) {
                        Text(
                            stringResource(R.string.settings_cycle) + ": " + cycleLabel(context, p.cycle),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            items(vm.packs, key = { it.id }) { pack ->
                val selected = p?.regionPack == pack.id
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .selectable(selected = selected, role = Role.RadioButton) { vm.selectRegion(pack.id) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(pack.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_sample, MoneyFmt.of(pack).format(123456789, pack.currency)) +
                                " · " + cycleLabel(context, pack.payCycleDefault),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.settings_about, stringResource(R.string.brand_name)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
