package app.penny.core.event

import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.LocalDate

/** SY-02 two-device conflict suite: any interleaving of local edits and exchanges converges to the same state. */
class SyncConvergenceTest : FunSpec({
    fun repo(store: EventStore, device: String) =
        EventSourcedRepository("transaction", Transaction.serializer(), store, { Actor("acct-$device", device) })

    val base = Transaction(type = TxType.EXPENSE, amountMinor = 100, currency = "INR", accountId = "a", date = LocalDate.of(2026, 11, 1), tz = "UTC")

    suspend fun exchange(a: InMemoryEventStore, b: InMemoryEventStore) {
        b.ingest(a.allEvents())
        a.ingest(b.allEvents())
    }

    test("concurrent edits to different fields both survive; same field resolves to one winner on both devices") {
        val a = InMemoryEventStore()
        val b = InMemoryEventStore()
        val ra = repo(a, "A")
        val rb = repo(b, "B")
        ra.create("t1", "p", base)
        exchange(a, b)
        ra.update("t1", base.copy(note = "from A", amountMinor = 200))
        rb.update("t1", base.copy(payee = "Shop", amountMinor = 300))
        exchange(a, b)
        val sa = a.current("transaction", "t1")!!
        val sb = b.current("transaction", "t1")!!
        sa.data shouldBe sb.data
        val t = ra.decode(sa.data)
        t.note shouldBe "from A"
        t.payee shouldBe "Shop"
        // Conflict on amount is detected and both values remain in the log.
        val conflicts = EventOrder.conflicts(a.allEvents().filter { it.entityId == "t1" })
        conflicts shouldHaveSize 1
        conflicts[0].field shouldBe "amountMinor"
    }

    test("delete on one device and edit on another: both events kept, states equal") {
        val a = InMemoryEventStore()
        val b = InMemoryEventStore()
        repo(a, "A").create("t1", "p", base)
        exchange(a, b)
        repo(a, "A").delete("t1")
        repo(b, "B").update("t1", base.copy(note = "edited"))
        exchange(a, b)
        a.current("transaction", "t1") shouldBe b.current("transaction", "t1")
        a.allEvents().size shouldBe b.allEvents().size
    }

    test("property: random interleavings converge") {
        checkAll(60, Arb.list(Arb.int(0, 5), 1..25)) { script ->
            val a = InMemoryEventStore()
            val b = InMemoryEventStore()
            val ra = repo(a, "A")
            val rb = repo(b, "B")
            ra.create("t1", "p", base)
            exchange(a, b)
            script.forEachIndexed { i, op ->
                when (op) {
                    0 -> ra.get("t1")?.let { ra.update("t1", it.value.copy(amountMinor = 1_000L + i)) }
                    1 -> rb.get("t1")?.let { rb.update("t1", it.value.copy(note = "b$i")) }
                    2 -> if (a.current("transaction", "t1")?.deletedAt == null) ra.delete("t1") else ra.restore("t1")
                    3 -> rb.get("t1")?.let { rb.update("t1", it.value.copy(amountMinor = 2_000L + i, payee = "p$i")) }
                    4 -> exchange(a, b)
                    else -> b.ingest(a.allEvents())
                }
            }
            exchange(a, b)
            a.current("transaction", "t1")?.data shouldBe b.current("transaction", "t1")?.data
            a.current("transaction", "t1")?.deletedAt?.let { 1 } shouldBe b.current("transaction", "t1")?.deletedAt?.let { 1 }
        }
    }
})
