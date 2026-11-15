package app.penny.ui.settings

import app.penny.ui.common.safeStart
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.penny.BuildConfig
import app.penny.R
import app.penny.Routes
import app.penny.core.Brand
import app.penny.ui.kit.Avatar
import app.penny.ui.kit.ListRow
import app.penny.ui.kit.PennyCard
import app.penny.ui.kit.PennyScreen
import app.penny.ui.kit.SectionHeader
import app.penny.ui.kit.SwitchRow

/** Guide topics: title and body string arrays, so every guide is translatable and works offline. */
data class Guide(val key: String, val title: Int, val body: Int)

val GUIDES = listOf(
    Guide("start", R.string.guide_start_title, R.array.guide_start),
    Guide("safe", R.string.guide_safe_title, R.array.guide_safe),
    Guide("budget", R.string.guide_budget_title, R.array.guide_budget),
    Guide("cycle", R.string.guide_cycle_title, R.array.guide_cycle),
    Guide("capture", R.string.guide_capture_title, R.array.guide_capture),
    Guide("people", R.string.guide_people_title, R.array.guide_people),
    Guide("bills", R.string.guide_bills_title, R.array.guide_bills),
    Guide("goals", R.string.guide_goals_title, R.array.guide_goals),
    Guide("import", R.string.guide_import_title, R.array.guide_import),
    Guide("undo", R.string.guide_undo_title, R.array.guide_undo),
    Guide("privacy", R.string.guide_privacy_title, R.array.guide_privacy),
    Guide("sync", R.string.guide_sync_title, R.array.guide_sync),
)

/** Get help: searchable FAQ, guides, contact with optional diagnostics (ST-12), report a bug, suggest a feature. */
@Composable
fun HelpScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val context = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var diag by rememberSaveable { mutableStateOf(false) }
    val questions = stringArrayResource(R.array.faq_q)
    val answers = stringArrayResource(R.array.faq_a)
    fun email(subject: String) {
        val body = if (diag) "\n\n---\n${Brand.NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n${Build.MANUFACTURER} ${Build.MODEL}\n" + context.getString(R.string.diag_no_data) else ""
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(Brand.SUPPORT_EMAIL)).putExtra(Intent.EXTRA_SUBJECT, "${Brand.NAME}: $subject").putExtra(Intent.EXTRA_TEXT, body)
        context.safeStart(i)
    }
    PennyScreen(stringResource(R.string.get_help), onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
            item {
                OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.search_help)) }, singleLine = true, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            item { SectionHeader(stringResource(R.string.faq)) }
            val idx = questions.indices.filter { q.isBlank() || questions[it].contains(q, true) || answers.getOrNull(it)?.contains(q, true) == true }
            items(idx) { i ->
                PennyCard(onClick = { open = if (open == i) null else i }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(questions[i], style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Icon(if (open == i) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                    if (open == i) Text(answers.getOrElse(i) { "" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                }
            }
            if (idx.isEmpty()) item { Text(stringResource(R.string.help_no_match), modifier = Modifier.padding(20.dp)) }
            item { SectionHeader(stringResource(R.string.guides)) }
            item {
                PennyCard {
                    GUIDES.take(4).forEach { g -> ListRow(stringResource(g.title), leading = { Avatar("G", g.key, icon = Icons.AutoMirrored.Outlined.LibraryBooks, size = 36.dp) }, onClick = { go(Routes.guides(g.key)) }, divider = true) }
                    TextButton(onClick = { go(Routes.guides()) }, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.all_guides)) }
                }
            }
            item { SectionHeader(stringResource(R.string.contact)) }
            item {
                PennyCard {
                    ListRow(stringResource(R.string.email_support), subtitle = stringResource(R.string.email_support_sub), leading = { Avatar("E", "email", icon = Icons.Outlined.Email, size = 36.dp) }, onClick = { email(context.getString(R.string.help_request)) }, divider = true)
                    ListRow(stringResource(R.string.report_bug), leading = { Avatar("B", "bug", icon = Icons.Outlined.BugReport, size = 36.dp) }, onClick = { email(context.getString(R.string.bug_report)) }, divider = true)
                    ListRow(stringResource(R.string.suggest_feature), leading = { Avatar("I", "idea", icon = Icons.Outlined.Lightbulb, size = 36.dp) }, onClick = { email(context.getString(R.string.feature_idea)) }, divider = true)
                    SwitchRow(stringResource(R.string.include_diagnostics), diag, { diag = it }, subtitle = stringResource(R.string.include_diagnostics_hint))
                }
            }
        }
    }
}

