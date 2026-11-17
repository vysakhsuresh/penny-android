package app.penny.domain.ops

import app.penny.core.Brand
import app.penny.core.event.Entities
import app.penny.core.event.Event
import app.penny.core.event.PennyJson
import app.penny.core.io.Csv
import app.penny.core.io.ImportedRow
import app.penny.core.model.Category
import app.penny.core.model.CategoryKind
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import app.penny.core.money.CurrencyUnit
import app.penny.core.rules.Dedupe
import app.penny.core.rules.RuleContext
import app.penny.core.rules.RuleEngine
import app.penny.domain.Book
import app.penny.domain.PennyRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.math.BigDecimal

/** SY-07: full export with schema version; the event log is included so import restores history too (SY-08). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class FullExport(
    // Always written (the shared Json omits defaults): a file must say what it is and which version wrote it.
    @kotlinx.serialization.EncodeDefault val app: String = Brand.EXPORT_APP_ID,
    // Files written before the version was always included are version 2; a missing value must keep meaning 2 after
    // SCHEMA moves on, so the default is that fixed number and new exports pass SCHEMA explicitly.
    @kotlinx.serialization.EncodeDefault val schema: Int = UNVERSIONED_SCHEMA,
    val exportedAt: Long,
    val profileId: String,
    val events: List<Event>,
) {
    companion object {
        const val SCHEMA = 2
        const val UNVERSIONED_SCHEMA = 2
    }
}

suspend fun PennyRepository.exportJson(profileId: String): String =
    PennyJson.encodeToString(FullExport.serializer(), FullExport(schema = FullExport.SCHEMA, exportedAt = System.currentTimeMillis(), profileId = profileId, events = store.allEventsFor(profileId)))

/**
 * SY-08 import of own export: events are ingested (idempotent: known ids are skipped), so importing twice is safe and
 * importing into a fresh install restores everything including history. Returns (profileId, new events).
 */
suspend fun PennyRepository.importJson(json: String): Pair<String, Int> {
    val exp = PennyJson.decodeFromString(FullExport.serializer(), json)
    require(exp.app == Brand.EXPORT_APP_ID) { "Not a ${Brand.NAME} export" }
    require(exp.schema <= FullExport.SCHEMA) { "Export is from a newer version" }
    // Own data: stored as local so it is pushed when sync is on (a restored phone must not keep it to itself).
    val n = store.ingest(exp.events, remote = false).size
    return exp.profileId to n
}

/** SY-07 CSV per table: transactions with readable names plus a raw JSON column; other tables as id + JSON. */
fun Book.exportCsv(): Map<String, String> {
    val tx = listOf(listOf("id", "date", "time", "type", "amount", "currency", "account", "to_account", "category", "payee", "note", "tags", "status", "private", "exclude_budget", "exclude_reports", "source")) +
        transactions.sortedBy { it.value.date }.map { s ->
            val t = s.value
            listOf(
                s.state.id, t.date.toString(), t.time?.toString().orEmpty(), t.type.name.lowercase(),
                BigDecimal.valueOf(t.amountMinor, CurrencyUnit.of(t.currency).minorUnits).toPlainString(), t.currency,
                accountName(t.accountId), accountName(t.toAccountId), categoryName(t.categoryId), t.payee.orEmpty(), t.note,
                t.tags.joinToString(";"), t.status.name.lowercase(), t.isPrivate.toString(), t.excludeFromBudget.toString(), t.excludeFromReports.toString(), t.source,
            )
        }
    val out = linkedMapOf("transactions.csv" to Csv.write(tx))
    val tables = listOf(
        Entities.ACCOUNT, Entities.CATEGORY, Entities.TAG, Entities.PAYEE, Entities.RULE, Entities.ENVELOPE, Entities.ALLOCATION, Entities.GOAL,
        Entities.GOAL_ENTRY, Entities.RECURRING, Entities.OCCURRENCE, Entities.TEMPLATE, Entities.SAVED_SEARCH, Entities.PERSON, Entities.LENDING,
        Entities.GROUP, Entities.GROUP_EXPENSE, Entities.SETTLEMENT, Entities.LOAN, Entities.REMITTANCE, Entities.CIRCLE,
        Entities.CIRCLE_CONTRIBUTION, Entities.ATTACHMENT, Entities.VALUATION, Entities.FX_RATE, Entities.WATCHLIST,
    )
    tables.forEach { e ->
        // Raw stored JSON: asking the decode cache for JsonObject returned already-decoded objects and the export crashed.
        val items = raw(e)
        if (items.isNotEmpty()) out["$e.csv"] = Csv.write(listOf(listOf("id", "created", "updated", "json")) + items.map { listOf(it.id, it.createdAt.toString(), it.updatedAt.toString(), it.data.toString()) })
    }
    return out
}

