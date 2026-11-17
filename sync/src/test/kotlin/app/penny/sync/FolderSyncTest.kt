package app.penny.sync

import app.penny.core.event.Actor
import app.penny.core.event.EventSourcedRepository
import app.penny.core.event.InMemoryEventStore
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.time.LocalDate

/** Decision 53: two devices converge through nothing but a shared folder, and the folder holds no readable data. */
class FolderSyncTest : FunSpec({
    class MemFolder : SyncFolder {
        val files = linkedMapOf<String, ByteArray>()
        override suspend fun list(dir: String) = files.keys.filter { it.substringBeforeLast('/', "") == dir }.map { it.substringAfterLast('/') }
        override suspend fun read(path: String) = files[path]
        override suspend fun write(path: String, bytes: ByteArray) { files[path] = bytes }
    }
    class MemKv : SyncKv {
        val m = mutableMapOf<String, String>()
        override suspend fun get(key: String) = m[key]
        override suspend fun put(key: String, value: String) { m[key] = value }
    }
    // PBKDF2 at 200k rounds is slow in tests; any deterministic phrase-to-key function exercises the same paths.
    val fastKey: (String, ByteArray) -> ByteArray = { p, salt -> MessageDigest.getInstance("SHA-256").digest(p.toByteArray() + salt) }
    val phrase = "able acid aged also area army away baby back ball band bank base bath bear beat bell belt best bird blow blue boat body"
    val tx = Transaction(type = TxType.EXPENSE, amountMinor = 1500, currency = "JPY", accountId = "cash", payee = "Lawson", date = LocalDate.of(2026, 11, 17), tz = "Asia/Tokyo")

    fun device(folder: SyncFolder, id: String): Triple<InMemoryEventStore, FolderSync, EventSourcedRepository<Transaction>> {
        val store = InMemoryEventStore()
        return Triple(store, FolderSync(store, MemKv(), folder, id, fastKey), EventSourcedRepository("transaction", Transaction.serializer(), store, { Actor("acct", id) }))
    }

    test("a second device reads the first device's book, and edits flow both ways") {
        val folder = MemFolder()
        val (a, fa, ra) = device(folder, "devA")
        val (b, fb, rb) = device(folder, "devB")
        ra.create("t1", "book1", tx)
        val masterA = fa.create(phrase)
        fa.sync(masterA, listOf("book1")).pushed shouldBe 1

        fb.isSetUp() shouldBe true
        val masterB = fb.unlock(phrase).shouldNotBeNull()
        fb.sync(masterB, emptyList()).pulled shouldBe 1
        rb.decode(b.current("transaction", "t1")!!.data).payee shouldBe "Lawson"

        rb.update("t1", tx.copy(amountMinor = 1800))
        fb.sync(masterB, listOf("book1"))
        fa.sync(masterA, listOf("book1"))
        ra.decode(a.current("transaction", "t1")!!.data).amountMinor shouldBe 1800
        a.current("transaction", "t1")!!.data shouldBe b.current("transaction", "t1")!!.data
    }

    test("a shared book's folder carries only that book; the person joining learns its id and gets it") {
        val folder = MemFolder()
        val (_, fa, ra) = device(folder, "devA")
        val (b, fb, rb) = device(folder, "devB")
        ra.create("private1", "personal", tx.copy(payee = "Private pharmacy"))
        ra.create("shared1", "space1", tx.copy(payee = "Rent"))
        fa.sync(fa.create(phrase), listOf("space1"))
        fb.streamIds() shouldBe listOf("space1")
        fb.sync(fb.unlock(phrase)!!, emptyList()).pulled shouldBe 1
        rb.decode(b.current("transaction", "shared1")!!.data).payee shouldBe "Rent"
        b.current("transaction", "private1").shouldBeNull()
    }

    test("the same book in two folders is sent to each in full (cursors are per folder)") {
        val one = MemFolder(); val two = MemFolder()
        val store = InMemoryEventStore()
        val kv = MemKv()
        val r = EventSourcedRepository("transaction", Transaction.serializer(), store, { Actor("acct", "devA") })
        r.create("t1", "space1", tx)
        val f1 = FolderSync(store, kv, one, "devA", fastKey, kvPrefix = "fsync")
        f1.sync(f1.create(phrase), listOf("space1")).pushed shouldBe 1
        val f2 = FolderSync(store, kv, two, "devA", fastKey, kvPrefix = "fspace.space1")
        f2.sync(f2.create(phrase), listOf("space1")).pushed shouldBe 1
    }

    test("a book deleted on this phone is not pulled back from the folder") {
        val folder = MemFolder()
        val (_, fa, ra) = device(folder, "devA")
        val (b, fb, _) = device(folder, "devB")
        ra.create("t1", "book1", tx)
        fa.sync(fa.create(phrase), listOf("book1"))
        fb.sync(fb.unlock(phrase)!!, emptyList(), skip = setOf("book1")).pulled shouldBe 0
        b.current("transaction", "t1").shouldBeNull()
    }

    test("a wrong phrase does not open the folder") {
        val folder = MemFolder()
        val (_, fa, _) = device(folder, "devA")
        fa.create(phrase)
        val (_, fb, _) = device(folder, "devB")
        fb.unlock(phrase.replace("able", "acid")).shouldBeNull()
    }

    test("the folder never contains readable amounts or payees") {
        val folder = MemFolder()
        val (_, fa, ra) = device(folder, "devA")
        ra.create("t1", "book1", tx)
        fa.sync(fa.create(phrase), listOf("book1"))
        folder.files.values.forEach { String(it, Charsets.ISO_8859_1) shouldNotContain "Lawson" }
    }

    test("a half-copied file is retried later and nothing after it is skipped; tool temp files are ignored") {
        val folder = MemFolder()
        val (_, fa, ra) = device(folder, "devA")
        val (b, fb, rb) = device(folder, "devB")
        val master = fa.create(phrase)
        ra.create("t1", "book1", tx)
        fa.sync(master, listOf("book1"))
        ra.create("t2", "book1", tx.copy(payee = "FamilyMart"))
        fa.sync(master, listOf("book1"))
        val first = folder.files.keys.filter { it.startsWith("streams/book1/devA_") }.minOf { it }
        val good = folder.files.getValue(first)
        folder.files[first] = good.copyOf(good.size / 2)
        folder.files["streams/book1/.syncthing.devA_000000000009.pnb.tmp"] = byteArrayOf(1, 2, 3)

        val r = fb.sync(fb.unlock(phrase)!!, emptyList())
        r.pulled shouldBe 0
        r.error.shouldNotBeNull()
        b.current("transaction", "t2").shouldBeNull()

        folder.files[first] = good
        fb.sync(fb.unlock(phrase)!!, emptyList()).pulled shouldBe 2
        rb.decode(b.current("transaction", "t2")!!.data).payee shouldBe "FamilyMart"
    }
})
