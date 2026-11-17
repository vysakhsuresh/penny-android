package app.penny

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import app.penny.domain.AppPrefs
import app.penny.domain.SecurityPrefs
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoHost
import app.penny.ui.lock.LockScreen
import app.penny.ui.theme.PennyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single activity. AppCompat so BiometricPrompt works (SE-04).
 *
 * The lock is an overlay above the navigation host, so locking never throws away the back stack, a half-filled form or
 * a pending camera/file result. Unlock state lives for the process only: after process death the app always starts
 * locked. FLAG_SECURE follows the settings live (ST-08).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var undo: UndoController
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var repo: app.penny.domain.PennyRepository

    private var locked by mutableStateOf(false)
    /** Deep links that arrive while the app is open (widgets, tile, shortcuts, notifications). */
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        locked = prefs.security.value.lockEnabled && !unlockedInProcess
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { prefs.security.collect { applySecureFlag(it) } }
        }
        // No book selected but books exist (an interrupted switch): open the first real one instead of setup. Decided off
        // the main thread: on a first install this opens (creates) the encrypted database, which blocked the main
        // thread long enough to show "Penny isn't responding" on slower phones.
        var startRoute by androidx.compose.runtime.mutableStateOf(if (prefs.currentProfileId.value != null) Routes.main() else null)
        if (startRoute == null) lifecycleScope.launch {
            val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repo.firstRealProfileId() }.getOrNull() }
            id?.let { prefs.setCurrentProfile(it) }
            startRoute = if (id == null) Routes.ONBOARDING else Routes.main()
        }
        setContent {
            val start = startRoute
            if (start == null) {
                // A plain frame in the theme's background for the moment the database takes to open.
                PennyTheme(prefs.appearance.collectAsStateWithLifecycle().value) { Surface(Modifier.fillMaxSize()) {} }
                return@setContent
            }
            val appearance by prefs.appearance.collectAsStateWithLifecycle()
            val security by prefs.security.collectAsStateWithLifecycle()
            val showLock = locked && security.lockEnabled
            PennyTheme(appearance) { androidx.compose.runtime.CompositionLocalProvider(app.penny.ui.kit.LocalHideToggle provides { prefs.setAppearance(prefs.appearance.value.copy(hideAmounts = !prefs.appearance.value.hideAmounts)) }) {
                Surface {
                    val nav = rememberNavController()
                    Box(Modifier.fillMaxSize()) {
                        Box(if (showLock) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                            UndoHost(undo) { PennyNavHost(nav, start, appearance.reduceMotion) }
                        }
                        val profileAtLock = androidx.compose.runtime.remember(showLock) { prefs.currentProfileId.value }
                        if (showLock) {
                            BackHandler { moveTaskToBack(true) }
                            Surface(Modifier.fillMaxSize()) { LockScreen(onUnlocked = {
                                unlockedInProcess = true; locked = false
                                // Switching between the real and the decoy book starts again at Home: the screen open before
                                // locking (say, Security settings) must not carry over into the other book.
                                if (prefs.currentProfileId.value != profileAtLock) nav.navigate(Routes.main()) { popUpTo(0) }
                            }) }
                        }
                    }
                    // Whatever switches the open book (a space, a restore, a profile, unlock into another book), the old
                    // book's screens, filters and period offsets must not carry over: start again at Home.
                    LaunchedEffect(Unit) {
                        prefs.currentProfileId.filterNotNull().distinctUntilChanged().drop(1).collect {
                            val route = nav.currentDestination?.route ?: return@collect
                            val freshHome = route.startsWith("main") && nav.previousBackStackEntry == null
                            if (route != Routes.ONBOARDING && !freshHome) nav.navigate(Routes.main()) { popUpTo(0) }
                        }
                    }
                    LaunchedEffect(pendingIntent) {
                        pendingIntent?.let { if (it.data != null) nav.handleDeepLink(it) }
                        pendingIntent = null
                    }
                }
            } }
        }
    }

    private fun applySecureFlag(s: SecurityPrefs) {
        // Amounts hidden in the app switcher while the lock is on (SE-04), or always when screenshots are blocked.
        if (s.blockScreenshots || s.lockEnabled) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) backgroundAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val s = prefs.security.value
        if (s.lockEnabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= s.autoLockSeconds * 1000L) {
            unlockedInProcess = false
            locked = true
        }
        backgroundAt = 0
    }

    private companion object {
        /** Process-scoped: survives rotation, resets on process death (so a killed app always starts locked). */
        var unlockedInProcess = false
        var backgroundAt = 0L
    }
}
