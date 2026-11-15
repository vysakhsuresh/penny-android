package app.penny.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.penny.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** One message with an optional undo action. [onExpire] runs if the user does not undo (used for delayed purge). */
data class UndoMessage(
    val text: String,
    val undo: (suspend () -> Unit)?,
    val onExpire: (suspend () -> Unit)? = null,
) {
    /** 0 pending, 1 undone, 2 expired. Exactly one of undo / expire ever runs. */
    internal val outcome = AtomicInteger(0)
}

/**
 * UX-02 / TX-03: destructive actions run immediately and offer a 5-second undo instead of a confirmation dialog.
 *
 * App-scoped so the snackbar survives navigating back from the screen that triggered it. Messages are buffered (none are
 * dropped while no screen is collecting, e.g. while locked), and the expiry timer runs here, not in the UI, so a delayed
 * purge still happens if the activity is rotated or backgrounded during the window.
 */
@Singleton
class UndoController @Inject constructor() {
    internal val messages = Channel<UndoMessage>(capacity = 16, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal val scope = CoroutineScope(SupervisorJob())

    fun offer(message: UndoMessage) {
        messages.trySend(message)
        if (message.onExpire != null) scope.launch {
            delay(UNDO_WINDOW_MS + 300)
            if (message.outcome.compareAndSet(0, 2)) runCatching { message.onExpire.invoke() }
        }
    }

    internal fun performUndo(message: UndoMessage) {
        if (message.outcome.compareAndSet(0, 1)) scope.launch { runCatching { message.undo?.invoke() } }
    }

    companion object { const val UNDO_WINDOW_MS = 5_000L }
}

@Composable
fun UndoHost(controller: UndoController, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(controller) {
        controller.messages.receiveAsFlow().collect { m ->
            if (m.outcome.get() != 0) return@collect // already expired while nobody was showing it
            val result = withTimeoutOrNull(UndoController.UNDO_WINDOW_MS) {
                host.showSnackbar(m.text, actionLabel = if (m.undo != null) undoLabel else null, duration = SnackbarDuration.Indefinite)
            }
            if (result == null) host.currentSnackbarData?.dismiss()
            if (result == SnackbarResult.ActionPerformed) controller.performUndo(m)
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 80.dp))
    }
}
