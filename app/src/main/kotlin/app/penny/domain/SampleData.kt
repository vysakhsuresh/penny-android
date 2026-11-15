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
            // Salary on the first day of the current pay period, whatever the cycle.
            tx(java.time.temporal.ChronoUnit.DAYS.between(app.penny.core.period.Periods.periodFor(today, p.cycle).start, today), TxType.INCOME, 400, "inc.salary", "Employer"),
            tx(2, TxType.EXPENSE, 32, "food.groc", "Market"), tx(3, TxType.EXPENSE, 9, "eat.cafe", "Cafe", cash),
            tx(5, TxType.EXPENSE, 120, "home.rent", "Landlord"), tx(6, TxType.EXPENSE, 14, "trans.ride", "Ride", cash),
            tx(8, TxType.EXPENSE, 22, "util.elec", "Power company"), tx(9, TxType.EXPENSE, 18, "eat.rest", "Restaurant"),
            tx(12, TxType.EXPENSE, 7, "fun.stream", "Streaming"), tx(14, TxType.EXPENSE, 26, "shop.cloth", "Clothing store"),
        ).filter { it.second.date.isAfter(today.minusDays(40)) }, groupId = g)
        // Last month too, so trends, comparisons and detection have something to show.
        repo.transactions.createAll(pid, (1..3).flatMap { m ->
            val d = today.minusMonths(m.toLong())
            listOf(
                repo.newId() to Transaction(type = TxType.EXPENSE, amountMinor = unit * 7 / 10, currency = p.baseCurrency, accountId = bank, categoryId = catId(pid, "fun.stream"), payee = "Streaming", date = d.withDayOfMonth(minOf(12, d.lengthOfMonth())), tz = p.timezone, source = "sample"),
                repo.newId() to Transaction(type = TxType.EXPENSE, amountMinor = unit * (28 + m * 3) / 10, currency = p.baseCurrency, accountId = bank, categoryId = catId(pid, "food.groc"), payee = "Market", date = d.withDayOfMonth(5), tz = p.timezone, source = "sample"),
                repo.newId() to Transaction(type = TxType.INCOME, amountMinor = unit * 40, currency = p.baseCurrency, accountId = bank, categoryId = catId(pid, "inc.salary"), payee = "Employer", date = d.withDayOfMonth(1), tz = p.timezone, source = "sample"),
            )
        }, groupId = g)
        repo.recurring.create(repo.newId(), pid, app.penny.core.model.Recurring("Rent", amountMinor = unit * 12, currency = p.baseCurrency, accountId = bank, categoryId = catId(pid, "home.rent"), payee = "Landlord",
            schedule = app.penny.core.schedule.Recurrence.monthly(today.withDayOfMonth(1).plusMonths(1), 1)), g)
        repo.recurring.create(repo.newId(), pid, app.penny.core.model.Recurring("Streaming", app.penny.core.model.RecurringKind.SUBSCRIPTION, amountMinor = unit * 7 / 10, currency = p.baseCurrency, accountId = bank,
            categoryId = catId(pid, "fun.stream"), payee = "Streaming", schedule = app.penny.core.schedule.Recurrence.monthly(today.plusDays(4), today.plusDays(4).dayOfMonth)), g)
        repo.goals.create(repo.newId(), pid, app.penny.core.model.Goal("New laptop", unit * 80, p.baseCurrency, due = today.plusMonths(6)), g)
        val friend = repo.newId()
        repo.people.create(friend, pid, app.penny.core.model.Person("Sam"), g)
        repo.lending.create(repo.newId(), pid, app.penny.core.model.LendingEntry(friend, app.penny.core.model.LendDirection.GAVE, unit * 5, p.baseCurrency, today.minusDays(10)), g)
        repo.templates.create(repo.newId(), pid, app.penny.core.model.Template("Coffee", amountMinor = unit * 4 / 10, currency = p.baseCurrency, accountId = cash, categoryId = catId(pid, "eat.cafe"), payee = "Cafe"), g)
        repo.envelopes.createAll(pid, listOf(
            repo.newId() to Envelope("Food", listOf(catId(pid, "food"), catId(pid, "eat")), limitMinor = unit * 9, currency = p.baseCurrency, startedOn = today.withDayOfMonth(1)),
            repo.newId() to Envelope("Transport", listOf(catId(pid, "trans")), limitMinor = unit * 3, currency = p.baseCurrency, startedOn = today.withDayOfMonth(1)),
        ), groupId = g)
    }
}
