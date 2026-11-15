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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One message with an optional undo action. [onExpire] runs if the user does not undo (used for delayed purge). */
data class UndoMessage(
    val text: String,
    val undo: (suspend () -> Unit)?,
    val onExpire: (suspend () -> Unit)? = null,
)

/**
 * UX-02 / TX-03: destructive actions run immediately and offer a 5-second undo instead of a confirmation dialog.
 * App-scoped so the snackbar survives navigating back from the screen that triggered it.
 */
@Singleton
class UndoController @Inject constructor() {
    internal val messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 8)
    internal val scope = CoroutineScope(SupervisorJob())
    fun offer(message: UndoMessage) { messages.tryEmit(message) }

    companion object { const val UNDO_WINDOW_MS = 5_000L }
}

@Composable
fun UndoHost(controller: UndoController, content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(controller) {
        controller.messages.collect { m ->
            val result = withTimeoutOrNull(UndoController.UNDO_WINDOW_MS) {
                host.showSnackbar(m.text, actionLabel = if (m.undo != null) undoLabel else null, duration = SnackbarDuration.Indefinite)
            }
            if (result == null) host.currentSnackbarData?.dismiss()
            controller.scope.launch {
                if (result == SnackbarResult.ActionPerformed) m.undo?.invoke() else m.onExpire?.invoke()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 80.dp))
    }
}