/** Guides: short, plain-language walkthroughs, available offline and translatable. */
@Composable
fun GuidesScreen(topic: String?, onBack: () -> Unit) {
    var openKey by rememberSaveable { mutableStateOf(topic) }
    val g = GUIDES.firstOrNull { it.key == openKey }
    PennyScreen(if (g != null) stringResource(g.title) else stringResource(R.string.guides), { if (g != null && topic == null) openKey = null else onBack() }) { pad ->
        if (g == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 48.dp)) {
                item { Text(stringResource(R.string.guides_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(GUIDES, key = { it.key }) { x ->
                    val steps = stringArrayResource(x.body)
                    PennyCard(onClick = { openKey = x.key }) {
                        ListRow(stringResource(x.title), subtitle = steps.firstOrNull(), leading = { Avatar(stringResource(x.title), x.key, icon = Icons.AutoMirrored.Outlined.LibraryBooks) })
                    }
                }
            }
        } else {
            val steps = stringArrayResource(g.body)
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Avatar((i + 1).toString(), g.key + i, size = 32.dp)
                        Spacer(Modifier.size(14.dp))
                        Text(s, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * MO-05 tip jar: voluntary, never gates anything. Amounts are tiers the store prices per region (MO-02); the
 * purchase goes through store billing (no third-party SDK) via [app.penny.billing.Billing].
 */
@Composable
fun CoffeeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var thanked by rememberSaveable { mutableStateOf(false) }
    val tiers = listOf("tip_small" to R.string.tip_small, "tip_medium" to R.string.tip_medium, "tip_large" to R.string.tip_large)
    PennyScreen(stringResource(R.string.buy_coffee), onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar("C", "coffee", icon = Icons.Outlined.Coffee, size = 88.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.coffee_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.coffee_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp))
            tiers.forEach { (sku, label) ->
                FilledTonalButton(onClick = { app.penny.billing.Billing.buy(context, sku) { ok -> if (ok) thanked = true } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (thanked) Text(stringResource(R.string.coffee_thanks), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(24.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.coffee_never_gates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                context.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.packageName)))
            }) { Icon(Icons.Outlined.Favorite, null); Spacer(Modifier.size(8.dp)); Text(stringResource(R.string.rate_instead)) }
        }
    }
}

/** ST-12 About: trust page (what we never do), privacy, licences, changelog, version. */
@Composable
fun AboutScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val uri = LocalUriHandler.current
    var tab by rememberSaveable { mutableStateOf("about") }
    PennyScreen(stringResource(R.string.about_us), onBack) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 48.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(Brand.NAME, "brand", size = 72.dp)
                Spacer(Modifier.height(12.dp))
                Text(Brand.NAME, style = MaterialTheme.typography.headlineMedium)
                Text(Brand.TAGLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.version_x, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("about" to R.string.our_story, "trust" to R.string.trust, "changes" to R.string.changelog, "licences" to R.string.licences).forEach { (k, l) ->
                    FilterChip(tab == k, { tab = k }, label = { Text(stringResource(l)) })
                }
            }
            when (tab) {
                "about" -> stringArrayResource(R.array.about_story).forEach { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                "trust" -> {
                    SectionHeader(stringResource(R.string.what_we_never_do))
                    stringArrayResource(R.array.trust_never).forEach { line ->
                        ListRow(line, leading = { Icon(Icons.Outlined.Shield, null, tint = MaterialTheme.colorScheme.primary) }, titleMaxLines = 3)
                    }
                    SectionHeader(stringResource(R.string.privacy))
                    Text(stringResource(R.string.privacy_summary), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
                    TextButton(onClick = { runCatching { uri.openUri(Brand.PRIVACY_URL) } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.full_privacy_policy)) }
                }
                "changes" -> stringArrayResource(R.array.changelog).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).semantics { if (it.startsWith("v")) heading() }) }
                else -> stringArrayResource(R.array.licences).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            PennyCard {
                ListRow(stringResource(R.string.get_help), onClick = { go(Routes.HELP) }, divider = true)
                ListRow(stringResource(R.string.buy_coffee), onClick = { go(Routes.COFFEE) })
            }
        }
    }
}
