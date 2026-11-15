package app.penny.wear

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WearSummary(val safe: String, val perDay: String, val daysLeft: Int, val templates: List<WearTemplate>, val hidden: Boolean)

@Serializable
data class WearTemplate(val id: String, val name: String, val amount: String?)

/** The last summary from the phone, cached so the tile and complication work without a round trip. */
object WearCache {
    private val json = Json { ignoreUnknownKeys = true }
    fun save(context: Context, raw: String) = context.getSharedPreferences("wear", Context.MODE_PRIVATE).edit().putString("summary", raw).apply()
    fun load(context: Context): WearSummary? = context.getSharedPreferences("wear", Context.MODE_PRIVATE).getString("summary", null)
        ?.let { runCatching { json.decodeFromString(WearSummary.serializer(), it) }.getOrNull() }
}

object Phone {
    suspend fun send(context: Context, path: String, body: ByteArray = ByteArray(0)): Boolean = runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, path, body).await() }
        nodes.isNotEmpty()
    }.getOrDefault(false)
}

/** WG-02: safe to spend, one-tap templates, and voice add. All data comes from the paired phone. */
class WearActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private var summary by mutableStateOf<WearSummary?>(null)
    private var status by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        summary = WearCache.load(this)
        setContent { MaterialTheme { WearHome(summary, status, onTemplate = ::addTemplate, onVoice = ::addVoice, onRefresh = ::refresh) } }
    }

    override fun onResume() { super.onResume(); Wearable.getMessageClient(this).addListener(this); refresh() }
    override fun onPause() { super.onPause(); Wearable.getMessageClient(this).removeListener(this) }

    override fun onMessageReceived(e: MessageEvent) {
        when (e.path) {
            "/penny/summary" -> { WearCache.save(this, String(e.data)); summary = WearCache.load(this) }
            "/penny/added" -> status = String(e.data).let { if (it.isEmpty()) getString(R.string.not_understood) else getString(R.string.added) }
        }
    }

    private fun refresh() { kotlinx.coroutines.MainScope().launch { if (!Phone.send(this@WearActivity, "/penny/summary/request")) status = getString(R.string.no_phone) } }
    private fun addTemplate(id: String) { kotlinx.coroutines.MainScope().launch { Phone.send(this@WearActivity, "/penny/add/template", id.toByteArray()) } }
    private fun addVoice(text: String) { kotlinx.coroutines.MainScope().launch { Phone.send(this@WearActivity, "/penny/add/voice", text.toByteArray()) } }
}

@Composable
private fun WearHome(s: WearSummary?, status: String?, onTemplate: (String) -> Unit, onVoice: (String) -> Unit, onRefresh: () -> Unit) {
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onVoice)
    }
    LaunchedEffect(Unit) { onRefresh() }
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(androidx.compose.ui.res.stringResource(R.string.safe_to_spend), style = MaterialTheme.typography.caption1) }
            item { Text(if (s == null) "—" else if (s.hidden) "••••" else s.safe, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            if (s != null && !s.hidden) item { Text(androidx.compose.ui.res.stringResource(R.string.per_day_left, s.perDay, s.daysLeft), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            status?.let { item { Text(it, style = MaterialTheme.typography.caption2) } }
            item {
                Chip(
                    onClick = { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) },
                    label = { Text(androidx.compose.ui.res.stringResource(R.string.say_it)) }, colors = ChipDefaults.primaryChipColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
            items(s?.templates.orEmpty(), key = { it.id }) { t ->
                Chip(onClick = { onTemplate(t.id) }, label = { Text(t.name) }, secondaryLabel = t.amount?.let { a -> { Text(a) } }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
            }
        }
    }
}
