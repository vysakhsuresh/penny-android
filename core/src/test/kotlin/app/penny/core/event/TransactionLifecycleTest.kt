package app.penny.core.event

import app.penny.core.model.GeoPoint
import app.penny.core.model.Split
import app.penny.core.model.Transaction
import app.penny.core.model.TxStatus
import app.penny.core.model.TxType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.LocalTime

class TransactionLifecycleTest : FunSpec({
    fun repo(store: EventStore): EventSourcedRepository<Transaction> {
        var t = 1_000L
        var n = 0
        return EventSourcedRepository(
            Entities.TRANSACTION, Transaction.serializer(), store,
            actor = { Actor("me", "phone") }, clock = { t++ }, ids = { "id${n++}" },
        )
    }

    val base = Transaction(
        type = TxType.EXPENSE, amountMinor = 1250, currency = "INR", accountId = "cash",
        categoryId = "food", payee = "Chai point", date = LocalDate.of(2026, 11, 14), tz = "Asia/Kolkata",
    )

    test("create, read, update every field, delete, restore and purge") {
        val store = InMemoryEventStore()
        val r = repo(store)
        r.create("t1", "p1", base)
        r.get("t1")!!.value shouldBe base

        val everyField = base.copy(
            type = TxType.TRANSFER, amountMinor = 99_000, currency = "USD", accountId = "bank", toAccountId = "card",
            toAmountMinor = 8_200_000, toCurrency = "INR", categoryId = null, payee = "Self", date = LocalDate.of(2026, 1, 31),
            time = LocalTime.of(9, 30), tz = "America/New_York", note = "moved", tags = listOf("trip", "work"),
            status = TxStatus.RECONCILED, refundOf = "t0", splits = listOf(Split(99_000, "food")), attachmentIds = listOf("a1"),
            location = GeoPoint(1.0, 2.0, "here"), recurringId = "r", groupExpenseId = "g", lendingId = "l",
            remittanceId = "rm", circleId = "c", isPrivate = true, excludeFromBudget = true, excludeFromReports = true, source = "import",
        )
        r.update("t1", everyField).shouldNotBeNull()
        r.get("t1")!!.value shouldBe everyField
        r.update("t1", everyField).shouldBeNull()

        r.delete("t1")
        r.get("t1")!!.state.deletedAt.shouldNotBeNull()
        r.list("p1").shouldBeEmpty()
        r.trash("p1") shouldHaveSize 1

        r.restore("t1")
        r.get("t1")!!.state.deletedAt.shouldBeNull()
        r.get("t1")!!.value shouldBe everyField

        r.purge("t1")
        r.get("t1").shouldBeNull()
    }

    test("undo reverses delete, edit and create with compensating events; the log only grows") {
        val store = InMemoryEventStore()
        val r = repo(store)
        val created = r.create("t1", "p1", base)
        val edited = r.update("t1", base.copy(amountMinor = 5000, note = "x"))!!
        val deleted = r.delete("t1")

        r.undo(deleted)
        r.get("t1")!!.state.deletedAt.shouldBeNull()
        r.undo(edited)
        r.get("t1")!!.value shouldBe base
        r.undo(created)
        r.get("t1").shouldBeNull()
        store.allEvents() shouldHaveSize 6
    }

    test("history lists versions with actor and diffs, and revert creates a new version") {
        val store = InMemoryEventStore()
        val r = repo(store)
        r.create("t1", "p1", base)
        r.update("t1", base.copy(amountMinor = 2000))
        r.update("t1", base.copy(amountMinor = 3000, note = "late"))

        val h = r.history("t1")
        h shouldHaveSize 3
        h[1].changes.single().field shouldBe "amountMinor"
        h[1].changes.single().before.toString() shouldBe "1250"
        h[1].changes.single().after.toString() shouldBe "2000"
        h.all { it.actor == "me" } shouldBe true

        r.revertTo("t1", 1)
        r.get("t1")!!.value shouldBe base
        r.history("t1").last().reason shouldBe "revert:v1"
        r.get("t1")!!.state.version shouldBe 4
    }

    test("materialised state is reproducible from the event log") {
        val store = InMemoryEventStore()
        val r = repo(store)
        r.create("t1", "p1", base)
        r.create("t2", "p1", base.copy(amountMinor = 7))
        r.update("t1", base.copy(note = "n"))
        r.delete("t2")
        val rebuilt = Projector.rebuild(store.allEvents())
        rebuilt[Entities.TRANSACTION to "t1"]!!.data shouldBe store.current(Entities.TRANSACTION, "t1")!!.data
        rebuilt[Entities.TRANSACTION to "t2"]!!.deletedAt shouldBe store.current(Entities.TRANSACTION, "t2")!!.deletedAt
    }
})
