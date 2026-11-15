package app.penny.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SE-10 / CLAUDE.md rule 7: usage analytics are counts only — never amounts, payees, notes, ids or text — and only when
 * the user opts in. Counts are kept on the device; the user can see exactly what would be shared and clear it. There is
 * no third-party SDK and no network call here: an aggregated upload can be added to the relay later with this same
 * shape (a map of event name to count).
 */
@Singleton
class Telemetry @Inject constructor(@ApplicationContext context: Context, private val prefs: AppPrefs) {
    private val store = context.getSharedPreferences("penny.counts", Context.MODE_PRIVATE)

    /** Only fixed, code-defined event names are accepted, so no user data can slip into a count's key. */
    enum class Event { APP_OPEN, TX_ADDED, QUICK_ADD, VOICE_ADD, RECEIPT_SCANNED, CAPTURE_ACCEPTED, IMPORT_DONE, EXPORT_DONE, SYNC_ON, SPACE_CREATED, WIDGET_USED }

    fun count(e: Event) {
        if (!prefs.analyticsOptIn) return
        store.edit().putLong(e.name, store.getLong(e.name, 0) + 1).apply()
    }

    fun snapshot(): Map<String, Long> = Event.entries.associate { it.name to store.getLong(it.name, 0) }.filterValues { it > 0 }

    fun clear() = store.edit().clear().apply()
}
