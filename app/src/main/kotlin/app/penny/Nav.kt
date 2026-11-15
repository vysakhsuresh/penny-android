package app.penny

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.penny.core.Brand
import app.penny.core.model.TxType
import app.penny.ui.accounts.AccountDetailScreen
import app.penny.ui.accounts.AccountEditScreen
import app.penny.ui.accounts.AccountsScreen
import app.penny.ui.budget.BudgetsScreen
import app.penny.ui.budget.EnvelopeDetailScreen
import app.penny.ui.budget.EnvelopeEditScreen
import app.penny.ui.capture.ReviewScreen
import app.penny.ui.catalog.CategoriesScreen
import app.penny.ui.catalog.CategoryEditScreen
import app.penny.ui.catalog.CategorySheet
import app.penny.ui.catalog.PayeeDetailScreen
import app.penny.ui.catalog.PayeesScreen
import app.penny.ui.catalog.RuleEditScreen
import app.penny.ui.catalog.RulesScreen
import app.penny.ui.catalog.TagsScreen
import app.penny.ui.catalog.TemplatesScreen
import app.penny.ui.home.AlertsScreen
import app.penny.ui.home.HomeNav
import app.penny.ui.home.HomeScreen
import app.penny.ui.history.EntityHistoryScreen
import app.penny.ui.history.GlobalHistoryScreen
import app.penny.ui.history.TrashScreen
import app.penny.ui.insights.CoachScreen
import app.penny.ui.insights.InsightsScreen
import app.penny.ui.onboarding.OnboardingScreen
import app.penny.ui.people.CircleScreen
import app.penny.ui.people.CircleEditScreen
import app.penny.ui.people.GroupExpenseEditScreen
import app.penny.ui.people.GroupScreen
import app.penny.ui.people.GroupEditScreen
import app.penny.ui.people.LendingEditScreen
import app.penny.ui.people.PeopleScreen
import app.penny.ui.people.PersonScreen
import app.penny.ui.people.PersonEditScreen
import app.penny.ui.people.RemittanceEditScreen
import app.penny.ui.people.RemittancesScreen
import app.penny.ui.plan.BillsScreen
import app.penny.ui.plan.GoalDetailScreen
import app.penny.ui.plan.GoalEditScreen
import app.penny.ui.plan.GoalsScreen
import app.penny.ui.plan.LoanDetailScreen
import app.penny.ui.plan.LoanEditScreen
import app.penny.ui.plan.LoansScreen
import app.penny.ui.plan.RecurringEditScreen
import app.penny.ui.settings.AboutScreen
import app.penny.ui.settings.AppearanceScreen
import app.penny.ui.settings.AlertSettingsScreen
import app.penny.ui.settings.CaptureSettingsScreen
import app.penny.ui.settings.CoffeeScreen
import app.penny.ui.settings.DataScreen
import app.penny.ui.settings.GuidesScreen
import app.penny.ui.settings.HelpScreen
import app.penny.ui.settings.ImportScreen
import app.penny.ui.settings.PlusScreen
import app.penny.ui.settings.ProfileSettingsScreen
import app.penny.ui.settings.ProfilesScreen
import app.penny.ui.settings.SecurityScreen
import app.penny.ui.settings.SettingsScreen
import app.penny.ui.settings.SpacesScreen
import app.penny.ui.settings.SyncScreen
import app.penny.ui.tx.ActivityScreen
import app.penny.ui.tx.QuickAddSheet
import app.penny.ui.tx.TransactionFormScreen
import app.penny.ui.tx.TransactionFormViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val MAIN = "main?tab={tab}"
    fun main(tab: String = "home") = "main?tab=$tab"
    const val TX = "tx/{id}?type={type}&amount={amount}&category={category}&payee={payee}&note={note}"
    fun tx(id: String, type: TxType? = null) = "tx/$id" + (type?.let { "?type=${it.name}" } ?: "")
    const val HISTORY = "history/{entity}/{id}"
    fun history(entity: String, id: String) = "history/$entity/$id"
    const val ACTIVITY = "activity?filter={filter}&search={search}"
    fun activity(filterJson: String? = null, search: Boolean = false) =
        "activity?search=$search" + (filterJson?.let { "&filter=" + android.net.Uri.encode(it) } ?: "")
    const val ACCOUNTS = "accounts"
    const val ACCOUNT = "account/{id}"
    const val ACCOUNT_EDIT = "account/{id}/edit"
    const val CATEGORIES = "categories"
    const val CATEGORY_EDIT = "category/{id}/edit?parent={parent}"
    const val TAGS = "tags"
    const val PAYEES = "payees"
    const val PAYEE = "payee/{id}"
    const val RULES = "rules"
    const val RULE_EDIT = "rule/{id}/edit"
    const val TEMPLATES = "templates"
    const val ENVELOPE = "envelope/{id}"
    const val ENVELOPE_EDIT = "envelope/{id}/edit"
    const val GOALS = "goals"
    const val GOAL = "goal/{id}"
    const val GOAL_EDIT = "goal/{id}/edit"
    const val BILLS = "bills"
    const val RECURRING_EDIT = "recurring/{id}/edit?from={from}"
    const val LOANS = "loans"
    const val LOAN = "loan/{id}"
    const val LOAN_EDIT = "loan/{id}/edit"
    const val PERSON = "person/{id}"
    const val PERSON_EDIT = "person/{id}/edit"
    const val LENDING_EDIT = "lending/{id}/edit?person={person}"
    const val GROUP = "group/{id}"
    const val GROUP_EDIT = "group/{id}/edit"
    const val GROUP_EXPENSE = "groupexpense/{id}?group={group}"
    const val REMITTANCES = "remittances"
    const val REMITTANCE_EDIT = "remittance/{id}/edit"
    const val CIRCLE = "circle/{id}"
    const val CIRCLE_EDIT = "circle/{id}/edit"
    const val COACH = "coach"
    const val ALERTS = "alerts"
    const val REVIEW = "review"
    const val TRASH = "trash"
    const val GLOBAL_HISTORY = "globalhistory"
    const val SETTINGS = "settings"
    const val PROFILE_SETTINGS = "settings/profile"
    const val PROFILES = "profiles"
    const val APPEARANCE = "settings/appearance"
    const val ALERT_SETTINGS = "settings/alerts"
    const val CAPTURE_SETTINGS = "settings/capture"
    const val SECURITY = "settings/security"
    const val DATA = "settings/data"
    const val IMPORT = "settings/import"
    const val SPACES = "settings/spaces?token={token}"
    const val SYNC = "settings/sync"
    const val PLUS = "settings/plus"
    const val ABOUT = "settings/about"
    const val HELP = "help"
    const val GUIDES = "guides?topic={topic}"
    fun guides(topic: String? = null) = "guides" + (topic?.let { "?topic=$it" } ?: "")
    const val COFFEE = "coffee"
    const val VOICE = "voice"
    const val SCAN = "scan"
    const val NEW = TransactionFormViewModel.NEW
}

