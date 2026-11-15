package app.penny.domain

import app.penny.core.model.AccountType
import app.penny.core.model.Envelope
import app.penny.core.model.FinancialAccount
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.domain.ops.catId
import java.time.LocalDate
import java.time.ZoneId

/**
 * Plausible sample data for the decoy book (SE-05) and for "try with sample data". Amounts scale with the currency's
 * typical chip so they look natural in any region; nothing here is real.
 */
object SampleData {
    suspend fun seed(repo: PennyRepository, pid: String) {
        val p = repo.profiles.get(pid)?.value ?: return
        val pack = repo.packs.get(p.regionPack)
        val unit = (pack.typicalAmountChips.getOrNull(2) ?: 100L) * Math.pow(10.0, app.penny.core.money.CurrencyUnit.of(p.baseCurrency).minorUnits.toDouble()).toLong()
        val today = LocalDate.now(runCatching { ZoneId.of(p.timezone) }.getOrDefault(ZoneId.systemDefault()))
        val cash = p.defaultAccountId ?: return
        val bank = repo.newId()
        val g = repo.accounts.newGroup()
        repo.accounts.create(bank, pid, FinancialAccount("Bank", AccountType.BANK, p.baseCurrency, unit * 60, today.minusMonths(2)), g)
        fun tx(daysAgo: Long, type: TxType, mult: Long, cat: String?, payee: String?, acct: String = bank) = repo.newId() to Transaction(
            type = type, amountMinor = unit * mult / 10, currency = p.baseCurrency, accountId = acct, categoryId = cat?.let { catId(pid, it) },
            payee = payee, date = today.minusDays(daysAgo), tz = p.timezone, source = "sample",
        )
        repo.transactions.createAll(pid, listOf(
            tx(today.dayOfMonth.toLong() - 1, TxType.INCOME, 400, "inc.salary", "Employer"),
            tx(2, TxType.EXPENSE, 32, "food.groc", "Market"), tx(3, TxType.EXPENSE, 9, "eat.cafe", "Cafe", cash),
            tx(5, TxType.EXPENSE, 120, "home.rent", "Landlord"), tx(6, TxType.EXPENSE, 14, "trans.ride", "Ride", cash),
            tx(8, TxType.EXPENSE, 22, "util.elec", "Power company"), tx(9, TxType.EXPENSE, 18, "eat.rest", "Restaurant"),
            tx(12, TxType.EXPENSE, 7, "fun.stream", "Streaming"), tx(14, TxType.EXPENSE, 26, "shop.cloth", "Clothing store"),
        ).filter { it.second.date.isAfter(today.minusDays(40)) }, groupId = g)
        repo.envelopes.createAll(pid, listOf(
            repo.newId() to Envelope("Food", listOf(catId(pid, "food"), catId(pid, "eat")), limitMinor = unit * 9, currency = p.baseCurrency, startedOn = today.withDayOfMonth(1)),
            repo.newId() to Envelope("Transport", listOf(catId(pid, "trans")), limitMinor = unit * 3, currency = p.baseCurrency, startedOn = today.withDayOfMonth(1)),
        ), groupId = g)
    }
}
