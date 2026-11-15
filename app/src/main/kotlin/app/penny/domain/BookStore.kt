package app.penny.domain

import app.penny.packs.RegionalPack
import app.penny.ui.common.MoneyFmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookLoad {
    data object Loading : BookLoad
    data object NoProfile : BookLoad
    data class Failed(val error: Throwable) : BookLoad
    data class Ready(val book: Book, val pack: RegionalPack, val fmt: MoneyFmt) : BookLoad
}

/** One shared, decoded snapshot of the open profile for every screen (decoded off the main thread). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BookStore @Inject constructor(private val repo: PennyRepository) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<BookLoad> = repo.observeBook().map { b ->
        if (b == null) {
            if (repo.currentProfileId.value == null) BookLoad.NoProfile else BookLoad.Loading
        } else {
            val pack = repo.packs.get(b.profile.regionPack)
            BookLoad.Ready(b, pack, MoneyFmt.of(b.profile, pack))
        }
    }.catch { emit(BookLoad.Failed(it)) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, BookLoad.Loading)

    fun ready(): BookLoad.Ready? = state.value as? BookLoad.Ready
}