data class ImportPlan(val rows: List<ImportedRow>, val transactions: List<Transaction>, val duplicates: Set<Int>, val newCategories: List<String>)

/**
 * CP-09 preview: maps imported rows to transactions on [accountId], matches category names (creating missing ones on
 * commit), applies rules, and marks duplicates against existing data (by external id or TX-10 rules).
 */
fun Book.planImport(rows: List<ImportedRow>, accountId: String): ImportPlan {
    val acct = accountMap[accountId]?.value
    val catByName = categories.associateBy { it.value.name.lowercase() }
    val newCats = mutableSetOf<String>()
    val existing = transactions.map { it.state.id to it.value }
    val externals = txList.flatMap { it.externalIds }.toSet()
    val rules = this.rules.map { it.state.id to it.value }
    val txs = rows.map { r ->
        val catName = r.category.substringAfterLast(':').substringAfterLast('/').substringAfterLast('›').trim()
        val cat = catByName[catName.lowercase()]?.state?.id ?: catName.takeIf { it.isNotEmpty() }?.also { newCats += it }?.let { "new:$it" }
        val income = r.amountMinor > 0
        val transferTo = r.transferAccount?.let { n -> accounts.firstOrNull { it.value.name.equals(n, true) }?.state?.id }
        val base = Transaction(
            type = when { transferTo != null -> if (income) TxType.TRANSFER else TxType.TRANSFER; income -> TxType.INCOME; else -> TxType.EXPENSE },
            amountMinor = kotlin.math.abs(r.amountMinor), currency = if (acct != null && r.currency == profile.baseCurrency) acct.currency else r.currency,
            accountId = if (transferTo != null && income) transferTo else accountId, toAccountId = if (transferTo != null) (if (income) accountId else transferTo) else null,
            categoryId = cat.takeIf { transferTo == null }, payee = r.payee.takeIf { it.isNotBlank() }, date = r.date, tz = profile.timezone, note = r.note,
            tags = r.tags, status = TxStatus.CLEARED, externalIds = listOf(r.externalId), source = "import",
        )
        RuleEngine.apply(base, rules, RuleContext(accountName = ::accountName)).tx
    }
    val dupes = txs.withIndex().filter { (_, t) -> t.externalIds.any { it in externals } || Dedupe.candidates(t, existing).isNotEmpty() }.map { it.index }.toSet()
    return ImportPlan(rows, txs, dupes, newCats.toList())
}

suspend fun PennyRepository.commitImport(book: Book, plan: ImportPlan, skipDuplicates: Boolean): Pair<String, Int> {
    val g = transactions.newGroup()
    val catIds = plan.newCategories.associateWith { name ->
        newId().also { categories.create(it, book.profileId, Category(kind = CategoryKind.EXPENSE, name = name, sort = book.categories.size), g) }
    }
    val chosen = plan.transactions.withIndex().filter { !skipDuplicates || it.index !in plan.duplicates }.map { it.value }
    if (chosen.isNotEmpty()) {
        transactions.createAll(book.profileId, chosen.map { t ->
            newId() to t.copy(categoryId = t.categoryId?.let { c -> if (c.startsWith("new:")) catIds[c.removePrefix("new:")] else c })
        }, groupId = g)
    }
    return g to chosen.size
}

@Suppress("unused")
private val keepSerializer = ListSerializer(Event.serializer())