private fun str(name: String, default: String? = null) = navArgument(name) {
    type = NavType.StringType
    nullable = default == null
    if (default != null) defaultValue = default else defaultValue = null
}

private fun NavBackStackEntry.arg(name: String): String? = arguments?.getString(name)

private enum class Tab(val key: String, val label: Int, val icon: ImageVector, val selected: ImageVector) {
    HOME("home", R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    BUDGETS("budgets", R.string.tab_budgets, Icons.Outlined.PieChart, Icons.Filled.PieChart),
    PEOPLE("people", R.string.tab_people, Icons.Outlined.People, Icons.Filled.People),
    INSIGHTS("insights", R.string.tab_insights, Icons.Outlined.Insights, Icons.Filled.Insights),
}

@Composable
fun PennyNavHost(nav: NavHostController, start: String, reduceMotion: Boolean) {
    val dur = if (reduceMotion) 0 else 220
    NavHost(
        nav, startDestination = start,
        enterTransition = { fadeIn(tween(dur)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(dur)) { it / 12 } },
        exitTransition = { fadeOut(tween(dur / 2)) },
        popEnterTransition = { fadeIn(tween(dur)) },
        popExitTransition = { fadeOut(tween(dur / 2)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(dur)) { it / 12 } },
    ) {
        val back: () -> Unit = { nav.popBackStack() }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(hiltViewModel(), onImport = { nav.navigate(Routes.IMPORT) }) {
                nav.navigate(Routes.main()) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(
            Routes.MAIN, arguments = listOf(str("tab", "home")),
            deepLinks = listOf(navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://home?tab={tab}" }),
        ) { e -> MainTabs(nav, e.arg("tab") ?: "home") }
        composable(
            Routes.TX,
            arguments = listOf(str("id", Routes.NEW), str("type"), str("amount"), str("category"), str("payee"), str("note")),
            // WG-03: penny://add?amount=…&category=… (amount in minor units).
            deepLinks = listOf(
                navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://add?type={type}&amount={amount}&category={category}&payee={payee}&note={note}" },
                navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://tx/{id}" },
            ),
        ) {
            TransactionFormScreen(hiltViewModel(), onBack = back, onHistory = { nav.navigate(Routes.history("transaction", it)) }, onMakeRecurring = { nav.navigate("recurring/${Routes.NEW}/edit?from=$it") })
        }
        composable(Routes.HISTORY, arguments = listOf(str("entity", "transaction"), str("id", ""))) { e -> EntityHistoryScreen(e.arg("entity")!!, e.arg("id")!!, back) }
        composable(
            Routes.ACTIVITY, arguments = listOf(str("filter"), str("search", "false")),
            deepLinks = listOf(navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://activity" }),
        ) { e -> ActivityScreen(e.arg("filter"), back, onOpen = { nav.navigate(Routes.tx(it)) }, focusSearch = e.arg("search") == "true") }
        composable(Routes.ACCOUNTS) { AccountsScreen(back, onOpen = { nav.navigate("account/$it") }, onAdd = { nav.navigate("account/${Routes.NEW}/edit") }) }
        composable(Routes.ACCOUNT, arguments = listOf(str("id", ""))) { e ->
            AccountDetailScreen(e.arg("id")!!, back, onEdit = { nav.navigate("account/$it/edit") }, onOpenTx = { nav.navigate(Routes.tx(it)) }, onHistory = { nav.navigate(Routes.history("account", it)) })
        }
        composable(Routes.ACCOUNT_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> AccountEditScreen(e.arg("id")!!, back) }
        composable(Routes.CATEGORIES) { CategoriesScreen(back, onEdit = { id, parent -> nav.navigate("category/$id/edit" + (parent?.let { "?parent=$it" } ?: "")) }) }
        composable(Routes.CATEGORY_EDIT, arguments = listOf(str("id", Routes.NEW), str("parent"))) { e ->
            CategoryEditScreen(e.arg("id")!!, e.arg("parent"), back, onHistory = { nav.navigate(Routes.history("category", it)) })
        }
        composable(Routes.TAGS) { TagsScreen(back, onActivity = { nav.navigate(Routes.activity(it)) }) }
        composable(Routes.PAYEES) { PayeesScreen(back, onOpen = { nav.navigate("payee/$it") }) }
        composable(Routes.PAYEE, arguments = listOf(str("id", ""))) { e -> PayeeDetailScreen(e.arg("id")!!, back, onOpenTx = { nav.navigate(Routes.tx(it)) }, onHistory = { nav.navigate(Routes.history("payee", it)) }) }
        composable(Routes.RULES) { RulesScreen(back, onEdit = { nav.navigate("rule/$it/edit") }) }
        composable(Routes.RULE_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> RuleEditScreen(e.arg("id")!!, back) }
        composable(Routes.TEMPLATES) { TemplatesScreen(back) }
        composable(Routes.ENVELOPE, arguments = listOf(str("id", ""))) { e ->
            EnvelopeDetailScreen(e.arg("id")!!, back, onEdit = { nav.navigate("envelope/$it/edit") }, onOpenTx = { nav.navigate(Routes.tx(it)) }, onHistory = { nav.navigate(Routes.history("envelope", it)) })
        }
        composable(Routes.ENVELOPE_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> EnvelopeEditScreen(e.arg("id")!!, back) }
        composable(Routes.GOALS) { GoalsScreen(back, onOpen = { nav.navigate("goal/$it") }, onAdd = { nav.navigate("goal/${Routes.NEW}/edit") }) }
        composable(Routes.GOAL, arguments = listOf(str("id", ""))) { e -> GoalDetailScreen(e.arg("id")!!, back, onEdit = { nav.navigate("goal/$it/edit") }, onHistory = { nav.navigate(Routes.history("goal", it)) }) }
        composable(Routes.GOAL_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> GoalEditScreen(e.arg("id")!!, back) }
        composable(Routes.BILLS) { BillsScreen(back, onEdit = { nav.navigate("recurring/$it/edit") }, onOpenTx = { nav.navigate(Routes.tx(it)) }) }
        composable(Routes.RECURRING_EDIT, arguments = listOf(str("id", Routes.NEW), str("from"))) { e -> RecurringEditScreen(e.arg("id")!!, e.arg("from"), back, onHistory = { nav.navigate(Routes.history("recurring", it)) }) }
        composable(Routes.LOANS) { LoansScreen(back, onOpen = { nav.navigate("loan/$it") }, onAdd = { nav.navigate("loan/${Routes.NEW}/edit") }) }
        composable(Routes.LOAN, arguments = listOf(str("id", ""))) { e -> LoanDetailScreen(e.arg("id")!!, back, onEdit = { nav.navigate("loan/$it/edit") }) }
        composable(Routes.LOAN_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> LoanEditScreen(e.arg("id")!!, back) }
        composable(Routes.PERSON, arguments = listOf(str("id", ""))) { e ->
            PersonScreen(e.arg("id")!!, back, onEdit = { nav.navigate("person/$it/edit") }, onEntry = { id, p -> nav.navigate("lending/$id/edit?person=$p") }, onGroup = { nav.navigate("group/$it") })
        }
        composable(Routes.PERSON_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> PersonEditScreen(e.arg("id")!!, back) }
        composable(Routes.LENDING_EDIT, arguments = listOf(str("id", Routes.NEW), str("person"))) { e -> LendingEditScreen(e.arg("id")!!, e.arg("person"), back) }
        composable(Routes.GROUP, arguments = listOf(str("id", ""))) { e ->
            GroupScreen(e.arg("id")!!, back, onEdit = { nav.navigate("group/$it/edit") }, onExpense = { id, g -> nav.navigate("groupexpense/$id?group=$g") })
        }
        composable(Routes.GROUP_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> GroupEditScreen(e.arg("id")!!, back) }
        composable(Routes.GROUP_EXPENSE, arguments = listOf(str("id", Routes.NEW), str("group"))) { e -> GroupExpenseEditScreen(e.arg("id")!!, e.arg("group"), back) }
        composable(Routes.REMITTANCES) { RemittancesScreen(back, onEdit = { nav.navigate("remittance/$it/edit") }) }
        composable(Routes.REMITTANCE_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> RemittanceEditScreen(e.arg("id")!!, back) }
        composable(Routes.CIRCLE, arguments = listOf(str("id", ""))) { e -> CircleScreen(e.arg("id")!!, back, onEdit = { nav.navigate("circle/$it/edit") }) }
        composable(Routes.CIRCLE_EDIT, arguments = listOf(str("id", Routes.NEW))) { e -> CircleEditScreen(e.arg("id")!!, back) }
        composable(Routes.COACH) { CoachScreen(back, onOpenTx = { nav.navigate(Routes.tx(it)) }) }
        composable(Routes.ALERTS) { AlertsScreen(back, onEnvelope = { nav.navigate("envelope/$it") }, onSettings = { nav.navigate(Routes.ALERT_SETTINGS) }) }
        composable(Routes.REVIEW, deepLinks = listOf(navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://review" })) { ReviewScreen(back) }
        composable(Routes.TRASH) { TrashScreen(back) }
        composable(Routes.GLOBAL_HISTORY) { GlobalHistoryScreen(back, onOpen = { entity, id -> nav.navigate(Routes.history(entity, id)) }) }
        composable(Routes.SETTINGS) { SettingsScreen(back, go = { nav.navigate(it) }) }
        composable(Routes.PROFILE_SETTINGS) { ProfileSettingsScreen(back, onDeleted = { nav.navigate(Routes.PROFILES) { popUpTo(0) } }) }
        composable(Routes.PROFILES) { ProfilesScreen(back, onSwitched = { nav.navigate(Routes.main()) { popUpTo(0) } }, onNone = { nav.navigate(Routes.ONBOARDING) { popUpTo(0) } }) }
        composable(Routes.APPEARANCE) { AppearanceScreen(back) }
        composable(Routes.ALERT_SETTINGS) { AlertSettingsScreen(back) }
        composable(Routes.CAPTURE_SETTINGS) { CaptureSettingsScreen(back, go = { nav.navigate(it) }) }
        composable(Routes.SECURITY) { SecurityScreen(back) }
        composable(Routes.DATA) { DataScreen(back, go = { nav.navigate(it) }, onWiped = { nav.navigate(Routes.ONBOARDING) { popUpTo(0) } }) }
        composable(Routes.IMPORT) { ImportScreen(back) }
        composable(
            Routes.SPACES, arguments = listOf(str("token")),
            deepLinks = listOf(navDeepLink { uriPattern = "${Brand.DEEP_LINK_SCHEME}://invite/{token}" }),
        ) { e -> SpacesScreen(back, initialToken = e.arg("token")) }
        composable(Routes.SYNC) { SyncScreen(back) }
        composable(Routes.PLUS) { PlusScreen(back) }
        composable(Routes.ABOUT) { AboutScreen(back, go = { nav.navigate(it) }) }
        composable(Routes.HELP) { HelpScreen(back, go = { nav.navigate(it) }) }
        composable(Routes.GUIDES, arguments = listOf(str("topic"))) { e -> GuidesScreen(e.arg("topic"), back) }
        composable(Routes.COFFEE) { CoffeeScreen(back) }
        composable(Routes.VOICE) { app.penny.ui.capture.VoiceScreen(back, onDone = { nav.popBackStack() }) }
        composable(Routes.SCAN) { app.penny.ui.capture.ScanScreen(back, onDone = { nav.popBackStack() }) }
    }
}

/** UX-03: four tabs plus a centre add button; Accounts and Settings live behind the avatar and Settings entry. */
@Composable
private fun MainTabs(nav: NavHostController, initial: String) {
    var tab by rememberSaveable { mutableStateOf(Tab.entries.firstOrNull { it.key == initial } ?: Tab.HOME) }
    var quickAdd by rememberSaveable { mutableStateOf(false) }
    var categorySheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEachIndexed { i, t ->
                    if (i == 2) {
                        Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            FloatingActionButton(onClick = { quickAdd = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(56.dp)) {
                                Icon(Icons.Filled.Add, stringResource(R.string.add_transaction))
                            }
                        }
                    }
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(if (tab == t) t.selected else t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { pad: PaddingValues ->
        when (tab) {
            Tab.HOME -> HomeScreen(
                HomeNav(
                    openTx = { nav.navigate(Routes.tx(it)) }, add = { quickAdd = true }, activity = { nav.navigate(Routes.activity()) },
                    search = { nav.navigate(Routes.activity(search = true)) }, accounts = { nav.navigate(Routes.ACCOUNTS) },
                    account = { nav.navigate("account/$it") }, envelope = { nav.navigate("envelope/$it") }, bills = { nav.navigate(Routes.BILLS) },
                    alerts = { nav.navigate(Routes.ALERTS) }, review = { nav.navigate(Routes.REVIEW) }, profiles = { nav.navigate(Routes.PROFILES) },
                    categorySheet = { categorySheetFor = it },
                ),
                pad,
            )
            Tab.BUDGETS -> BudgetsScreen(pad, go = { nav.navigate(it) })
            Tab.PEOPLE -> PeopleScreen(pad, go = { nav.navigate(it) })
            Tab.INSIGHTS -> InsightsScreen(pad, go = { nav.navigate(it) })
        }
    }
    if (quickAdd) {
        QuickAddSheet(
            onDismiss = { quickAdd = false },
            onMore = { type -> nav.navigate(Routes.tx(Routes.NEW, type)) },
            onVoice = { nav.navigate(Routes.VOICE) },
            onScan = { nav.navigate(Routes.SCAN) },
        )
    }
    categorySheetFor?.let { id -> CategorySheet(id, onDismiss = { categorySheetFor = null }) }
}
