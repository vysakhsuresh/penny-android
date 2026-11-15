package app.penny.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only event log (SY-01). Rows are never updated or deleted. */
@Entity(
    tableName = "events",
    indices = [
        Index(value = ["eventId"], unique = true), Index(value = ["entity", "entityId"]), Index(value = ["groupId"]),
        Index(value = ["profileId", "seq"]),
    ],
)
data class EventRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val eventId: String,
    val entity: String,
    val entityId: String,
    val profileId: String,
    val op: String,
    val patch: String,
    val before: String?,
    val actor: String,
    val device: String,
    val ts: Long,
    val groupId: String?,
    val reason: String?,
    val vclock: String,
    val ciphertextRef: String?,
    /** SY-02 Lamport clock; ordering key shared by all devices. */
    @androidx.room.ColumnInfo(defaultValue = "0") val lamport: Long = 0,
    /** True for events that came from another device (never pushed back). */
    @androidx.room.ColumnInfo(defaultValue = "0") val remote: Boolean = false,
)

/** Device-local key/value state that is not user data (sync cursors, last digest), never synced. */
@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val key: String, val value: String)

/** Materialised state of every entity, rebuilt from events. */
@Entity(tableName = "entities", primaryKeys = ["entity", "id"], indices = [Index(value = ["entity", "profileId"])])
data class EntityRow(
    val entity: String,
    val id: String,
    val profileId: String,
    val json: String,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val lastActor: String,
)

/** Query-friendly materialised view of transactions (period sums, lists, search). */
@Entity(tableName = "transactions", indices = [Index(value = ["profileId", "dateEpochDay"]), Index(value = ["accountId"])])
data class TransactionRow(
    @PrimaryKey val id: String,
    val profileId: String,
    val type: String,
    val amountMinor: Long,
    val currency: String,
    val accountId: String,
    val toAccountId: String?,
    val categoryId: String?,
    val payee: String?,
    val note: String,
    val dateEpochDay: Long,
    val timeMinutes: Int?,
    val json: String,
    val version: Int,
    val updatedAt: Long,
    val deletedAt: Long?,
)

/** Full-text search over payee and note (SR-01), offline. */
@Fts4(contentEntity = TransactionRow::class)
@Entity(tableName = "transactions_fts")
data class TransactionFts(val payee: String?, val note: String)
