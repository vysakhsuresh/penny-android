package app.penny.ui.common

import app.penny.domain.ops.addCategoryNamed
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.penny.domain.AppPrefs
import app.penny.domain.Book
import app.penny.domain.BookLoad
import app.penny.domain.BookStore
import app.penny.domain.PennyRepository
import app.penny.packs.RegionalPack
import app.penny.ui.kit.ErrorState
import app.penny.ui.kit.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What a screen renders with: the decoded book, its regional pack and its money formatter. */
data class Ctx(val book: Book, val pack: RegionalPack, val fmt: MoneyFmt, val hidden: Boolean = false) {
    val base: String get() = book.profile.baseCurrency
    /** Every amount on screen comes through here, so "hide amounts" covers rows, charts and dialogs alike. */
    fun money(minor: Long, currency: String = base, signed: Boolean = false) = if (hidden) HIDDEN else fmt.format(minor, currency, signed)

    companion object { const val HIDDEN = "••••" }
}

/**
 * The one ViewModel most screens need: the shared book plus "run an operation and offer undo" (UX-02, NN-3).
 * Screen-local form state lives in rememberSaveable so it survives rotation and process death (UX-04).
 */
@HiltViewModel
class BookViewModel @Inject constructor(
    val repo: PennyRepository,
    val store: BookStore,
    val undo: UndoController,
    val prefs: AppPrefs,
    val telemetry: app.penny.domain.Telemetry,
    val handle: SavedStateHandle,
    @ApplicationContext val context: Context,
) : ViewModel() {
    val state: StateFlow<BookLoad> = store.state

    fun ctx(): Ctx? = (state.value as? BookLoad.Ready)?.let { Ctx(it.book, it.pack, it.fmt, prefs.appearance.value.hideAmounts) }

    /**
     * Runs [op] off the main thread; when it returns an undo group and [message] is set, shows the 5-second undo
     * snackbar. [after] runs on the main thread when done (navigation).
     */
    /**
     * Adds a category from inside a picker and hands back its id to select (the existing one when the name is taken).
     */
    fun addCategory(book: Book, name: String, kind: app.penny.core.model.CategoryKind, onId: (String) -> Unit) {
        val existing = book.categories.firstOrNull { it.value.parentId == null && it.value.kind == kind && it.value.name.trim().equals(name.trim(), ignoreCase = true) }
        if (existing != null) { onId(existing.state.id); return }
        val id = repo.newId()
        onId(id)
        act(app.penny.R.string.category_added) { b -> addCategoryNamed(b, id, name, kind) }
    }

    fun act(@StringRes message: Int? = null, messageText: String? = null, after: (() -> Unit)? = null, op: suspend PennyRepository.(Book) -> String?) {
        val book = (state.value as? BookLoad.Ready)?.book ?: return
        // A second tap while the first save is still writing would create a duplicate and pop the screen twice.
        if (!inFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) { runCatching { repo.op(book) } }
                outcome.onFailure { e ->
                    android.util.Log.w("Penny", "op failed", e)
                    // Never report success for a write that did not happen; keep the user on the screen with their input.
                    undo.offer(UndoMessage(context.getString(app.penny.R.string.action_failed), undo = null))
                    return@launch
                }
                val group = outcome.getOrNull()
                val text = messageText ?: message?.let(context::getString)
                if (text != null) undo.offer(UndoMessage(text, undo = group?.let { g -> { repo.undo(g) } }))
                after?.invoke()
            } finally {
                inFlight.set(false)
            }
        }
    }

    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Runs without undo (reads, navigation side effects). */
    fun launch(block: suspend PennyRepository.() -> Unit) {
        viewModelScope.launch { withContext(Dispatchers.Default) { runCatching { repo.block() } } }
    }

    fun toast(text: String) = undo.offer(UndoMessage(text, undo = null))
}

/** Renders loading / error until the book is ready; one call per screen. */
@Composable
fun WithBook(vm: BookViewModel = hiltViewModel(), modifier: Modifier = Modifier, content: @Composable (Ctx, BookViewModel) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    when (val st = s) {
        BookLoad.Loading -> LoadingState(modifier)
        // Opened from a link or widget before setup finished: say so instead of spinning forever.
        BookLoad.NoProfile -> app.penny.ui.kit.EmptyState(
            androidx.compose.ui.res.stringResource(app.penny.R.string.setup_first),
            androidx.compose.ui.res.stringResource(app.penny.R.string.setup_first_body), modifier,
        )
        is BookLoad.Failed -> ErrorState(modifier)
        is BookLoad.Ready -> content(Ctx(st.book, st.pack, st.fmt, app.penny.ui.theme.LocalPenny.current.hideAmounts), vm)
    }
}
