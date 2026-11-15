package app.penny.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.penny.R
import app.penny.core.model.AlertState
import app.penny.domain.AlertService
import app.penny.ui.common.WithBook
import app.penny.ui.kit.EmptyState
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class AlertsViewModel @Inject constructor(val service: AlertService) : androidx.lifecycle.ViewModel()

/** AL-06 Home alerts card target: newest first; dismiss, act, mute per envelope (via alert settings). */
@Composable
fun AlertsScreen(onBack: () -> Unit, onEnvelope: (String) -> Unit, onSettings: () -> Unit, avm: AlertsViewModel = hiltViewModel()) {
    WithBook { ctx, vm ->
        val b = ctx.book
        LaunchedEffect(b.profileId) { vm.launch { avm.service.run(b) } }
        val list = b.alerts.filter { it.value.state != AlertState.DISMISSED }
        LaunchedEffect(list.size) {
            val unseen = list.filter { it.value.state == AlertState.NEW }
            if (unseen.isNotEmpty()) vm.launch { alerts.updateMany(unseen.map { it.state.id to it.value.copy(state = AlertState.SEEN) }) }
        }
        PennyScreen(stringResource(R.string.alerts), onBack, actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.alert_settings)) } }) { pad ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                if (list.isEmpty()) item { EmptyState(stringResource(R.string.no_alerts), stringResource(R.string.no_alerts_body), icon = Icons.Outlined.NotificationsNone) }
                items(list, key = { it.state.id }) { a ->
                    PennyCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(a.value.title, style = MaterialTheme.typography.titleSmall)
                            Text(a.value.body, style = MaterialTheme.typography.bodyMedium)
                            Text(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(a.value.firedAtMillis).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row {
                                if (a.value.objectEntity == "envelope" && a.value.objectId != null) TextButton(onClick = {
                                    vm.launch { alerts.update(a.state.id, a.value.copy(state = AlertState.ACTED)) }
                                    onEnvelope(a.value.objectId!!)
                                }) { Text(stringResource(R.string.open)) }
                                TextButton(onClick = { vm.act(R.string.dismissed) { alerts.update(a.state.id, a.value.copy(state = AlertState.DISMISSED)) } }) { Text(stringResource(R.string.dismiss)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
