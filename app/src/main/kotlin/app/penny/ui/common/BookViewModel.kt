package app.penny.ui.common

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
data class Ctx(val book: Book, val pack: RegionalPack, val fmt: MoneyFmt) {
    val base: String get() = book.profile.baseCurrency
    fun money(minor: Long, currency: String = base, signed: Boolean = false) = fmt.format(minor, currency, signed)
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

    fun ctx(): Ctx? = (state.value as? BookLoad.Ready)?.let { Ctx(it.book, it.pack, it.fmt) }

    /**
     * Runs [op] off the main thread; when it returns an undo group and [message] is set, shows the 5-second undo
     * snackbar. [after] runs on the main thread when done (navigation).
     */
    fun act(@StringRes message: Int? = null, messageText: String? = null, after: (() -> Unit)? = null, op: suspend PennyRepository.(Book) -> String?) {
        val book = (state.value as? BookLoad.Ready)?.book ?: return
        viewModelScope.launch {
            val group = withContext(Dispatchers.Default) { runCatching { repo.op(book) }.getOrElse { e -> android.util.Log.w("Penny", "op failed", e); null } }
            val text = messageText ?: message?.let(context::getString)
            if (text != null) undo.offer(UndoMessage(text, undo = group?.let { g -> { repo.undo(g) } }))
            after?.invoke()
        }
    }

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
        BookLoad.Loading, BookLoad.NoProfile -> LoadingState(modifier)
        is BookLoad.Failed -> ErrorState(modifier)
        is BookLoad.Ready -> content(Ctx(st.book, st.pack, st.fmt), vm)
    }
}
