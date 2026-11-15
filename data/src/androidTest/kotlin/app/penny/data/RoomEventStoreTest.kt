package app.penny.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.penny.core.event.Actor
import app.penny.core.event.Entities
import app.penny.core.event.EventSourcedRepository
import app.penny.core.model.Transaction
import app.penny.core.model.TxType
import app.penny.data.db.PennyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RoomEventStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedDatabaseRoundTripsTransactionLifecycle() = runTest {
        ctx.deleteDatabase("penny-test.db")
        System.loadLibrary("sqlcipher")
        val db = androidx.room.Room.databaseBuilder(ctx, PennyDatabase::class.java, "penny-test.db")
            .openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(ByteArray(32) { it.toByte() }))
            .build()
        val store = RoomEventStore(db)
        val repo = EventSourcedRepository(Entities.TRANSACTION, Transaction.serializer(), store, { Actor("me", "test") })
        val t = Transaction(TxType.EXPENSE, 500, "INR", "cash", payee = "Chai point", date = LocalDate.of(2026, 11, 15), tz = "Asia/Kolkata")

        repo.create("t1", "p1", t)
        repo.update("t1", t.copy(note = "masala"))
        assertEquals(1, db.dao().search("p1", "masala").size)
        val g = repo.delete("t1")
        assertEquals(0, db.dao().observeRecent("p1", 10).first().size)
        repo.undo(g)
        assertEquals("masala", repo.get("t1")!!.value.note)

        val before = store.current(Entities.TRANSACTION, "t1")
        store.rebuild()
        assertEquals(before, store.current(Entities.TRANSACTION, "t1"))
        assertEquals(4, repo.history("t1").size) // create, update, delete, undo-restore
        repo.purge("t1")
        assertNull(repo.get("t1"))
        db.close()
    }
}
