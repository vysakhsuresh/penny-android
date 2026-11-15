package app.penny

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.penny.domain.AppPrefs
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoHost
import app.penny.ui.home.HomeScreen
import app.penny.ui.onboarding.OnboardingScreen
import app.penny.ui.settings.SettingsScreen
import app.penny.ui.theme.PennyTheme
import app.penny.ui.trash.TrashScreen
import app.penny.ui.tx.HistoryScreen
import app.penny.ui.tx.TransactionFormScreen
import app.penny.ui.tx.TransactionFormViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var undo: UndoController
    @Inject lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val start = if (prefs.currentProfileId.value == null) Routes.ONBOARDING else Routes.HOME
        setContent {
            PennyTheme {
                Surface {
                    UndoHost(undo) { PennyNavHost(start) }
                }
            }
        }
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TX = "tx/{${TransactionFormViewModel.ARG_ID}}"
    const val HISTORY = "tx/{${TransactionFormViewModel.ARG_ID}}/history"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    fun tx(id: String) = "tx/$id"
    fun history(id: String) = "tx/$id/history"
}

@Composable
private fun PennyNavHost(start: String) {
    val nav = rememberNavController()
    val idArg = listOf(navArgument(TransactionFormViewModel.ARG_ID) { type = NavType.StringType })
    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(hiltViewModel()) {
                nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                vm = hiltViewModel(),
                onAdd = { nav.navigate(Routes.tx(TransactionFormViewModel.NEW)) },
                onOpen = { nav.navigate(Routes.tx(it)) },
                onTrash = { nav.navigate(Routes.TRASH) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onNeedsOnboarding = { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.HOME) { inclusive = true } } },
            )
        }
        composable(
            Routes.TX,
            arguments = listOf(
                navArgument(TransactionFormViewModel.ARG_ID) {
                    type = NavType.StringType
                    defaultValue = TransactionFormViewModel.NEW
                },
            ),
            deepLinks = listOf(navDeepLink { uriPattern = "penny://add" }),
        ) {
            TransactionFormScreen(hiltViewModel(), onBack = { nav.popBackStack() }, onHistory = { nav.navigate(Routes.history(it)) })
        }
        composable(Routes.HISTORY, arguments = idArg) { HistoryScreen(hiltViewModel()) { nav.popBackStack() } }
        composable(Routes.TRASH) { TrashScreen(hiltViewModel()) { nav.popBackStack() } }
        composable(Routes.SETTINGS) { SettingsScreen(hiltViewModel()) { nav.popBackStack() } }
    }
}
