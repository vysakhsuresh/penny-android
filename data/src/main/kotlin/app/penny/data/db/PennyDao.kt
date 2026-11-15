package app.penny.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PennyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(row: EventRow): Long

    @Query("SELECT * FROM events WHERE entity = :entity AND entityId = :entityId ORDER BY seq")
    suspend fun eventsFor(entity: String, entityId: String): List<EventRow>

    @Query("SELECT * FROM events WHERE groupId = :groupId ORDER BY seq")
    suspend fun eventsInGroup(groupId: String): List<EventRow>

    @Query("SELECT * FROM events ORDER BY seq")
    suspend fun allEvents(): List<EventRow>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun eventCount(): Long

    @Query("SELECT * FROM entities WHERE entity = :entity AND id = :id")
    suspend fun entity(entity: String, id: String): EntityRow?

    @Upsert
    suspend fun upsertEntity(row: EntityRow)

    @Query("DELETE FROM entities WHERE entity = :entity AND id = :id")
    suspend fun deleteEntity(entity: String, id: String)

    @Query("SELECT * FROM entities WHERE entity = :entity AND profileId = :profileId AND (:includeDeleted OR deletedAt IS NULL)")
    suspend fun entities(entity: String, profileId: String, includeDeleted: Boolean): List<EntityRow>

    @Query("SELECT * FROM entities WHERE entity = :entity AND profileId = :profileId AND (:includeDeleted OR deletedAt IS NULL)")
    fun observeEntities(entity: String, profileId: String, includeDeleted: Boolean): Flow<List<EntityRow>>

    @Query("SELECT * FROM entities WHERE entity = :entity AND deletedAt IS NULL")
    fun observeAllOf(entity: String): Flow<List<EntityRow>>

    @Upsert
    suspend fun upsertTransaction(row: TransactionRow)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String)

    @Query(
        "SELECT * FROM transactions WHERE profileId = :profileId AND deletedAt IS NULL " +
            "ORDER BY dateEpochDay DESC, timeMinutes DESC, updatedAt DESC LIMIT :limit",
    )
    fun observeRecent(profileId: String, limit: Int): Flow<List<TransactionRow>>

    @Query(
        "SELECT * FROM transactions WHERE profileId = :profileId AND deletedAt IS NULL " +
            "AND dateEpochDay BETWEEN :fromDay AND :toDay ORDER BY dateEpochDay DESC, timeMinutes DESC",
    )
    fun observeRange(profileId: String, fromDay: Long, toDay: Long): Flow<List<TransactionRow>>

    @Query("SELECT * FROM transactions WHERE profileId = :profileId AND deletedAt IS NULL")
    fun observeAllTransactions(profileId: String): Flow<List<TransactionRow>>

    @Query("SELECT * FROM transactions WHERE profileId = :profileId AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(profileId: String): Flow<List<TransactionRow>>

    @Query(
        "SELECT t.* FROM transactions t JOIN transactions_fts f ON t.rowid = f.rowid " +
            "WHERE transactions_fts MATCH :query AND t.profileId = :profileId AND t.deletedAt IS NULL ORDER BY t.dateEpochDay DESC",
    )
    suspend fun search(profileId: String, query: String): List<TransactionRow>

    @Query("DELETE FROM entities")
    suspend fun clearEntities()

    @Query("DELETE FROM transactions")
    suspend fun clearTransactions()
}
